package com.workbuddy.localmodel.model

import android.content.Context
import java.io.File

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("local_model_settings", Context.MODE_PRIVATE)

    /**
     * 默认上下文 8192。
     *
     * 4096 时自动裁剪只留 2764 token（要扣掉 max_tokens 与余量），一问一答就把历史丢光，
     * 于是每轮都 kv_reset 全量重算 —— 真机上这正是 prefill 动辄几十秒的主因。
     * 8192 后可用预算约 7000 token，多轮对话能留住历史、KV 复用才真正生效；
     * 4B Q4_K_M 在 16GB 机型上 KV 缓存只多占几百 MB，代价可接受。
     * 小内存机型仍可在「加载参数」里调回 4096。
     */
    var contextSize: Int
        get() = prefs.getInt(KEY_CONTEXT, DEFAULT_CONTEXT)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    /**
     * 老版本留下的 4096 默认值升级到 8192。
     *
     * 只在用户没手动改过（值仍是旧默认 4096）时迁移一次，迁移后置位标记，
     * 之后用户自己改回 4096 不会再被覆盖。
     */
    fun migrateContextDefaultIfNeeded() {
        if (prefs.getBoolean(KEY_CONTEXT_MIGRATED, false)) return
        val edited = prefs.edit()
        if (prefs.getInt(KEY_CONTEXT, DEFAULT_CONTEXT) == LEGACY_DEFAULT_CONTEXT) {
            edited.putInt(KEY_CONTEXT, DEFAULT_CONTEXT)
        }
        edited.putBoolean(KEY_CONTEXT_MIGRATED, true).apply()
    }

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

    /**
     * 上一次加载的模型的层数。
     *
     * 用来在「模型还没加载」的时候给出 GPU 层数推荐：真机上实测（Qwen3.5-4B / Mali-G720）
     * 层数越多越快，全量卸载（层数=模型层数）时 prefill 从 9.4s 降到 2.3s、decode 从 2.7 提到 6.0 t/s，
     * 所以推荐值就是模型的全部层数，而不是保守的部分卸载。
     */
    var lastLayerCount: Int
        get() = prefs.getInt(KEY_LAST_LAYER_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_LAYER_COUNT, value).apply()

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
        const val DEFAULT_CONTEXT = 8192
        const val LEGACY_DEFAULT_CONTEXT = 4096
        const val KEY_CONTEXT_MIGRATED = "context_default_migrated"
        const val KEY_CONTEXT = "context_size"
        const val KEY_THREADS = "threads"
        const val KEY_BATCH = "batch_size"
        const val KEY_USE_MLOCK = "use_mlock"
        const val KEY_GPU_LAYERS = "gpu_layers"
        const val KEY_LAST_LAYER_COUNT = "last_layer_count"
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
