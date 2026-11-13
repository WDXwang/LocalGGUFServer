package com.workbuddy.localmodel.ui

import com.workbuddy.localmodel.llm.ChatMessage
import com.workbuddy.localmodel.llm.GenParams
import com.workbuddy.localmodel.llm.LlamaEngine
import com.workbuddy.localmodel.util.ThermalMonitor
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

    /** 一次性提示（例如切换模型后对话被重置），显示后可关闭 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 上一轮生成的速度指标，供聊天页底部性能行显示 */
    private val _perf = MutableStateFlow<PerfSnapshot?>(null)
    val perf: StateFlow<PerfSnapshot?> = _perf.asStateFlow()

    data class PerfSnapshot(
        val tokensPerSecond: Double,
        val ttftMs: Long,
        val generated: Int,
        val thermalLevel: Int
    )

    /**
     * 对话世代号。切换模型时自增，让在途生成的结果失效，
     * 避免旧模型的回答被追加到已经清空的对话里。
     */
    @Volatile
    private var epoch = 0

    fun send(text: String, params: GenParams) {
        if (_generating.value) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        _messages.update { it + ChatMessage("user", trimmed) }
        _error.value = null
        _streaming.value = ""
        _generating.value = true

        // 温控降级：偏热时把单次生成长度砍半（少跑一会儿、少发热），
        // 严重过热时直接不启动，避免把 SoC 顶到关机保护
        val thermal = ThermalMonitor.status.value
        if (thermal >= 4) {
            _generating.value = false
            _error.value = "设备温度过高（${ThermalMonitor.label}），已暂停推理。请让手机降温后再试。"
            return
        }
        val effectiveParams = if (thermal >= 2) {
            params.copy(maxTokens = (params.maxTokens / 2).coerceAtLeast(64))
        } else {
            params
        }

        val snapshot = _messages.value
        val myEpoch = epoch
        scope.launch {
            val result = LlamaEngine.chat(
                snapshot,
                effectiveParams,
                onToken = { chunk ->
                    if (myEpoch == epoch) {
                        _streaming.update { it + chunk }
                    }
                },
                onNotice = { text ->
                    if (myEpoch == epoch) _notice.value = text
                }
            )
            if (myEpoch != epoch) {
                // 生成期间对话已被重置（例如切换了模型），丢弃这次结果
                _streaming.value = ""
                _generating.value = false
                return@launch
            }
            result.onSuccess { gen ->
                _messages.update { it + ChatMessage("assistant", gen.text) }
                if (gen.generatedTokens > 0) {
                    _perf.value = PerfSnapshot(
                        tokensPerSecond = gen.tokensPerSecond,
                        ttftMs = gen.ttftMs,
                        generated = gen.generatedTokens,
                        thermalLevel = ThermalMonitor.status.value
                    )
                }
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

    /**
     * 切换到另一个模型后重置对话。
     *
     * 旧对话的上下文对新模型没有意义：继续沿用会让新模型被上一个模型的对话带跑偏，
     * 看起来就像"换了模型答案还是一样"。
     * KV 缓存不在这里处理——旧 session 在 LlamaEngine.load 里已经释放，
     * 新 session 的 kv_tokens 是空的，等于自然作废。
     */
    fun resetAfterModelSwitch(modelName: String) {
        epoch++
        _messages.value = emptyList()
        _streaming.value = ""
        _error.value = null
        _notice.value = "模型已切换（$modelName），对话已重置"
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** 临时提示（例如"已复制到剪贴板"） */
    fun showNotice(text: String) {
        _notice.value = text
    }
}
