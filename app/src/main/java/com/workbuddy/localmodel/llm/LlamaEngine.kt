package com.workbuddy.localmodel.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局唯一的推理引擎。UI 聊天与 HTTP 服务共享同一个模型实例，
 * 通过 Mutex 串行化，避免 llama.cpp context 的并发竞争。
 */
object LlamaEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val generationMutex = Mutex()
    private val freeLock = Any()

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
                    val h = LlamaBridge.nativeLoadModel(
                        path = file.absolutePath,
                        nThreads = config.threads,
                        nCtx = config.contextSize,
                        nBatch = config.batchSize,
                        nGpuLayers = config.gpuLayers
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
                    synchronized(freeLock) { handle = h }
                    info = loaded
                    _state.value = EngineState.Ready(loaded)
                    Result.success(loaded)
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
        onToken: (String) -> Unit = {}
    ): Result<GenResult> = generationMutex.withLock {
        val h = handle
        if (h == 0L) {
            return@withLock Result.failure(IllegalStateException("尚未加载模型"))
        }
        try {
            // 预设的系统提示词作为第一条 system 消息参与 chat 模板；
            // 调用方自己已经带了 system 消息就不重复插入
            val effective = if (params.systemPrompt.isBlank() || messages.firstOrNull()?.role == "system") {
                messages
            } else {
                listOf(ChatMessage("system", params.systemPrompt)) + messages
            }
            val prompt = LlamaBridge.nativeBuildPrompt(
                h,
                effective.map { it.role }.toTypedArray(),
                effective.map { it.content }.toTypedArray(),
                true
            )
            generateLocked(h, prompt, params, onToken)
        } catch (t: Throwable) {
            Result.failure(t)
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

        if (ret < 0) {
            Result.failure(
                IllegalStateException(
                    when (ret) {
                        -1 -> "推理引擎不可用"
                        -2 -> "提示词分词失败"
                        -4 -> "推理失败，可能是上下文不足或内存不足"
                        -5 -> "已有生成任务正在进行"
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
                    prefillTokens = prefill
                )
            )
        }
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
