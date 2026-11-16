package com.workbuddy.localmodel.llm

data class ChatMessage(
    val role: String,
    val content: String
)

data class LoadConfig(
    val contextSize: Int = 8192,
    /** decode 线程数，取大核数（同步屏障被小核拖住） */
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
    /** prefill 线程数；0 = 自动取全部核心（最多 8），矩阵乘能吃满核心 */
    val threadsBatch: Int = 0,
    val batchSize: Int = 256,
    /**
     * GPU 卸载层数。-1 表示"全部层"，0 表示纯 CPU。
     *
     * 默认 -1：本工程的核心卖点就是 Vulkan 加速，装了带 Vulkan 的包却默认 0 层
     * 等于白装（实测 32 层全量卸载比纯 CPU 快 2~4 倍）。没有 Vulkan 后端时
     * llama.cpp 会自己忽略 -1，所以这个默认值在纯 CPU 构建上也是安全的。
     */
    val gpuLayers: Int = -1,
    /** 把权重锁定在物理内存，避免长时间运行被系统回收换出后缺页崩溃 */
    val useMlock: Boolean = true,
    /** KV/FA 组合偏好：auto（默认，FA+q8_0）/ q8_fa / q8_auto / f16_auto */
    val kvMode: String = "auto"
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
    val gpuDevices: String = "",
    /** 实际生效的 attention 实现：enabled / auto / disabled（回退后可能与首选不同） */
    val flashAttn: String = "",
    /** 实际生效的 KV cache 类型：q8_0 / f16 */
    val kvType: String = "",
    /** decode 线程数（生效值） */
    val decodeThreads: Int = 0,
    /** prefill 线程数（生效值） */
    val prefillThreads: Int = 0
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
    val decodeMs: Long = 0,
    /** 本次 prompt 里已命中 KV 缓存的 token 数 */
    val kvReuseTokens: Int = 0
) {

    /** KV 复用命中率（0..1），阶段一的核心验收指标 */
    val kvReuseRatio: Double
        get() = if (prefillTokens > 0) kvReuseTokens.toDouble() / prefillTokens else 0.0
    val cancelled: Boolean get() = finishReason == 2
    val hitLengthLimit: Boolean get() = finishReason == 1

    /** decode 阶段的生成速度 */
    val tokensPerSecond: Double
        get() = if (decodeMs > 0) generatedTokens.toDouble() * 1000.0 / decodeMs else 0.0

    /** 首 token 延迟（prefill 耗时即为主要部分） */
    val ttftMs: Long get() = prefillMs
}

/** 引擎忙：并发生成请求被拒（HTTP 侧映射 429 + Retry-After） */
class EngineBusyException(message: String = "引擎正被另一个请求占用，请稍后重试") :
    IllegalStateException(message)

/** 热降级拒绝：温度过高，本档直接不出结果（HTTP 侧映射 503 + Retry-After） */
class EngineThermalException(message: String) : IllegalStateException(message)

/** 最近一次推理的规模指标，供 /metrics 与 /health 暴露 */
data class EngineStats(
    val promptTokens: Int = 0,
    val kvReuseTokens: Int = 0,
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
    val generatedTokens: Int = 0,
    val thermalLevel: Int = 0,
    val decodeThreads: Int = 0,
    val prefillThreads: Int = 0
) {
    val kvReuseRatio: Double
        get() = if (promptTokens > 0) kvReuseTokens.toDouble() / promptTokens else 0.0
}

sealed class EngineState {
    data object Idle : EngineState()
    data class Loading(val name: String) : EngineState()
    data class Ready(val info: ModelInfo) : EngineState()
    data class Failed(val message: String) : EngineState()
}
