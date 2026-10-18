package com.qwen.voiceinput

import android.content.Context
import android.content.SharedPreferences
import com.qwen.voiceinput.asr.AsrController

/** 用户设置持久化（API Key / 模型 / 语言 / VAD / 自动填入） */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("qwen_voice_input", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "zh") ?: "zh"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var vadThreshold: Float
        get() = prefs.getFloat(KEY_VAD_THRESHOLD, 0.2f)
        set(value) = prefs.edit().putFloat(KEY_VAD_THRESHOLD, value).apply()

    var vadSilenceMs: Int
        get() = prefs.getInt(KEY_VAD_SILENCE, 800)
        set(value) = prefs.edit().putInt(KEY_VAD_SILENCE, value).apply()

    /** 悬浮球不透明度 0.3~1.0（同时作用于横向声波框） */
    var bubbleAlpha: Float
        get() = prefs.getFloat(KEY_BUBBLE_ALPHA, 0.95f)
        set(value) = prefs.edit().putFloat(KEY_BUBBLE_ALPHA, value.coerceIn(0.3f, 1f)).apply()

    /** 悬浮球颜色（ARGB int，默认品牌黑） */
    var bubbleColor: Int
        get() = prefs.getInt(KEY_BUBBLE_COLOR, DEFAULT_BUBBLE_COLOR)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_COLOR, value).apply()

    /** DashScope realtime 接入地址（高级选项） */
    var endpointBase: String
        get() = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    fun toAsrConfig(): AsrController.Config = AsrController.Config(
        apiKey = apiKey,
        model = model,
        endpointBase = endpointBase,
        language = language,
        vadThreshold = vadThreshold,
        vadSilenceMs = vadSilenceMs,
    )

    companion object {
        const val DEFAULT_MODEL = "qwen3-asr-flash-realtime"
        const val DEFAULT_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
        const val DEFAULT_BUBBLE_COLOR = 0xFF0B0D10.toInt()

        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VAD_THRESHOLD = "vad_threshold"
        private const val KEY_VAD_SILENCE = "vad_silence"
        private const val KEY_BUBBLE_ALPHA = "bubble_alpha"
        private const val KEY_BUBBLE_COLOR = "bubble_color"
        private const val KEY_ENDPOINT = "endpoint"
    }
}
