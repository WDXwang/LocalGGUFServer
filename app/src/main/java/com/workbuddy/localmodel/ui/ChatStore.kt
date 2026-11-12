package com.workbuddy.localmodel.ui

import com.workbuddy.localmodel.llm.ChatMessage
import com.workbuddy.localmodel.llm.GenParams
import com.workbuddy.localmodel.llm.LlamaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 聊天状态放在单例里，切换页面不会中断生成或丢失对话。
 */
object ChatStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun send(text: String, params: GenParams) {
        if (_generating.value) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        _messages.update { it + ChatMessage("user", trimmed) }
        _error.value = null
        _streaming.value = ""
        _generating.value = true

        val snapshot = _messages.value
        scope.launch {
            val result = LlamaEngine.chat(snapshot, params) { chunk ->
                _streaming.update { it + chunk }
            }
            result.onSuccess { gen ->
                _messages.update { it + ChatMessage("assistant", gen.text) }
            }.onFailure { throwable ->
                _error.value = throwable.message ?: "推理失败"
            }
            _streaming.value = ""
            _generating.value = false
        }
    }

    fun cancel() {
        LlamaEngine.cancel()
    }

    fun clearError() {
        _error.value = null
    }

    fun clear() {
        if (!_generating.value) {
            _messages.value = emptyList()
            _error.value = null
        }
    }
}
