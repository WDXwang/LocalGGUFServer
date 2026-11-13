package com.workbuddy.localmodel.llm

data class ChatMessage(
    val role: String,
    val content: String
)

data class LoadConfig(
    val contextSize: Int = 4096,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
    val batchSize: Int = 256,
    val gpuLayers: Int = 0,
    /** 把权重锁定在物理内存，避免长时间运行被系统回收换出后缺页崩溃 */
    val useMlock: Boolean = true
)

data class ModelInfo(
    val name: String,
    val path: String,
    val desc: String,
    val parameters: Long,
    val sizeBytes: Long,
    val contextSize: Int,
    val hasChatTemplate: Boolean,
    /** 请求的 GPU 卸载层数（用户在加载参数里填的） */
    val gpuLayersRequested: Int = 0,
    /** 实际生效的层数上限（请求值按模型总层数折算后的结果） */
    val gpuLayersEffective: Int = 0,
    /** 模型总层数 */
    val layerCount: Int = 0,
    /** 本次构建是否带 GPU 卸载能力（Vulkan 后端真的注册上了才是 true） */
    val gpuOffloadSupported: Boolean = false,
    /** 注册到的设备名，例如 "GPU:Vulkan0 Mali-G720 Immortalis MC12 | CPU:..." */
    val gpuDevices: String = ""
) {
    /** 是否真的把层放到了 GPU 上 */
    val gpuActive: Boolean get() = gpuOffloadSupported && gpuLayersEffective > 0
}

data class GenParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val seed: Int = -1,
    /** 非空时作为 system 消息插在对话最前面，走模型自带的 chat 模板 */
    val systemPrompt: String = ""
)

data class GenResult(
    val text: String,
    val generatedTokens: Int,
    val finishReason: Int,
    val prefillTokens: Int = 0,
    /** prompt（含 KV 复用后的新增部分）处理耗时，毫秒 */
    val prefillMs: Long = 0,
    /** 逐 token decode 阶段耗时，毫秒，不含首 token 等待 */
    val decodeMs: Long = 0
) {
    val cancelled: Boolean get() = finishReason == 2
    val hitLengthLimit: Boolean get() = finishReason == 1

    /** decode 阶段的生成速度 */
    val tokensPerSecond: Double
        get() = if (decodeMs > 0) generatedTokens.toDouble() * 1000.0 / decodeMs else 0.0

    /** 首 token 延迟（prefill 耗时即为主要部分） */
    val ttftMs: Long get() = prefillMs
}

sealed class EngineState {
    data object Idle : EngineState()
    data class Loading(val name: String) : EngineState()
    data class Ready(val info: ModelInfo) : EngineState()
    data class Failed(val message: String) : EngineState()
}
