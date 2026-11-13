package com.workbuddy.localmodel.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 温控状态监听。
 *
 * 手机持续推理十几分钟，SoC 一定会撞到温控墙：此时继续按原参数跑，
 * 轻则速度掉到 1/3，重则被系统判定过热直接杀进程（用户看到的就是"无声退出"）。
 * 这里把 PowerManager 的热状态映射成 4 档，供调用方做分级降级：
 *
 *   NONE(0) / LIGHT(1) 正常
 *   MODERATE(2) 开始降 batch、收紧生成长度
 *   SEVERE(3) 再降 context 上限，并在 UI 明确提示
 *   CRITICAL(4) / EMERGENCY(5) / SHUTDOWN(6) 直接中断当前生成
 */
object ThermalMonitor {

    /** 0..6，直接对应 PowerManager.THERMAL_STATUS_* */
    private val _status = MutableStateFlow(0)
    val status: StateFlow<Int> = _status.asStateFlow()

    @Volatile
    private var registered = false

    fun attach(context: Context) {
        if (registered) return
        // 热状态 API 是 29+；低于 29 的机器只保留"正常"档，不降级（老机器也很少跑到 16GB 大模型）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        runCatching {
            // 用 SAM 转换直接传 lambda，避免不同 compileSdk 上内嵌接口名解析不一致
            pm.addThermalStatusListener(context.mainExecutor) { level: Int ->
                _status.value = level
            }
            registered = true
            _status.value = runCatching { pm.currentThermalStatus }.getOrDefault(0)
        }
    }

    val label: String
        get() = labelOf(status.value)

    fun labelOf(level: Int): String = when (level) {
        0 -> "正常"
        1 -> "轻微升温"
        2 -> "偏热"
        3 -> "过热"
        else -> "严重过热"
    }

    /** 热状态达到该档时，把单次生成长度砍半 */
    val shouldTrimTokens: Boolean get() = status.value >= 2

    /** 热状态达到该档时建议直接中断生成 */
    val shouldAbort: Boolean get() = status.value >= 4
}
