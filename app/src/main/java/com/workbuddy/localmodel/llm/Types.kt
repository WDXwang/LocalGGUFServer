package com.workbuddy.localmodel.llm

data class ChatMessage(
    val role: String,
    val content: String
)

data class LoadConfig(
    val contextSize: Int = 4096,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
    val batchSize: Int = 512,
    val gpuLayers: Int = 0
)

data class ModelInfo(
    val name: String,
    val path: String,
    val desc: String,
    val parameters: Long,
    val sizeBytes: Long,
    val contextSize: Int,
    val hasChatTemplate: Boolean
)

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
    val prefillTokens: Int = 0
) {
    val cancelled: Boolean get() = finishReason == 2
    val hitLengthLimit: Boolean get() = finishReason == 1
}

sealed class EngineState {
    data object Idle : EngineState()
    data class Loading(val name: String) : EngineState()
    data class Ready(val info: ModelInfo) : EngineState()
    data class Failed(val message: String) : EngineState()
}
