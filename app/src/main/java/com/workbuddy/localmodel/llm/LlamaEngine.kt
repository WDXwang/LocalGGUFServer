package com.workbuddy.localmodel.llm

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 全局唯一的推理引擎。UI 聊天与 HTTP 服务共享同一个模型实例，
 * 通过 Mutex 串行化，避免 llama.cpp context 的并发竞争。
 */
object LlamaEngine {

    /**
     * 单次请求的 prompt token 预算。
     *
     * 真机实测（天玑 9300+ / Mali-G720，Qwen3.5-4B Q4_K_M，32 层全量卸载）prefill 稳定在
     * 82~90 ms/token，且与 n_batch 大小无关（128/256/512/1024 四档差异都在 ±10% 内），
     * 因为这批 Vulkan 路径没有吃到批处理并行收益。换算下来 prompt 每多 100 token 就多等约 9 秒。
     *
     * 1400 token ≈ 2 分钟等待，是"还能接受"和"点一下等半天"的分界。
     * ctx 8192 只是容量上限，不代表该用满 —— 每轮都是全量重算，所以按这个预算裁历史。
     */
    private const val PROMPT_TOKEN_BUDGET = 1400

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val generationMutex = Mutex()
    private val freeLock = Any()

    /** 应用 Context，用于加载前查询可用内存 */
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var info: ModelInfo? = null

    val currentInfo: ModelInfo? get() = info

    val isReady: Boolean get() = handle != 0L

    /** 供 OpenAI 接口返回的模型名 */
    val modelId: String
        get() = info?.name?.substringBeforeLast('.') ?: "local-model"

    suspend fun load(file: File, config: LoadConfig): Result<ModelInfo> =
        withContext(Dispatchers.Default) {
            generationMutex.withLock {
                releaseLocked()

                _state.value = EngineState.Loading(file.name)
                try {
                    // 加载前内存预估：宁可拒绝加载并提示换小模型，也不要让 native 在
                    // 分配中途 OOM 被杀（后者在真机上表现为"无声闪退"，拿不到任何日志）
                    memoryShortfall(file, config)?.let { message ->
                        _state.value = EngineState.Failed(message)
                        return@withLock Result.failure(IllegalStateException(message))
                    }

                    val h = LlamaBridge.nativeLoadModel(
                        path = file.absolutePath,
                        nThreads = config.threads,
                        nCtx = config.contextSize,
                        nBatch = config.batchSize,
                        nGpuLayers = config.gpuLayers,
                        nMlock = config.useMlock
                    )
                    if (h == 0L) {
                        val message = "模型加载失败，请确认文件是有效的 GGUF 且内存充足"
                        _state.value = EngineState.Failed(message)
                        return@withLock Result.failure(IllegalStateException(message))
                    }

                    val loaded = ModelInfo(
                        name = file.name,
                        path = file.absolutePath,
                        desc = LlamaBridge.nativeModelDesc(h),
                        parameters = LlamaBridge.nativeModelParams(h),
                        sizeBytes = LlamaBridge.nativeModelSize(h),
                        contextSize = LlamaBridge.nativeContextSize(h),
                        hasChatTemplate = LlamaBridge.nativeHasChatTemplate(h)
                    )
                    val gpu = parseGpuInfo(LlamaBridge.nativeGpuInfo(h))
                    val infoWithGpu = loaded.copy(
                        gpuLayersRequested = gpu.requested,
                        gpuLayersEffective = gpu.effective,
                        layerCount = gpu.layers,
                        gpuOffloadSupported = gpu.offload,
                        gpuDevices = gpu.devices
                    )
                    synchronized(freeLock) { handle = h }
                    info = infoWithGpu
                    _state.value = EngineState.Ready(infoWithGpu)
                    Result.success(infoWithGpu)
                } catch (t: Throwable) {
                    val message = t.message ?: t.javaClass.simpleName
                    _state.value = EngineState.Failed(message)
                    Result.failure(t)
                }
            }
        }

