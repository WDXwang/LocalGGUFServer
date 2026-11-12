package com.workbuddy.localmodel.llm

/**
 * llama.cpp 的 JNI 绑定。所有方法均为阻塞调用，请在 IO/Default 线程执行。
 */
object LlamaBridge {

    init {
        System.loadLibrary("localmodel")
        nativeBackendInit()
    }

    interface TokenCallback {
        /** 一段完整的 UTF-8 文本（可能是单个 token 或若干 token 的组合） */
        fun onToken(bytes: ByteArray)

        /** finishReason: 0=自然结束 1=达到上限 2=已取消 3=出错 */
        fun onComplete(finishReason: Int, generatedTokens: Int)
    }

    external fun nativeBackendInit()

    external fun nativeLoadModel(
        path: String,
        nThreads: Int,
        nCtx: Int,
        nBatch: Int,
        nGpuLayers: Int,
        nMlock: Boolean
    ): Long

    external fun nativeFreeModel(handle: Long)

    external fun nativeModelDesc(handle: Long): String

    external fun nativeModelParams(handle: Long): Long

    external fun nativeModelSize(handle: Long): Long

    external fun nativeContextSize(handle: Long): Int

    external fun nativeHasChatTemplate(handle: Long): Boolean

    external fun nativeBuildPrompt(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean
    ): String

    external fun nativeCountTokens(handle: Long, text: String): Int

    external fun nativeStop(handle: Long)

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        seed: Int,
        callback: TokenCallback
    ): Int
}
