package com.workbuddy.localmodel.model

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("local_model_settings", Context.MODE_PRIVATE)

    var contextSize: Int
        get() = prefs.getInt(KEY_CONTEXT, 4096)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    var threads: Int
        get() = prefs.getInt(
            KEY_THREADS,
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        )
        set(value) = prefs.edit().putInt(KEY_THREADS, value).apply()

    var batchSize: Int
        get() = prefs.getInt(KEY_BATCH, 512)
        set(value) = prefs.edit().putInt(KEY_BATCH, value).apply()

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

    var lastModelPath: String?
        get() = prefs.getString(KEY_LAST_MODEL, null)
        set(value) = prefs.edit().putString(KEY_LAST_MODEL, value).apply()

    private companion object {
        const val KEY_CONTEXT = "context_size"
        const val KEY_THREADS = "threads"
        const val KEY_BATCH = "batch_size"
        const val KEY_GPU_LAYERS = "gpu_layers"
        const val KEY_PORT = "server_port"
        const val KEY_API_KEY = "api_key"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_LAST_MODEL = "last_model"
    }
}
