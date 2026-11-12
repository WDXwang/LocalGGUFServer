package com.workbuddy.localmodel.server

import com.workbuddy.localmodel.llm.LlamaEngine
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServerController {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var server: ApiServer? = null

    @Synchronized
    fun start(
        port: Int,
        apiKey: String,
        defaultMaxTokens: Int,
        defaultTemperature: Float
    ): Result<Unit> {
        server?.let { return Result.success(Unit) }

        return try {
            val instance = ApiServer(port, apiKey, defaultMaxTokens, defaultTemperature)
            instance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = instance
            _error.value = null
            _running.value = true
            Result.success(Unit)
        } catch (t: Throwable) {
            val message = "端口 $port 启动失败：${t.message ?: t.javaClass.simpleName}"
            _error.value = message
            _running.value = false
            Result.failure(IllegalStateException(message, t))
        }
    }

    @Synchronized
    fun stop() {
        LlamaEngine.cancel()
        server?.stop()
        server = null
        _running.value = false
    }

    fun clearError() {
        _error.value = null
    }
}
