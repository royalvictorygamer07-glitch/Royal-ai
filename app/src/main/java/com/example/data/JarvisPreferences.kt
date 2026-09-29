package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig

class JarvisPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() {
            val key = prefs.getString(KEY_API_KEY, "") ?: ""
            if (key.isNotBlank()) return key
            return try {
                val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
                (field.get(null) as? String)?.takeIf { it != "MY_GEMINI_API_KEY" } ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() {
            val m = prefs.getString(KEY_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash"
            return if (m.contains("3.5") || m.isBlank()) "gemini-2.5-flash" else m
        }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var voice: String
        get() = prefs.getString(KEY_VOICE, "Natural Girl Voice") ?: "Natural Girl Voice"
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var voiceSpeed: Float
        get() = prefs.getFloat(KEY_VOICE_SPEED, 1.04f)
        set(value) = prefs.edit().putFloat(KEY_VOICE_SPEED, value).apply()

    var voicePitch: Float
        get() = prefs.getFloat(KEY_VOICE_PITCH, 1.22f)
        set(value) = prefs.edit().putFloat(KEY_VOICE_PITCH, value).apply()

    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, "Assistant Mode") ?: "Assistant Mode"
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    val isGfMode: Boolean
        get() = personality.equals("Girlfriend Mode", ignoreCase = true) ||
                personality.contains("Girlfriend", ignoreCase = true) ||
                personality.contains("Sweetheart", ignoreCase = true) ||
                personality.equals("Romantic Girlfriend", ignoreCase = true)

    fun setGirlfriendMode(enabled: Boolean) {
        if (enabled) {
            personality = "Girlfriend Mode"
            voice = "Girl Voice"
            voicePitch = 1.15f
            voiceSpeed = 1.0f
            wakeWord = "Jaan"
        } else {
            personality = "Assistant Mode"
            voice = "Natural Female"
            voicePitch = 1.08f
            voiceSpeed = 1.02f
            wakeWord = "Jarvis"
        }
    }

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "User") ?: "User"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, "Jarvis") ?: "Jarvis"
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value).apply()

    var youtubeEnabled: Boolean
        get() = prefs.getBoolean(KEY_YOUTUBE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_YOUTUBE_ENABLED, value).apply()

    var youtubeApiKey: String
        get() = prefs.getString(KEY_YOUTUBE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YOUTUBE_API_KEY, value.trim()).apply()

    var isPowerOnline: Boolean
        get() = prefs.getBoolean(KEY_POWER_ONLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_POWER_ONLINE, value).apply()

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    var isStandbyMode: Boolean
        get() = prefs.getBoolean(KEY_STANDBY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_STANDBY_MODE, value).apply()

    var isWhatsAppReaderEnabled: Boolean
        get() = prefs.getBoolean(KEY_WHATSAPP_READER, true)
        set(value) = prefs.edit().putBoolean(KEY_WHATSAPP_READER, value).apply()

    var isEdgeLightingEnabled: Boolean
        get() = prefs.getBoolean(KEY_EDGE_LIGHTING, true)
        set(value) = prefs.edit().putBoolean(KEY_EDGE_LIGHTING, value).apply()

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_VOICE_SPEED = "voice_speed"
        private const val KEY_VOICE_PITCH = "voice_pitch"
        private const val KEY_PERSONALITY = "personality"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_YOUTUBE_ENABLED = "youtube_enabled"
        private const val KEY_YOUTUBE_API_KEY = "youtube_api_key"
        private const val KEY_POWER_ONLINE = "power_online"
        private const val KEY_MIC_MUTED = "mic_muted"
        private const val KEY_STANDBY_MODE = "standby_mode"
        private const val KEY_WHATSAPP_READER = "whatsapp_reader"
        private const val KEY_EDGE_LIGHTING = "edge_lighting"
    }
}