    suspend fun unload() = withContext(Dispatchers.Default) {
        generationMutex.withLock {
            releaseLocked()
            _state.value = EngineState.Idle
            Result.success(Unit)
        }
    }

    /** 中断当前生成（可从任意线程调用） */
    fun cancel() {
        synchronized(freeLock) {
            val h = handle
            if (h != 0L) {
                LlamaBridge.nativeStop(h)
            }
        }
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        params: GenParams,
        onToken: (String) -> Unit = {},
        onNotice: (String) -> Unit = {}
    ): Result<GenResult> = generationMutex.withLock {
        val h = handle
        if (h == 0L) {
            return@withLock Result.failure(IllegalStateException("尚未加载模型"))
        }
        try {
            // 预设的系统提示词作为第一条 system 消息参与 chat 模板；
            // 调用方自己已经带了 system 消息就不重复插入
            var effective = if (params.systemPrompt.isBlank() || messages.firstOrNull()?.role == "system") {
                messages
            } else {
                listOf(ChatMessage("system", params.systemPrompt)) + messages
            }
            var prompt = LlamaBridge.nativeBuildPrompt(
                h,
                effective.map { it.role }.toTypedArray(),
                effective.map { it.content }.toTypedArray(),
                true
            )

            // 上下文保护：两个预算取更严的那个
            //   1) 模型上限：n_ctx 的 80% 再预留生成长度（硬限制，超了 native 会截断）
            //   2) prompt 预算 PROMPT_TOKEN_BUDGET：真机实测 prefill 约 90ms/token
            //      （32 层 Vulkan、与 batch 大小无关，见 GPU层数实测对照.md 第六节），
            //      1400 token 约合 2 分钟等待。ctx 开到 8192 只是容量，不代表该用满：
            //      每轮都会全量重算，prompt 越长等得越久，所以这里按预算裁而不是按 ctx 裁。
            // 超预算时从最早的非 system 消息开始丢弃，只裁"发给模型的副本"，
            // UI 里的历史消息原样保留，用户不会看到消息凭空消失。
            val ctx = info?.contextSize ?: 0
            val reserve = params.maxTokens.coerceIn(0, 1024)
            val ctxLimit = if (ctx > 0) (ctx * 8 / 10) - reserve else Int.MAX_VALUE
            val limit = minOf(ctxLimit, PROMPT_TOKEN_BUDGET)
            if (limit > 0 && effective.size > 1) {
                var promptTokens = LlamaBridge.nativeCountTokens(h, prompt)
                var dropped = 0
                while (promptTokens > limit && effective.size > 1) {
                    val next = dropOldestMessage(effective)
                    if (next.size == effective.size) break
                    dropped += effective.size - next.size
                    effective = next
                    prompt = LlamaBridge.nativeBuildPrompt(
                        h,
                        effective.map { it.role }.toTypedArray(),
                        effective.map { it.content }.toTypedArray(),
                        true
                    )
                    promptTokens = LlamaBridge.nativeCountTokens(h, prompt)
                }
                if (dropped > 0) {
                    android.util.Log.i(
                        "LlamaJNI",
                        "context trim: prompt=$promptTokens <= limit=$limit " +
                            "(budget=$PROMPT_TOKEN_BUDGET, ctx_limit=$ctxLimit), " +
                            "dropped $dropped messages, kv_reset_on_next_prefill"
                    )
                    onNotice("上下文超出预算（约 2 分钟等待），已自动裁剪早期 $dropped 条历史")
                }
                if (promptTokens > limit) {
                    // 只剩最后一条仍然超预算：没有可裁的历史了，交给 native 的 ctx 保护兜底
                    android.util.Log.w(
                        "LlamaJNI",
                        "prompt still $promptTokens > limit=$limit after trimming, " +
                            "single message too long"
                    )
                }
            }

            generateLocked(h, prompt, params, onToken)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * 丢掉最早的一条（非 system）消息。
     *
     * 第一条是 system 时保留它，否则整轮对话会被剪成"只有一个问题"，预设提示词也就失效了。
     * 一次只丢一条，由调用方重新计数后再决定是否继续 —— 按 token 计而不是按条数计。
     */
    private fun dropOldestMessage(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size <= 1) return messages
        return if (messages.first().role == "system" && messages.size > 2) {
            messages.take(1) + messages.drop(2)
        } else {
            messages.drop(1)
        }
    }

    suspend fun complete(
        prompt: String,
        params: GenParams,
        onToken: (String) -> Unit = {}
    ): Result<GenResult> = generationMutex.withLock {
        val h = handle
        if (h == 0L) {
            return@withLock Result.failure(IllegalStateException("尚未加载模型"))
        }
        try {
            generateLocked(h, prompt, params, onToken)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * 加载后预热：产 1 个 token 的空推理。
     *
     * Vulkan 的第一轮推理要现编/加载 compute pipeline、分配 compute buffer、建图，
     * 这部分开销在真机上表现为"第一次回答明显比后面慢"（实测 32 层首轮 prefill
     * 2265ms，第二轮只有 245ms）。预热把它提前吃掉，用户感知到的第一条回复就快了。
     */
    suspend fun warmup(): Result<Long> = generationMutex.withLock {
        val h = handle
        if (h == 0L) {
            return@withLock Result.failure(IllegalStateException("尚未加载模型"))
        }
        try {
            val t0 = System.currentTimeMillis()
            val result = generateLocked(
                h,
                "hi",
                GenParams(maxTokens = 1, temperature = 0f, seed = 0),
                {}
            )
            val cost = System.currentTimeMillis() - t0
            result.fold(
                onSuccess = { Result.success(cost) },
                onFailure = { Result.failure(it) }
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun countTokens(text: String): Int = withContext(Dispatchers.Default) {
        val h = handle
        if (h == 0L) 0 else LlamaBridge.nativeCountTokens(h, text)
    }

    private suspend fun generateLocked(
        h: Long,
        prompt: String,
        params: GenParams,
        onToken: (String) -> Unit
    ): Result<GenResult> = withContext(Dispatchers.Default) {
        val builder = StringBuilder()
        var finishReason = 3
        var generated = 0
        val prefill = LlamaBridge.nativeCountTokens(h, prompt)

        val callback = object : LlamaBridge.TokenCallback {
            override fun onToken(bytes: ByteArray) {
                val text = String(bytes, Charsets.UTF_8)
                builder.append(text)
                onToken(text)
            }

            override fun onComplete(reason: Int, tokens: Int) {
                finishReason = reason
                generated = tokens
            }
        }

        val ret = LlamaBridge.nativeGenerate(
            handle = h,
            prompt = prompt,
            maxTokens = params.maxTokens,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            minP = params.minP,
            repeatPenalty = params.repeatPenalty,
            seed = params.seed,
            callback = callback
        )

        if (generated < 0) {
            generated = 0
        }

        // 耗时由 native 侧在同一个时钟里量，避免把 JNI/线程切换算进 prefill
        val timing = runCatching { LlamaBridge.nativeLastTiming(h) }.getOrNull()
        val prefillMs = timing?.getOrNull(0) ?: 0L
        val decodeMs = timing?.getOrNull(1) ?: 0L

        if (ret < 0) {
            if (ret == -6) {
                // GPU 队列提交超时（长时间高温推理或 prompt 过长时驱动挂起）。
                // context 已经不可用，继续用下去只会重复失败，直接卸载让用户重新加载。
                // 注意：这里已持有 generationMutex，不能再调 unload()（会死锁）
                android.util.Log.e("LlamaJNI", "Vulkan queue submit timeout, unloading model")
                releaseLocked()
                _state.value = EngineState.Idle
            }
            Result.failure(
                IllegalStateException(
                    when (ret) {
                        -1 -> "推理引擎不可用"
                        -2 -> "提示词分词失败"
                        -4 -> "推理失败，可能是上下文不足或内存不足"
                        -5 -> "已有生成任务正在进行"
                        -6 -> "GPU 提交超时（多为设备过热或提示词过长），模型已卸载，请冷却后重新加载"
                        else -> "推理失败 (code=$ret)"
                    }
                )
            )
        } else {
            Result.success(
                GenResult(
                    text = builder.toString(),
                    generatedTokens = generated,
                    finishReason = finishReason,
                    prefillTokens = prefill,
                    prefillMs = prefillMs,
                    decodeMs = decodeMs
                )
            )
        }
    }

    /**
     * 加载前内存预估。返回非 null 表示"不够，别加载"，字符串是要给用户看的原因。
     *
     * 估算口径（偏保守，目的只是拦住必然 OOM 的情况）：
     *   权重     = 文件大小 ×（mlock 1.5 / mmap 1.2）——mlock 会把整份权重钉在物理内存，
     *              另需一段连续空间给计算图张量；mmap 时权重可以按页换入，但要留计算缓冲
     *   KV cache = n_ctx × 40KB（按 4B 级模型 32 层 / GQA 8 KV 头 / fp16 的典型值）
     *   固定开销 = 128MB（计算图、tokenizer、采样器、日志缓冲等）
     */
    private fun memoryShortfall(file: File, config: LoadConfig): String? {
        val context = appContext ?: return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val mi = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(mi) }.getOrNull() ?: return null

        val fileBytes = file.length()
        val weightFactor = if (config.useMlock) 1.5 else 1.2
        val kvBytes = config.contextSize.toLong() * 40_960L
        val overhead = 128L * 1024 * 1024
        val need = (fileBytes * weightFactor).toLong() + kvBytes + overhead
        if (mi.availMem >= need) return null

        fun gb(v: Long) = String.format("%.1f", v / 1024.0 / 1024.0 / 1024.0)
        android.util.Log.w(
            "LlamaJNI",
            "memory precheck failed: avail=${gb(mi.availMem)}GB need=${gb(need)}GB " +
                "(file=${gb(fileBytes)}GB ctx=${config.contextSize} mlock=${config.useMlock})"
        )
        return "可用内存不足：需要约 ${gb(need)}GB，当前仅剩 ${gb(mi.availMem)}GB。" +
            "请换更小的量化版本（Q4_K_M → Q3_K_M / Q2_K），或把上下文调小、关闭 mlock 后重试。"
    }

    private data class GpuState(
        val offload: Boolean,
        val layers: Int,
        val requested: Int,
        val effective: Int,
        val devices: String
    )

    /**
     * 解析 native 侧的 GPU 实况 JSON。
     * devices 拼成 "GPU:Vulkan0 Mali-G720 | CPU:CPU" 这种短串直接给 UI 显示，
     * 只要 Vulkan 设备出现在列表里，就说明这个 .so 真的带了 Vulkan 后端。
     */
    private fun parseGpuInfo(json: String): GpuState {
        if (json.isBlank()) return GpuState(false, 0, 0, 0, "")
        return runCatching {
            val obj = JSONObject(json)
            val layers = obj.optInt("n_layer", 0)
            val arr = obj.optJSONArray("devices")
            val names = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val d = arr.optJSONObject(i) ?: continue
                        val type = d.optString("type", "?")
                        val name = d.optString("name", "")
                        val desc = d.optString("desc", "")
                        val label = listOf(name, desc).filter { it.isNotBlank() }.joinToString(" ")
                        add("$type:$label")
                    }
                }
            }
            GpuState(
                offload = obj.optInt("offload_supported", 0) == 1,
                layers = layers,
                requested = obj.optInt("requested", 0),
                effective = obj.optInt("effective", 0),
                devices = names.joinToString(" | ")
            )
        }.getOrElse { GpuState(false, 0, 0, 0, "") }
    }

    private fun releaseLocked() {
        synchronized(freeLock) {
            val h = handle
            handle = 0L
            info = null
            if (h != 0L) {
                LlamaBridge.nativeFreeModel(h)
            }
        }
    }
}
