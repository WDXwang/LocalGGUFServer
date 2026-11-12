package com.workbuddy.localmodel.model

import android.content.Context
import java.io.File

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("local_model_settings", Context.MODE_PRIVATE)

    /**
     * 默认上下文 4096。
     *
     * 8192 在手机上 prefill 慢、KV 缓存占用近乎翻倍，长时间多轮对话更容易被系统回收；
     * 需要长上下文时在「加载参数」卡片里手动调大，并配合自动裁剪使用。
     */
    var contextSize: Int
        get() = prefs.getInt(KEY_CONTEXT, 4096)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    var threads: Int
        get() = prefs.getInt(KEY_THREADS, defaultThreads())
        set(value) = prefs.edit().putInt(KEY_THREADS, value).apply()

    /**
     * 默认 batch 256。
     *
     * n_batch 决定 prefill 一次提交多少 token，1024 会让 prefill 的内存峰值和瞬时功耗都很高，
     * 长时间推理时更容易触发 OOM 或温控杀进程；256 的 prefill 略慢但峰值平缓很多。
     */
    var batchSize: Int
        get() = prefs.getInt(KEY_BATCH, 256)
        set(value) = prefs.edit().putInt(KEY_BATCH, value).apply()

    /**
     * 权重 mlock 开关，默认开启。
     *
     * 手机长时间推理时系统会回收 mmap 的权重页，下次访问缺页会直接崩掉进程；
     * 开启后权重锁在物理内存，代价是加载稍慢、占用固定。内存不够时 native 侧自动回退 mmap 并打 WARN 日志。
     */
    var useMlock: Boolean
        get() = prefs.getBoolean(KEY_USE_MLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_MLOCK, value).apply()

    /**
     * 默认线程数取「最高频那一簇」的核数，不再直接用总核数。
     *
     * big.LITTLE 上把小核也拉进 llama.cpp 的同步屏障，会被最慢的核拖住；
     * 天玑 9300+ 这类 4×X4 + 4×A720 的机器上，4 线程（只用 X4）通常比 8 线程快 20%~40%。
     */
    private fun defaultThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val fallback = cores.coerceIn(2, 8)
        val freqKHz = (0 until cores).mapNotNull { cpu ->
            runCatching {
                val file = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                if (file.canRead()) file.readText().trim().toLongOrNull() else null
            }.getOrNull()
        }
        if (freqKHz.size != cores) return fallback
        val peak = freqKHz.maxOrNull() ?: return fallback
        // 频率达到峰值 80% 以上的核心视为大核
        val big = freqKHz.count { it >= peak * 0.8 }
        return big.coerceIn(2, 8)
    }


    var gpuLayers: Int
        get() = prefs.getInt(KEY_GPU_LAYERS, 0)
        set(value) = prefs.edit().putInt(KEY_GPU_LAYERS, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 512)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_TEMPERATURE, value).apply()

    var topP: Float
        get() = prefs.getFloat(KEY_TOP_P, 0.95f)
        set(value) = prefs.edit().putFloat(KEY_TOP_P, value).apply()

    var repeatPenalty: Float
        get() = prefs.getFloat(KEY_REPEAT_PENALTY, 1.1f)
        set(value) = prefs.edit().putFloat(KEY_REPEAT_PENALTY, value).apply()

    /** 当前生效的系统提示词，空串表示不带系统提示词 */
    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    /** 当前选中的预设名，用于界面回显；「自定义」或空串表示用户自己写的 */
    var presetName: String
        get() = prefs.getString(KEY_PRESET_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PRESET_NAME, value).apply()

    var lastModelPath: String?
        get() = prefs.getString(KEY_LAST_MODEL, null)
        set(value) = prefs.edit().putString(KEY_LAST_MODEL, value).apply()

    private companion object {
        const val KEY_CONTEXT = "context_size"
        const val KEY_THREADS = "threads"
        const val KEY_BATCH = "batch_size"
        const val KEY_USE_MLOCK = "use_mlock"
        const val KEY_GPU_LAYERS = "gpu_layers"
        const val KEY_PORT = "server_port"
        const val KEY_API_KEY = "api_key"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_REPEAT_PENALTY = "repeat_penalty"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_PRESET_NAME = "preset_name"
        const val KEY_LAST_MODEL = "last_model"
    }
}
