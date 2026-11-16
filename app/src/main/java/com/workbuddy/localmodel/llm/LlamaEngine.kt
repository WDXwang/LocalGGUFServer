package com.workbuddy.localmodel.llm

import android.app.ActivityManager
import android.content.Context
import com.workbuddy.localmodel.model.SettingsStore
import com.workbuddy.localmodel.util.ThermalMonitor
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
     * prompt token 预算：**动态**，取 ctx 的 90% 再扣掉本次生成长度。
     *
     * 之前这里是一个写死的 700，理由是"每轮都全量重算，prompt 越长首字越久"。
     * 但那个前提在日志里被证伪了：`context trim: ... dropped 13 of 14 messages, kv_reset_on_next_prefill`
     * —— 预算 700 让聊天历史几乎每轮都被裁到只剩一条，prompt 头部一变，
     * 从位置 0 求公共前缀的 KV 复用就永远命中 0，于是"裁剪"和"复用"互相废掉，
     * 每轮真的变成全量 prefill（实测 prefill=49993ms）。
     *
     * 现在的口径：
     *   limit = ctx * 9 / 10 - reserve，reserve = maxTokens（夹在 0..1024）
     * ctx=8192 时约 6800 token，几十轮对话都留得住，KV 复用才有意义；
     * 只有真的超了才裁，且裁完会打 `context trim` 日志，可观测。
     *
     * 注意这个上限只约束"发给模型的副本"，UI 里的历史消息不受影响。
     */
    private fun promptLimit(ctx: Int, maxTokens: Int): Int {
        if (ctx <= 0) return Int.MAX_VALUE
        val reserve = maxTokens.coerceIn(0, 1024)
        return (ctx * 9 / 10 - reserve).coerceAtLeast(256)
    }

    /** 每条消息在 chat 模板里的固定开销（role 标记、分隔符），经验值 5 token */
    private const val MESSAGE_TEMPLATE_OVERHEAD = 5

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val generationMutex = Mutex()
    private val freeLock = Any()

    /** 并发闸门：HTTP 侧靠它快速失败（429），UI 侧靠它抢占 */
    private val inflight = java.util.concurrent.atomic.AtomicInteger(0)

    /** 正在生成的请求 id 与来源，供"按 id 取消"与 UI 抢占使用 */
    @Volatile
    private var activeRequestId: String? = null

    @Volatile
    private var activeOwner: String = ""

    /** 最近一次推理的规模指标，/health 直接读它 */
    @Volatile
    var lastStats: EngineStats = EngineStats()
        private set

    val isBusy: Boolean get() = inflight.get() > 0

    /** 应用 Context，用于加载前查询可用内存 */
    @Volatile
    private var appContext: Context? = null

    /** 设置项，读取"思考模式"开关；UI 聊天和 HTTP 接口都走这里，保持同一份策略 */
    @Volatile
    private var settings: SettingsStore? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        settings = SettingsStore(context.applicationContext)
    }

    /**
     * 关闭推理（think）时，在 assistant 开头预填一个空的思考块。
     *
     * Qwen3/Qwen3.5 系列在 assistant 回复里默认先输出 `<think>...<｜end▁of▁thinking｜>`，
     * 这段既进不了正文，又要实打实生成一遍 token —— 真机 1~4 t/s 的速度下，
     * 一段和正文一样长的思考内容等于凭空多等一倍时间。
     *
     * 直接把 `<think>\n\n<｜end▁of▁thinking｜>\n` 塞进 prompt 的 assistant 位置，模型会接着写正文，
     * 不需要它自己再走一遍思考。只在模型名命中 qwen3 家族时启用，
     * 其它模型没有这个约定，硬塞会污染上下文。
     */
    private const val THINK_PREFILL = "<think>\n\n</think>\n"

    private fun applyThinkPrefill(prompt: String): String {
        if (settings?.thinkMode ?: true) return prompt
        val name = info?.name?.lowercase() ?: return prompt
        if (!name.contains("qwen3")) return prompt
        android.util.Log.i("LlamaJNI", "think prefill: 已关闭思考，跳过 <think> 推理段")
        return prompt + THINK_PREFILL
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

                    val nThreadsBatch = resolvePrefillThreads(config)
                    val kvMode = resolveKvMode(config)
                    // GPU 层数回退链：-1（全量）→ 一半 → 0（纯 CPU）。
                    // 部分机型/驱动在全量卸载时会加载失败或直接崩，逐级退到能跑为止，
                    // 并把实际生效值写回设置，下次就不用再退一遍。
                    val candidates = buildList {
                        add(config.gpuLayers)
                        if (config.gpuLayers < 0) add(16)
                        add(0)
                    }.distinct()
                    var h = 0L
                    var usedLayers = config.gpuLayers
                    for (layers in candidates) {
                        h = LlamaBridge.nativeLoadModel(
                            path = file.absolutePath,
                            nThreads = config.threads,
                            nThreadsBatch = nThreadsBatch,
                            nCtx = config.contextSize,
                            nBatch = config.batchSize,
                            nGpuLayers = layers,
                            nMlock = config.useMlock,
                            kvMode = kvMode
                        )
                        if (h != 0L) {
                            usedLayers = layers
                            if (layers != config.gpuLayers) {
                                android.util.Log.w(
                                    "LlamaJNI",
                                    "gpu layer fallback: requested=${config.gpuLayers} effective=$layers"
                                )
                                settings?.gpuLayers = layers
                            }
                            break
                        }
                        android.util.Log.w("LlamaJNI", "load failed with gpu_layers=$layers, retrying lower")
                    }
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
                        gpuLayersRequested = usedLayers,
                        gpuLayersEffective = gpu.effective,
                        layerCount = gpu.layers,
                        gpuOffloadSupported = gpu.offload,
                        gpuDevices = gpu.devices,
                        flashAttn = gpu.fa,
                        kvType = gpu.kvType,
                        decodeThreads = gpu.threads,
                        prefillThreads = gpu.threadsBatch
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

    /** 中断当前生成（可从任意线程调用，等价于取消"正在跑的那个请求"） */
    fun cancel() {
        synchronized(freeLock) {
            val h = handle
            if (h != 0L) {
                LlamaBridge.nativeStop(h)
            }
        }
    }

    /**
     * 按 requestId 取消。
     *
     * 旧实现是无条件 nativeStop：并发场景下 A 客户端断开会把 B 正在跑的生成一起杀掉
     * （native 里 stop 是 context 级的，没有请求维度）。HTTP 侧现在给每个请求带 id，
     * 只有 id 匹配当前在跑的那个才真的停。
     */
    fun cancelRequest(requestId: String) {
        if (requestId.isBlank()) return
        if (activeRequestId != requestId) return
        cancel()
    }

    /**
     * 并发准入。
     *
     * HTTP 侧只放 1 个生成进来，第二个立刻返回失败（映射成 429），
     * 而不是像以前那样在 Mutex 上静默排队 —— 排队在真机上是灾难：
     * 一个请求可能跑 2 分钟，客户端早就超时了，服务端还在替它烧电。
     * UI 侧优先：允许叠加，并顺手把正在跑的 API 请求掐掉。
     */
    private fun admit(requestId: String?, owner: String) {
        if (owner == "api") {
            if (!inflight.compareAndSet(0, 1)) {
                throw EngineBusyException("已有生成任务在进行，请稍后重试（当前模型单并发）")
            }
            return
        }
        inflight.incrementAndGet()
        if (activeOwner == "api") {
            activeRequestId?.let { cancelRequest(it) }
        }
    }

    /** 热降级是否已生效（滞回）：≥2 降级、≤1 才恢复，避免在阈值上反复横跳 */
    @Volatile
    private var thermalLatched = false

    /**
     * 温控策略下沉到引擎。
     *
     * 以前这套判断只在 ChatStore 里（UI 专用），HTTP API 完全不受约束 ——
     * 客户端可以在 88°C 的机器上连续打 512 token 的请求，直到驱动超时（-6）。
     * 现在两条入口都过这里：
     *   level >= 4  直接拒绝（映射 503 + Retry-After）
     *   level >= 3  maxTokens 压到 128
     *   level >= 2  生成长度砍半（上限 256）
     * 并带滞回：降到 2 档后即使瞬回 3 档以下也保持降级，直到回落到 ≤1 才恢复。
     */
    private fun applyThermal(params: GenParams): GenParams {
        val level = ThermalMonitor.status.value
        if (level >= 4) {
            throw EngineThermalException("设备过热（热状态 $level），已暂停生成本次请求，请冷却后再试")
        }
        if (level >= 2) thermalLatched = true else if (level <= 1) thermalLatched = false
        if (!thermalLatched) return params
        val cap = if (level >= 3) 128 else 256
        val trimmed = minOf(params.maxTokens, cap, (params.maxTokens / 2).coerceAtLeast(64))
        if (trimmed == params.maxTokens) return params
        android.util.Log.i(
            "LlamaJNI",
            "thermal degrade: level=$level max_tokens ${params.maxTokens} -> $trimmed"
        )
        return params.copy(maxTokens = trimmed)
    }

    /** 热降级时同步降线程：8 线程 prefill 的瞬时电流明显高于 4 线程 */
    private fun applyThermalThreads(h: Long) {
        val level = ThermalMonitor.status.value
        if (level < 2) return
        val cur = info ?: return
        val decode = (cur.decodeThreads / 2).coerceIn(2, 8)
        val batch = (cur.prefillThreads / 2).coerceIn(2, 8)
        runCatching { LlamaBridge.nativeSetThreads(h, decode, batch) }
            .onSuccess { android.util.Log.i("LlamaJNI", "thermal threads: decode=$decode batch=$batch") }
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        params: GenParams,
        requestId: String? = null,
        owner: String = "ui",
        onToken: (String) -> Unit = {},
        onNotice: (String) -> Unit = {}
    ): Result<GenResult> {
        try {
            admit(requestId, owner)
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        try {
            return generationMutex.withLock {
                val h = handle
                if (h == 0L) {
                    return@withLock Result.failure(IllegalStateException("尚未加载模型"))
                }
                activeRequestId = requestId
                activeOwner = owner
                try {
                    // 预设的系统提示词作为第一条 system 消息参与 chat 模板；
                    // 调用方自己已经带了 system 消息就不重复插入
                    var effective = if (params.systemPrompt.isBlank() || messages.firstOrNull()?.role == "system") {
                        messages
                    } else {
                        listOf(ChatMessage("system", params.systemPrompt)) + messages
                    }

                    // 上下文保护：limit = ctx * 9 / 10 - reserve（见 promptLimit 注释）。
                    // 只有真的超了才裁，且裁的是"发给模型的副本"，UI 历史原样保留。
                    // 裁一次就要重建 prompt，所以先按单条消息的 token 数一次算出丢几条，
                    // 避免旧实现那种"丢一条→重建→重新计数"的 O(n²) native 调用。
                    var prompt = applyThinkPrefill(
                        LlamaBridge.nativeBuildPrompt(
                            h,
                            effective.map { it.role }.toTypedArray(),
                            effective.map { it.content }.toTypedArray(),
                            true
                        )
                    )
                    val ctx = info?.contextSize ?: 0
                    val limit = promptLimit(ctx, params.maxTokens)
                    var promptTokens = LlamaBridge.nativeCountTokens(h, prompt)
                    if (limit > 0 && promptTokens > limit && effective.size > 1) {
                        val perMessage = effective.map {
                            LlamaBridge.nativeCountTokens(h, it.content) + MESSAGE_TEMPLATE_OVERHEAD
                        }
                        val keepSystem = effective.first().role == "system"
                        var idx = if (keepSystem) 1 else 0
                        var total = promptTokens
                        var dropped = 0
                        while (total > limit && idx < effective.size - 1) {
                            total -= perMessage[idx]
                            idx++
                            dropped++
                        }
                        if (dropped > 0) {
                            effective = buildList {
                                if (keepSystem) add(effective.first())
                                addAll(effective.subList(idx, effective.size))
                            }
                            prompt = applyThinkPrefill(
                                LlamaBridge.nativeBuildPrompt(
                                    h,
                                    effective.map { it.role }.toTypedArray(),
                                    effective.map { it.content }.toTypedArray(),
                                    true
                                )
                            )
                            promptTokens = LlamaBridge.nativeCountTokens(h, prompt)
                            android.util.Log.i(
                                "LlamaJNI",
                                "context trim: prompt=$promptTokens <= limit=$limit " +
                                    "(ctx=$ctx, reserve=${params.maxTokens}), " +
                                    "dropped $dropped of ${dropped + effective.size} messages, " +
                                    "kv_reset_on_next_prefill"
                            )
                            onNotice("上下文超出模型窗口的 90%，已自动裁剪早期 $dropped 条历史")
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

                    val effectiveParams = applyThermal(params)
                    if (thermalLatched) applyThermalThreads(h)
                    generateLocked(h, prompt, effectiveParams, onToken)
                } catch (t: Throwable) {
                    Result.failure(t)
                } finally {
                    activeRequestId = null
                    activeOwner = ""
                }
            }
        } finally {
            inflight.decrementAndGet()
        }
    }

    suspend fun complete(
        prompt: String,
        params: GenParams,
        requestId: String? = null,
        owner: String = "ui",
        onToken: (String) -> Unit = {}
    ): Result<GenResult> {
        try {
            admit(requestId, owner)
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        try {
            return generationMutex.withLock {
                val h = handle
                if (h == 0L) {
                    return@withLock Result.failure(IllegalStateException("尚未加载模型"))
                }
                activeRequestId = requestId
                activeOwner = owner
                try {
                    val effectiveParams = applyThermal(params)
                    if (thermalLatched) applyThermalThreads(h)
                    generateLocked(h, prompt, effectiveParams, onToken)
                } catch (t: Throwable) {
                    Result.failure(t)
                } finally {
                    activeRequestId = null
                    activeOwner = ""
                }
            }
        } finally {
            inflight.decrementAndGet()
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
        // 真正提交给模型的 prompt 长度与 KV 命中量（由 native 侧在 tokenize 后记录，
        // 比在 Kotlin 侧重新 countTokens 更准 —— 旧实现用的正是裁剪前的计数，stealth 虚高）
        val promptTokens = timing?.getOrNull(3)?.toInt() ?: prefill
        val kvReuse = timing?.getOrNull(4)?.toInt() ?: 0
        lastStats = EngineStats(
            promptTokens = promptTokens,
            kvReuseTokens = kvReuse,
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            generatedTokens = generated,
            thermalLevel = ThermalMonitor.status.value,
            decodeThreads = info?.decodeThreads ?: 0,
            prefillThreads = info?.prefillThreads ?: 0
        )

        if (ret < 0) {
            if (ret == -6) {
                // GPU 队列提交超时（长时间高温推理或 prompt 过长时驱动挂起）。
                // context 已经不可用，继续用下去只会重复失败，直接卸载让用户重新加载。
                // 注意：这里已持有 generationMutex，不能再调 unload()（会死锁）
                android.util.Log.e("LlamaJNI", "Vulkan queue submit timeout, unloading model")
                releaseLocked()
                _state.value = EngineState.Idle
            }
            // -5（native 的 try_lock 失败）统一当成"忙"，HTTP 侧据此回 429 而不是 500
            val error: Throwable = if (ret == -5) {
                EngineBusyException("已有生成任务在进行，请稍后重试（当前模型单并发）")
            } else {
                IllegalStateException(
                    when (ret) {
                        -1 -> "推理引擎不可用"
                        -2 -> "提示词分词失败"
                        -4 -> "推理失败，可能是上下文不足或内存不足"
                        -6 -> "GPU 提交超时（多为设备过热或提示词过长），模型已卸载，请冷却后重新加载"
                        else -> "推理失败 (code=$ret)"
                    }
                )
            }
            Result.failure(error)
        } else {
            Result.success(
                GenResult(
                    text = builder.toString(),
                    generatedTokens = generated,
                    finishReason = finishReason,
                    prefillTokens = promptTokens,
                    kvReuseTokens = kvReuse,
                    prefillMs = prefillMs,
                    decodeMs = decodeMs
                )
            )
        }
    }

    /**
     * 加载前内存预估。返回非 null 表示"不够，别加载"，字符串是要给用户看的原因。
     *
     * 估算口径（只需要拦住必然 OOM 的情况，宁可略保守，但不能把能跑的模型拦下来）：
     *   权重 = 文件大小 —— mlock 只是把这些页钉住不换出，并不需要额外一份拷贝；
     *          之前按 1.5 倍估会凭空多算 50%，2GB 的 3B 模型因此被误判成"需要 3.2GB"直接拒载。
     *   KV   = n_ctx × 40KB（按 4B 级模型 32 层 / GQA 8 KV 头 / fp16 的典型值；
     *          q8_0 KV 实际只用一半，所以这是上界）
     *   固定 = 256MB（Vulkan compute buffer 实测约 509MB 但可复用/可换出，加上 tokenizer、
     *          采样器、日志缓冲取 256MB 更贴近真实增量）
     */
    private fun memoryShortfall(file: File, config: LoadConfig): String? {
        val context = appContext ?: return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val mi = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(mi) }.getOrNull() ?: return null

        val fileBytes = file.length()
        val kvBytes = config.contextSize.toLong() * 40_960L
        val overhead = 256L * 1024 * 1024
        val need = fileBytes + kvBytes + overhead
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
        val devices: String,
        val fa: String = "",
        val kvType: String = "",
        val threads: Int = 0,
        val threadsBatch: Int = 0
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
                devices = names.joinToString(" | "),
                fa = obj.optString("fa", ""),
                kvType = obj.optString("kv_type", ""),
                threads = obj.optInt("threads", 0),
                threadsBatch = obj.optInt("threads_batch", 0)
            )
        }.getOrElse { GpuState(false, 0, 0, 0, "") }
    }

    /**
     * prefill 线程数：默认吃满所有核心（最多 8）。
     *
     * prefill 是 big.LITTLE 上少数真能吃满小核的阶段（矩阵乘，没有严格的同步屏障拖累），
     * 而 decode 逐 token 时同步屏障会被最慢的核拖住，所以两者用不同值。
     */
    private fun resolvePrefillThreads(config: LoadConfig): Int {
        if (config.threadsBatch > 0) return config.threadsBatch.coerceIn(2, 8)
        val cores = Runtime.getRuntime().availableProcessors()
        return cores.coerceIn(2, 8)
    }

    /**
     * KV/FA 组合偏好：优先用加载参数里的设置，其次是外部文件
     * `Android/data/<pkg>/files/kv.conf`（给不改 UI 也能 A/B 的场合用）。
     * 取值：auto（默认，FA+q8_0 优先）/ q8_fa / q8_auto / f16_auto。
     */
    private fun resolveKvMode(config: LoadConfig): String {
        val fromConfig = config.kvMode.trim()
        if (fromConfig.isNotEmpty() && fromConfig != "auto") return fromConfig
        val dir = runCatching { appContext?.getExternalFilesDir(null) }.getOrNull() ?: return ""
        val file = File(dir, "kv.conf")
        if (!file.exists()) return ""
        return runCatching { file.readText().trim() }.getOrDefault("")
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
