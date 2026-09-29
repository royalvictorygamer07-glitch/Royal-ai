package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class TtsService(
    private val context: Context,
    private val onStateChanged: (isSpeaking: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsService"
    }

    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false
    private var currentProfile: String = "Natural Girl Voice"
    private val speechQueue = ConcurrentLinkedQueue<String>()

    init {
        initEngine()
    }

    private fun initEngine() {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TextToSpeech: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                var configured = false
                val preferredLocales = listOf(
                    Locale.forLanguageTag("en-IN"),
                    Locale.getDefault(),
                    Locale.US,
                    Locale.forLanguageTag("hi-IN")
                )
                for (loc in preferredLocales) {
                    val res = engine.setLanguage(loc)
                    if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                        configured = true
                        break
                    }
                }
                if (!configured) {
                    engine.language = Locale.getDefault()
                }

                isInitialized = true
                applyVoiceProfile(currentProfile)

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onStateChanged(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        onStateChanged(false)
                        processNextQueuedSpeech()
                    }

                    override fun onError(utteranceId: String?) {
                        onStateChanged(false)
                        processNextQueuedSpeech()
                    }
                })

                processNextQueuedSpeech()
            }
        } else {
            Log.e(TAG, "TextToSpeech onInit failed with status $status")
            try {
                tts = TextToSpeech(context, this)
            } catch (_: Exception) {}
        }
    }

    private fun processNextQueuedSpeech() {
        val next = speechQueue.poll() ?: return
        speak(next)
    }

    private fun ensureAudibleVolume() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current == 0 || (current.toFloat() / max.toFloat()) < 0.35f) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.75f).toInt().coerceAtLeast(1), 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttrs)
                    .build()
                am.requestAudioFocus(focusReq)
            }
        } catch (_: Exception) {}
    }

    /**
     * Determines if a given voice is a natural female voice.
     */
    private fun isFemaleVoice(v: Voice): Boolean {
        val name = v.name.lowercase(Locale.ROOT)
        if (name.contains("female") || name.contains("woman") || name.contains("girl")) return true
        if (name.contains("en-in-x-end") || name.contains("en-in-x-cxx#female")) return true
        if (name.contains("hi-in-x-hie") || name.contains("hi-in-x-hic")) return true
        if (name.contains("en-us-x-sfg") || name.contains("en-us-x-iol")) return true
        if (name.contains("en-gb-x-rjs")) return true
        if (name.contains("f00") || name.contains("f01") || name.contains("f02") || name.contains("f03") || name.contains("f04")) return true
        val features = v.features
        if (features != null && (features.contains("gender=female") || features.contains("female"))) return true
        return false
    }

    /**
     * Finds the best natural female voice matching locale.
     */
    private fun findNaturalFemaleVoice(engine: TextToSpeech, preferHindi: Boolean): Voice? {
        val voices = engine.voices ?: return null
        if (voices.isEmpty()) return null

        val available = voices.filter { !it.isNetworkConnectionRequired }
        val pool = if (available.isNotEmpty()) available else voices.toList()

        if (preferHindi) {
            val hiFemale = pool.firstOrNull { it.locale.language == "hi" && isFemaleVoice(it) }
            if (hiFemale != null) return hiFemale
        }

        // Indian English female (most natural for Hinglish & English)
        val inFemale = pool.firstOrNull {
            it.locale.language == "en" &&
            (it.locale.country.equals("IN", ignoreCase = true) || it.name.contains("en-in", ignoreCase = true)) &&
            isFemaleVoice(it)
        }
        if (inFemale != null) return inFemale

        // Any English female
        val enFemale = pool.firstOrNull { it.locale.language == "en" && isFemaleVoice(it) }
        if (enFemale != null) return enFemale

        // Any female voice
        val anyFemale = pool.firstOrNull { isFemaleVoice(it) }
        if (anyFemale != null) return anyFemale

        return null
    }

    /**
     * Apply chosen voice profile (defaults to Natural Female voice).
     */
    fun applyVoiceProfile(
        profile: String,
        speedMultiplier: Float = 1.02f,
        pitchMultiplier: Float = 1.08f
    ) {
        currentProfile = profile
        val engine = tts ?: return
        if (!isInitialized) return

        val lower = profile.lowercase(Locale.ROOT)
        val isExplicitMale = lower.contains("male") || lower.contains("baritone") || lower.contains("classic")
        val isCalm = lower.contains("calm") || lower.contains("peaceful") || lower.contains("shant")
        val isEnergetic = lower.contains("energetic") || lower.contains("dynamic") || lower.contains("fast")
        val isSweet = lower.contains("sweet") || lower.contains("girlfriend") || lower.contains("romantic")

        // Natural Girl is the primary default profile with sweet, vibrant, natural girl pitch
        val (basePitch, baseRate) = when {
            isExplicitMale -> Pair(0.95f, 1.00f)
            isCalm -> Pair(1.08f, 0.94f)
            isEnergetic -> Pair(1.26f, 1.15f)
            isSweet -> Pair(1.24f, 1.04f)
            else -> Pair(1.22f, 1.04f) // Sweet natural girl voice cadence
        }

        try {
            val finalPitch = (basePitch * pitchMultiplier).coerceIn(0.6f, 1.8f)
            val finalRate = (baseRate * speedMultiplier).coerceIn(0.6f, 1.8f)

            engine.setPitch(finalPitch)
            engine.setSpeechRate(finalRate)

            if (!isExplicitMale) {
                val femaleVoice = findNaturalFemaleVoice(engine, containsDevanagari(currentProfile))
                if (femaleVoice != null) {
                    engine.voice = femaleVoice
                }
            } else {
                val maleVoice = engine.voices?.firstOrNull { voice ->
                    !voice.isNetworkConnectionRequired && (
                        voice.name.contains("male", ignoreCase = true) ||
                        voice.name.contains("m0", ignoreCase = true) ||
                        voice.name.contains("en-in-x-ena", ignoreCase = true) ||
                        voice.name.contains("en-us-x-iom", ignoreCase = true)
                    )
                }
                if (maleVoice != null) engine.voice = maleVoice
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error applying voice profile: ${e.message}")
        }
    }

    fun previewVoiceProfile(profile: String, speed: Float = 1.02f, pitch: Float = 1.08f) {
        applyVoiceProfile(profile, speed, pitch)
        val lower = profile.lowercase(Locale.ROOT)
        val sampleText = when {
            lower.contains("calm") -> "Calm female voice active. Slow, soothing, and relaxed cadence."
            lower.contains("energetic") -> "Energetic voice online! Maximum speed and lively cadence!"
            lower.contains("sweet") || lower.contains("girlfriend") -> "Haanji jaan, main aapki sweet girl voice assistant hoon. I am always here for you!"
            lower.contains("classic") || lower.contains("male") -> "Classic Jarvis voice operational. How may I be of assistance, sir?"
            else -> "Haanji! Main aapki natural girl voice assistant hoon. Main aapke ek hi baar bolne par turant jawab dungi."
        }
        speak(sampleText)
    }

    private fun sanitizeForSpeech(raw: String): String {
        var s = raw
        s = s.replace(Regex("https?://t\\.me/\\S+"), "AK EXPLOITS Telegram channel")
        s = s.replace(Regex("https?://\\S+"), "link")
        s = s.replace(Regex("[*#_`~>|\\[\\]{}()\"]"), " ")
        s = s.replace(Regex("^[\\s*-•]+", RegexOption.MULTILINE), " ")
        s = s.replace(Regex("[\\p{So}\\p{Cn}]"), "")
        s = s.replace(Regex("\\.{2,}"), ".")
        s = s.replace(Regex("-{2,}"), " ")
        s = s.replace(Regex("!{2,}"), "!")
        s = s.replace(Regex("\\?{2,}"), "?")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun containsDevanagari(text: String): Boolean {
        return text.any { it in '\u0900'..'\u097F' }
    }

    fun speak(text: String) {
        val cleaned = sanitizeForSpeech(text)
        if (cleaned.isBlank()) {
            onStateChanged(false)
            return
        }
        ensureAudibleVolume()
        if (!isInitialized || tts == null) {
            speechQueue.offer(cleaned)
            initEngine()
            return
        }
        val engine = tts ?: return
        val isHindi = containsDevanagari(cleaned)
        try {
            if (isHindi) {
                val hiLocale = Locale.forLanguageTag("hi-IN")
                if (engine.isLanguageAvailable(hiLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = hiLocale
                }
            } else {
                val enInLocale = Locale.forLanguageTag("en-IN")
                if (engine.isLanguageAvailable(enInLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = enInLocale
                } else {
                    engine.language = Locale.getDefault()
                }
            }
        } catch (_: Exception) {}

        applyVoiceProfile(currentProfile)

        val isExplicitMale = currentProfile.contains("male", ignoreCase = true) || currentProfile.contains("classic", ignoreCase = true)
        if (!isExplicitMale) {
            val femaleVoice = findNaturalFemaleVoice(engine, isHindi)
            if (femaleVoice != null) {
                try { engine.voice = femaleVoice } catch (_: Exception) {}
            }
        }

        val utteranceId = "JARVIS_SPEECH_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val result = engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "engine.speak failed code $result, retrying with default language")
            try {
                engine.language = Locale.getDefault()
                engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            } catch (retryEx: Exception) {
                Log.e(TAG, "Retry speak error: ${retryEx.message}")
            }
        }
    }

    fun testVoice(message: String = "Jarvis natural female voice audio is 100% online and functional.") {
        speak(message)
    }

    fun stop() {
        speechQueue.clear()
        try {
            tts?.stop()
        } catch (_: Exception) {}
        onStateChanged(false)
    }

    fun shutdown() {
        speechQueue.clear()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isInitialized = false
    }
}
