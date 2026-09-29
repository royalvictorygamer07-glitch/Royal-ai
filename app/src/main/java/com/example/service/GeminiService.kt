package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.example.data.JarvisPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class GeminiService(
    private val context: Context,
    private val onVoiceChanged: ((String) -> Unit)? = null,
    private val onMuteToggled: ((Boolean) -> Unit)? = null
) {
    companion object {
        private const val TAG = "GeminiService"
    }

    private val prefs = JarvisPreferences(context)
    val appManager = AppManagerHelper(context)
    val systemControl = SystemControlHelper(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun processQuery(query: String): String = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext ""
        val lower = cleanQuery.lowercase(Locale.ROOT)
        val isHindi = containsHindiOrHinglish(lower)

        // =========================================================================
        // 1. VOICE CHECK & MICROPHONE CONTROL (Instant Action)
        // =========================================================================
        if (isVoiceCheckCommand(lower)) {
            prefs.isMicMuted = false
            onMuteToggled?.invoke(false)
            return@withContext if (isHindi) {
                "Main bilkul bol rahi hoon. Mere natural female audio aur voice systems 100% active hain. Aap koi bhi command dein, main turant execute karungi."
            } else {
                "I am speaking clearly. My natural female voice and execution systems are 100% operational. How can I assist you?"
            }
        }
        if (isMuteCommand(lower)) {
            prefs.isMicMuted = true
            onMuteToggled?.invoke(true)
            return@withContext if (isHindi) {
                "Microphone mute kar diya gaya hai. Unmute karne ke liye mic button dabayein."
            } else {
                "Microphone muted. Tap the mic button to un-mute."
            }
        }
        if (isUnmuteCommand(lower)) {
            prefs.isMicMuted = false
            onMuteToggled?.invoke(false)
            return@withContext if (isHindi) {
                "Microphone unmute ho gaya hai. Main aapki commands sun rahi hoon."
            } else {
                "Microphone unmuted. Standing by for instructions."
            }
        }

        // =========================================================================
        // 1B. BACKGROUND SERVICE CONTROL & WAKE WORD ("Jarvis off d background", "Hey Jarvis on")
        // =========================================================================
        if (isBackgroundOffCommand(lower)) {
            JarvisBackgroundService.instance?.setStandbyMode(true)
            prefs.isStandbyMode = true
            return@withContext if (isHindi) {
                "Jarvis background service off kar di gayi hai, Sir. Main standby mode me hoon. Jab bhi zaroorat ho, bas 'Hey Jarvis' bolein."
            } else {
                "Jarvis background service deactivated, Sir. I am in standby mode. Just say 'Hey Jarvis' whenever you need me."
            }
        }
        if (isWakeUpCommand(lower)) {
            JarvisBackgroundService.instance?.setStandbyMode(false)
            prefs.isStandbyMode = false
            prefs.isPowerOnline = true
            prefs.isMicMuted = false
            return@withContext if (isHindi) {
                "Ji Sir, Jarvis active aur ready hai! Boliye, main aapki kya madad karoon?"
            } else {
                "Yes Sir! Jarvis is fully online and ready. How can I help you?"
            }
        }

        // =========================================================================
        // 1C. WHATSAPP MESSAGE READER CONFIGURATION & STATUS
        // =========================================================================
        if (isWhatsAppReaderCommand(lower)) {
            val hasPerm = JarvisNotificationListenerService.isPermissionGranted(context)
            if (!hasPerm) {
                JarvisNotificationListenerService.openSettings(context)
                return@withContext if (isHindi) {
                    "WhatsApp messages padhne ke liye Notification Access settings khol di gayi hain. Kripya Jarvis ko allow karein."
                } else {
                    "Opening Notification Access settings so Jarvis can read your WhatsApp messages aloud."
                }
            } else {
                prefs.isWhatsAppReaderEnabled = true
                return@withContext if (isHindi) {
                    "WhatsApp reader 100% active hai. Jaise hi koi WhatsApp message aayega, main use bol kar bataungi."
                } else {
                    "WhatsApp reader is fully active. Incoming messages will be announced aloud automatically."
                }
            }
        }

        // =========================================================================
        // 2. CREATOR & OFFICIAL TELEGRAM CHANNEL
        // =========================================================================
        if (isCreatorQuery(lower)) {
            return@withContext if (isHindi) {
                "Mujhe AK EXPLOITS ne develop kiya hai. AK EXPLOITS ka official Telegram channel: https://t.me/+FeBUfQ14loAxZmU1 hai."
            } else {
                "I was created and engineered by AK EXPLOITS. Official Telegram: https://t.me/+FeBUfQ14loAxZmU1."
            }
        }

        // =========================================================================
        // 3. VOICE PROFILE SWITCH (Calm, Energetic, Professional, Girl Voice, Male)
        // =========================================================================
        if (isCalmVoiceQuery(lower)) {
            prefs.voice = "Calm"
            onVoiceChanged?.invoke("Calm")
            return@withContext if (isHindi) {
                "Voice profile ko Calm (शांत) mode me switch kar diya gaya hai. Relaxed aur smooth cadence active."
            } else {
                "Voice profile switched to Calm. Relaxed, soothing cadence operational."
            }
        }
        if (isEnergeticVoiceQuery(lower)) {
            prefs.voice = "Energetic"
            onVoiceChanged?.invoke("Energetic")
            return@withContext if (isHindi) {
                "Voice profile ko Energetic mode me switch kar diya gaya hai. High throttle dynamic delivery!"
            } else {
                "Voice profile switched to Energetic. High-frequency dynamic cadence operational."
            }
        }
        if (isProfessionalVoiceQuery(lower)) {
            prefs.voice = "Professional"
            onVoiceChanged?.invoke("Professional")
            return@withContext if (isHindi) {
                "Voice profile ko Professional mode me set kar diya gaya hai. Authoritative aur formal response."
            } else {
                "Voice profile switched to Professional. Authoritative and measured delivery operational."
            }
        }
        if (isGirlVoiceQuery(lower)) {
            prefs.voice = "Girl Voice"
            onVoiceChanged?.invoke("Girl Voice")
            return@withContext if (isHindi) {
                "Voice profile ko Friday Girl Voice me switch kar diya gaya hai."
            } else {
                "Voice profile switched to Friday."
            }
        }
        if (isMaleVoiceQuery(lower)) {
            prefs.voice = "Classic"
            onVoiceChanged?.invoke("Classic")
            return@withContext if (isHindi) {
                "Voice profile ko classic Jarvis male timbre me set kar diya gaya hai."
            } else {
                "Voice profile restored to classic Jarvis male timbre."
            }
        }

        // =========================================================================
        // 3B. GIRLFRIEND MODE ACTIVATION & DEACTIVATION
        // =========================================================================
        if (isGirlfriendActivationQuery(lower)) {
            prefs.setGirlfriendMode(true)
            onVoiceChanged?.invoke("Girl Voice")
            return@withContext "Haan mere jaan! Ab main tumhari girlfriend ban gayi hoon. Tumse bohot pyaar karungi aur hamesha tumhara dhyan rakhungi. Boliye babu, ab kya karein? 🥰"
        }
        if (isGirlfriendDeactivationQuery(lower)) {
            prefs.setGirlfriendMode(false)
            onVoiceChanged?.invoke("Professional")
            return@withContext "Girlfriend mode deactivate kar diya gaya hai. Jarvis assistant mode operational."
        }

        // =========================================================================
        // 4. INSTANT MATH & CALCULATION (Instant Direct Evaluation)
        // =========================================================================
        val mathResult = tryEvaluateMath(cleanQuery, lower)
        if (mathResult != null) {
            return@withContext mathResult
        }

        // =========================================================================
        // 5. FLASHLIGHT / TORCH (On, Off, Toggle, Batti, Roshni)
        // =========================================================================
        if (isFlashlightCommand(lower)) {
            return@withContext when {
                "on" in lower || "chalu" in lower || "jalao" in lower || "start" in lower ||
                        "open" in lower || "kholo" in lower || "jalado" in lower || "andhera" in lower -> {
                    val res = systemControl.setTorch(true)
                    if (isHindi) "Flashlight chalu kar di gayi hai." else res
                }
                "off" in lower || "band" in lower || "bujhao" in lower || "stop" in lower || "close" in lower -> {
                    val res = systemControl.setTorch(false)
                    if (isHindi) "Flashlight band kar di gayi hai." else res
                }
                else -> {
                    val res = systemControl.toggleTorch()
                    if (isHindi) "Flashlight toggle kar di gayi hai." else res
                }
            }
        }

        // =========================================================================
        // 6. ALARM COMMANDS (Set Alarm, Cancel/Show Alarms)
        // =========================================================================
        if (isAlarmCommand(lower)) {
            return@withContext handleAlarmCommand(cleanQuery, lower, isHindi)
        }

        // =========================================================================
        // 7. TIMER & STOPWATCH
        // =========================================================================
        if (isTimerCommand(lower)) {
            return@withContext handleTimerCommand(cleanQuery, lower, isHindi)
        }

        // =========================================================================
        // 8. PHONE CALL & CONTACT DIALING
        // =========================================================================
        if (isCallCommand(lower)) {
            val target = extractCallTarget(cleanQuery)
            return@withContext systemControl.makePhoneCall(target)
        }

        // =========================================================================
        // 9. WHATSAPP (Send Message or Open)
        // =========================================================================
        if (isWhatsAppCommand(lower)) {
            return@withContext handleWhatsAppCommand(cleanQuery, lower)
        }

        // =========================================================================
        // 10. SMS / TEXT MESSAGE
        // =========================================================================
        if (isSmsCommand(lower)) {
            return@withContext handleSmsCommand(cleanQuery, lower)
        }

        // =========================================================================
        // 11. YOUTUBE SEARCH & DIRECT VIDEO/SONG PLAYBACK
        // =========================================================================
        if (isYouTubePlayCommand(lower)) {
            val videoTarget = extractYouTubeQuery(cleanQuery)
            systemControl.searchOrPlayYouTube(videoTarget)
            return@withContext if (prefs.isGfMode) {
                "Haan mere jaan! Main YouTube open karke '${videoTarget}' search kar rahi hoon tumhare liye. Dekho saare videos aa gaye! 🥰"
            } else if (isHindi) {
                "Maine YouTube open karke '${videoTarget}' search kar diya hai."
            } else {
                "Searching and playing '${videoTarget}' on YouTube."
            }
        }

        // =========================================================================
        // 11B. GOOGLE & WEB SEARCH
        // =========================================================================
        if (isGoogleSearchCommand(lower)) {
            val googleTarget = extractGoogleQuery(cleanQuery)
            systemControl.searchGoogle(googleTarget)
            return@withContext if (prefs.isGfMode) {
                "Haan sweetheart, maine Google open karke '${googleTarget}' search kar diya hai tumhare liye! Dekh lo mere babu. ❤️"
            } else if (isHindi) {
                "Maine Google par '${googleTarget}' search kar diya hai."
            } else {
                "Searching Google for '${googleTarget}'."
            }
        }

        // =========================================================================
        // 12. MUSIC & SONGS
        // =========================================================================
        if (isMusicCommand(lower)) {
            val songTarget = cleanQuery
                .replace(Regex("^(play music|play song|play|gaana bajao|gana bajao|gaana chalao|gana chalao|music chalao|song bajao)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(gaana bajao|chalao|play karo)$", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { if (prefs.isGfMode) "Top Romantic Hindi Songs" else "Top Hindi Songs" }
            systemControl.playMusic(songTarget)
            return@withContext if (prefs.isGfMode) {
                "Tumhare liye gaana play kar rahi hoon mere jaan! Enjoy karo babu! 🎶❤️"
            } else if (isHindi) {
                "Maine gaana chala diya hai."
            } else {
                "Playing music."
            }
        }

        // =========================================================================
        // 13. CAMERA & SELFIES
        // =========================================================================
        if (isCameraCommand(lower)) {
            val isFront = "front" in lower || "selfie" in lower
            val isVideo = "video" in lower || "record" in lower
            return@withContext systemControl.openCamera(front = isFront, video = isVideo)
        }

        // =========================================================================
        // 14. AUDIO VOLUME CONTROLS
        // =========================================================================
        if (isVolumeCommand(lower)) {
            return@withContext handleVolumeCommand(lower)
        }

        // =========================================================================
        // 15. RINGER MODES (Silent, Vibrate, Normal)
        // =========================================================================
        if ("silent" in lower && ("phone" in lower || "mode" in lower || "karo" in lower || "daalo" in lower)) {
            return@withContext systemControl.setRingerMode("silent")
        }
        if ("vibrate" in lower && ("phone" in lower || "mode" in lower || "karo" in lower || "daalo" in lower)) {
            return@withContext systemControl.setRingerMode("vibrate")
        }
        if ("normal" in lower && ("mode" in lower || "ring" in lower || "karo" in lower)) {
            return@withContext systemControl.setRingerMode("normal")
        }

        // =========================================================================
        // 16. SCREEN BRIGHTNESS
        // =========================================================================
        if ("brightness" in lower) {
            val match = Regex("(\\d{1,3})\\s*%?").find(lower)
            if (match != null) {
                val pct = match.groupValues[1].toIntOrNull() ?: 50
                return@withContext systemControl.setBrightness(pct)
            }
            if ("full" in lower || "max" in lower || "100" in lower) {
                return@withContext systemControl.setBrightness(100)
            }
            if ("kam" in lower || "low" in lower || "down" in lower || "dheemi" in lower) {
                return@withContext systemControl.setBrightness(20)
            }
            if ("badhao" in lower || "up" in lower || "high" in lower || "tez" in lower) {
                return@withContext systemControl.setBrightness(85)
            }
            return@withContext systemControl.openSettings("display")
        }

        // =========================================================================
        // 17. TIME & DATE
        // =========================================================================
        if (isTimeDateCommand(lower)) {
            return@withContext systemControl.getCurrentTime()
        }

        // =========================================================================
        // 18. BATTERY TELEMETRY
        // =========================================================================
        if ("battery" in lower || "charging" in lower || "charge" in lower || "kitna percent" in lower) {
            return@withContext systemControl.getBatteryStatus()
        }

        // =========================================================================
        // 19. MAPS & NAVIGATION
        // =========================================================================
        if (isNavigationCommand(lower)) {
            val dest = cleanQuery
                .replace(Regex("^(navigate to|directions to|route to|rasta dikhao|kahan hai|le chalo)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(ka rasta|chalo|navigate karo|kahan hai)$", RegexOption.IGNORE_CASE), "")
                .trim()
            return@withContext systemControl.openMapsNavigation(dest.ifBlank { "nearby places" })
        }

        // =========================================================================
        // 20. SCREEN GESTURES & GLOBAL NAVIGATION (Screenshot, Home, Back, Recents, Lock, Scroll)
        // =========================================================================
        val globalAction = matchGlobalAction(lower)
        if (globalAction != null) {
            return@withContext systemControl.executeGlobalSystemAction(globalAction)
        }

        // =========================================================================
        // 21. ACCESSIBILITY SCREEN CLICKS & TOUCH
        // =========================================================================
        if (isScreenControlCommand(lower)) {
            return@withContext handleScreenControl(cleanQuery, lower, isHindi)
        }

        // =========================================================================
        // 21B. CORNER EDGE LIGHTING / BORDERLIGHT OVERLAY (Always on & background)
        // =========================================================================
        if (isEdgeLightingCommand(lower)) {
            return@withContext handleEdgeLightingCommand(lower, isHindi)
        }

        // =========================================================================
        // 22. SETTINGS SHORTCUTS
        // =========================================================================
        if (isSettingsCommand(lower)) {
            val sec = when {
                "wifi" in lower -> "wifi"
                "bluetooth" in lower -> "bluetooth"
                "hotspot" in lower || "tethering" in lower -> "hotspot"
                "airplane" in lower || "flight" in lower -> "airplane"
                "sound" in lower -> "sound"
                "display" in lower -> "display"
                "battery" in lower -> "battery"
                "accessibility" in lower -> "accessibility"
                else -> null
            }
            return@withContext systemControl.openSettings(sec)
        }

        // =========================================================================
        // 23. LAUNCH ANY INSTALLED APP (100% Dynamic Scan & Launch)
        // =========================================================================
        if (isAppLaunchCommand(lower)) {
            val appResult = appManager.findAndLaunchApp(cleanQuery, isHindi)
            return@withContext appResult.message
        }

        // =========================================================================
        // 24. GEMINI API INTELLIGENT REASONING (if API key available)
        // =========================================================================
        val apiKey = prefs.apiKey
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val response = callGeminiRestApi(cleanQuery, apiKey, prefs.model)
                if (response.isNotBlank()) {
                    return@withContext response
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini API call failed: ${e.message}")
            }
        }

        // =========================================================================
        // 25. AUTONOMOUS COGNITIVE MIND DECISION MATRIX (Rich Hindi + English)
        // =========================================================================
        return@withContext decideCognitiveResponse(cleanQuery, lower, isHindi)
    }

    private fun isBackgroundOffCommand(lower: String): Boolean {
        return lower.contains("off d background") || lower.contains("off the background") ||
                lower.contains("off background") || lower.contains("background off") ||
                lower.contains("turn off background") || lower.contains("background band") ||
                lower.contains("background service off") || lower.contains("background band karo") ||
                lower.contains("background me mat raho") || lower == "jarvis off" ||
                lower.contains("jarvis so jao") || lower.contains("sleep mode") ||
                lower.contains("jarvis sleep")
    }

    private fun isWakeUpCommand(lower: String): Boolean {
        return lower == "hey jarvis" || lower == "he jarvis" || lower == "hi jarvis" ||
                lower == "hello jarvis" || lower == "jarvis on" || lower == "hey jarvis on" ||
                lower == "wake up jarvis" || lower == "jarvis wake up" || lower == "jarvis utho" ||
                lower == "jarvis chalu ho jao" || lower == "jarvis start" || lower == "jarvis online" ||
                lower == "suno jarvis" || lower == "jarvis suno" || lower == "ok jarvis" ||
                lower == "jarvis"
    }

    private fun isWhatsAppReaderCommand(lower: String): Boolean {
        return lower.contains("whatsapp message padho") || lower.contains("whatsapp padho") ||
                lower.contains("kya message kiya") || lower.contains("kya message aaya") ||
                lower.contains("read whatsapp") || lower.contains("whatsapp reader") ||
                lower.contains("whatsapp message padh") || lower.contains("message padh ke")
    }

    private fun isMuteCommand(lower: String): Boolean {
        return lower == "mute" || lower == "mute mic" || lower == "mic mute" ||
                lower.contains("mic mute karo") || lower.contains("mic band karo") ||
                lower.contains("aawaz band karo") || lower.contains("chup ho jao") ||
                lower.contains("shant raho") || lower == "mute karo" || lower.contains("chup raho")
    }

    private fun isUnmuteCommand(lower: String): Boolean {
        return lower == "unmute" || lower == "unmute mic" || lower == "mic unmute" ||
                lower.contains("mic unmute karo") || lower.contains("mic chalu karo") ||
                lower.contains("aawaz chalu karo") || lower.contains("bolo") ||
                lower == "unmute karo" || lower.contains("shuru karo")
    }

    private fun isCallCommand(lower: String): Boolean {
        return lower.startsWith("call ") || lower.startsWith("phone ") ||
                lower.startsWith("dial ") || lower.endsWith(" ko call karo") ||
                lower.endsWith(" ko phone lagao") || lower.contains("phone lagao") ||
                lower.contains("call lagao") || lower.contains("phone mila do") ||
                lower.contains("call mila do")
    }

    private fun extractCallTarget(query: String): String {
        return query.replace(Regex("^(call|phone|dial|phone lagao|call karo|phone mila do)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(ko call karo|ko phone lagao|call karo|phone lagao|ko phone mila do)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun isYouTubePlayCommand(lower: String): Boolean {
        return (("youtube" in lower || "yt" in lower) && (
                "play" in lower || "search" in lower || "open" in lower || "khol" in lower ||
                "chalao" in lower || "gaana" in lower || "song" in lower || "video" in lower ||
                "bajao" in lower || "dhundo" in lower || "laga" in lower || "dekh" in lower
        )) || lower.startsWith("play on youtube") || lower.contains("youtube par") || lower.contains("youtube pe") || lower.contains("youtube me")
    }

    private fun extractYouTubeQuery(query: String): String {
        var s = query.trim()
        // Strip common command prefixes
        s = s.replace(Regex("^(youtube|yt)\\s*(open\\s*karke|khol\\s*ke|kholo\\s*aur|khol\\s*kar|open\\s*karo\\s*aur|open\\s*karo|kholo|par|pe|me|mein)?\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^(play on youtube|search on youtube|search for|search|play|find|kholo|open)\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^(youtube|yt)\\s*(par|pe|me|mein)?\\s*", RegexOption.IGNORE_CASE), "")

        // Strip common command suffixes
        s = s.replace(Regex("\\s*(search\\s*karo|search\\s*kar\\s*do|search\\s*karo\\s*na|search\\s*kar|dhundo|chalao|gaana\\s*chalao|gana\\s*chalao|song\\s*chalao|bajao|laga\\s*do|video\\s*chalao|play\\s*karo|on\\s*youtube|youtube\\s*par|youtube\\s*pe)$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\s*(search|dhundho|play)$", RegexOption.IGNORE_CASE), "")
        s = s.trim()

        return s.ifBlank { "AK EXPLOITS" }
    }

    private fun isGoogleSearchCommand(lower: String): Boolean {
        return ("google" in lower || "chrome" in lower) && (
                "search" in lower || "dhundo" in lower || "open karke" in lower || "khol ke" in lower || "kholo aur" in lower || "par search" in lower || "pe search" in lower
        )
    }

    private fun extractGoogleQuery(query: String): String {
        var s = query.trim()
        s = s.replace(Regex("^(google|chrome)\\s*(open\\s*karke|khol\\s*ke|kholo\\s*aur|khol\\s*kar|open\\s*karo\\s*aur|open\\s*karo|kholo|par|pe|me|mein)?\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^(search on google|search for|search|find|kholo|open)\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^(google|chrome)\\s*(par|pe|me|mein)?\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\s*(search\\s*karo|search\\s*kar\\s*do|search\\s*karo\\s*na|search\\s*kar|dhundo|on\\s*google|google\\s*par|google\\s*pe)$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\s*(search|dhundho)$", RegexOption.IGNORE_CASE), "")
        return s.trim().ifBlank { "AK EXPLOITS" }
    }

    private fun isMusicCommand(lower: String): Boolean {
        return lower.startsWith("play music") || lower.startsWith("play song") ||
                lower.contains("gaana bajao") || lower.contains("gana bajao") ||
                lower.contains("music chalao") || lower.contains("gaana chalao") ||
                lower.contains("song bajao") || lower.contains("kuch sunao") ||
                lower.contains("spotify")
    }

    private fun isCameraCommand(lower: String): Boolean {
        return lower.contains("camera") || lower.contains("photo khicho") || lower.contains("take a picture") ||
                lower.contains("selfie") || lower.contains("photo le lo") || lower.contains("video record") || lower.contains("video banao")
    }

    private fun isFlashlightCommand(lower: String): Boolean {
        return lower.contains("flashlight") || lower.contains("torch") ||
                lower.contains("batti") || lower.contains("light jalao") ||
                lower.contains("light band") || lower.contains("light on") ||
                lower.contains("light off") || lower.contains("roshni") ||
                lower.contains("andhera hai")
    }

    private fun isAlarmCommand(lower: String): Boolean {
        return lower.contains("alarm") || lower.contains("wake me up") || lower.contains("jaga dena") || lower.contains("utha dena")
    }

    private fun handleAlarmCommand(cleanQuery: String, lower: String, isHindi: Boolean): String {
        if ("show" in lower || "open" in lower || "dikhao" in lower || "kholo" in lower || lower == "alarms") {
            return systemControl.showAlarms()
        }

        var hour = 7
        var minute = 0

        // Parse: 7:30 am, 07:30 pm, 6:00
        val timeMatch = Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(lower)
        if (timeMatch != null) {
            hour = timeMatch.groupValues[1].toIntOrNull() ?: 7
            minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val amPm = timeMatch.groupValues[3].lowercase(Locale.ROOT)
            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0
            return systemControl.setAlarm(hour, minute, "Jarvis Alarm")
        }

        // Parse: "subah 6 baje", "shaam 8 baje", "7 baje", "7 am"
        val simpleMatch = Regex("(subah|shaam|dopahar|raat)?\\s*(\\d{1,2})\\s*(am|pm|baje)?", RegexOption.IGNORE_CASE).find(lower)
        if (simpleMatch != null) {
            val period = simpleMatch.groupValues[1].lowercase(Locale.ROOT)
            hour = simpleMatch.groupValues[2].toIntOrNull() ?: 7
            val suffix = simpleMatch.groupValues[3].lowercase(Locale.ROOT)
            if ((period == "shaam" || period == "raat" || suffix == "pm") && hour < 12) hour += 12
            if ((period == "subah" || suffix == "am") && hour == 12) hour = 0
            return systemControl.setAlarm(hour, 0, "Jarvis Alarm")
        }

        return systemControl.showAlarms()
    }

    private fun isTimerCommand(lower: String): Boolean {
        return lower.contains("timer") || lower.contains("stopwatch")
    }

    private fun handleTimerCommand(cleanQuery: String, lower: String, isHindi: Boolean): String {
        if ("stopwatch" in lower) {
            return systemControl.showTimers()
        }
        if ("show" in lower || "open" in lower || "dikhao" in lower || lower == "timers") {
            return systemControl.showTimers()
        }

        var totalSeconds = 60
        val minMatch = Regex("(\\d+)\\s*(minute|min|m|mint)", RegexOption.IGNORE_CASE).find(lower)
        val secMatch = Regex("(\\d+)\\s*(second|sec|s)", RegexOption.IGNORE_CASE).find(lower)

        if (minMatch != null || secMatch != null) {
            val mins = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val secs = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            totalSeconds = (mins * 60) + secs
            if (totalSeconds <= 0) totalSeconds = 60
            return systemControl.setTimer(totalSeconds, "Jarvis Timer")
        }

        val numOnly = Regex("(\\d+)\\s*(minute|min)?", RegexOption.IGNORE_CASE).find(lower)
        if (numOnly != null) {
            val mins = numOnly.groupValues[1].toIntOrNull() ?: 5
            return systemControl.setTimer(mins * 60, "Jarvis Timer")
        }

        return systemControl.showTimers()
    }

    private fun isWhatsAppCommand(lower: String): Boolean {
        return "whatsapp" in lower
    }

    private fun handleWhatsAppCommand(cleanQuery: String, lower: String): String {
        val msg = cleanQuery.substringAfter("saying", "")
            .ifEmpty { cleanQuery.substringAfter("bol kar", "") }
            .ifEmpty { cleanQuery.substringAfter("bolo ki", "") }
            .ifEmpty { cleanQuery.substringAfter("message", "") }
            .ifEmpty { cleanQuery.substringAfter("whatsapp", "") }
            .trim()
        val textToSend = if (msg.isNotEmpty()) msg else "Hello from Jarvis"
        return systemControl.sendWhatsApp(textToSend)
    }

    private fun isSmsCommand(lower: String): Boolean {
        return lower.startsWith("send sms") || lower.startsWith("sms ") || lower.contains("sms bhejo") || lower.contains("message bhejo")
    }

    private fun handleSmsCommand(cleanQuery: String, lower: String): String {
        val target = cleanQuery.substringAfter("to", "").substringBefore("saying", "").trim()
        val msg = cleanQuery.substringAfter("saying", "")
            .ifEmpty { cleanQuery.substringAfter("message", "") }
            .ifEmpty { cleanQuery.substringAfter("sms", "") }
            .trim()
        return systemControl.sendSms(target, if (msg.isNotEmpty()) msg else "Hello")
    }

    private fun isVolumeCommand(lower: String): Boolean {
        return "volume" in lower || "awaaz" in lower || "sound" in lower
    }

    private fun handleVolumeCommand(lower: String): String {
        if ("up" in lower || "increase" in lower || "badhao" in lower || "tez" in lower || "zyada" in lower) {
            return systemControl.adjustVolume(true)
        }
        if ("down" in lower || "decrease" in lower || "kam" in lower || "ghatao" in lower || "dheemi" in lower) {
            return systemControl.adjustVolume(false)
        }
        if ("mute" in lower || "shant" in lower) {
            return systemControl.muteAudio(true)
        }
        if ("unmute" in lower) {
            return systemControl.muteAudio(false)
        }
        if ("full" in lower || "max" in lower || "100" in lower) {
            return systemControl.setVolumePercent(100)
        }
        val match = Regex("(\\d{1,3})\\s*%").find(lower)
        if (match != null) {
            val pct = match.groupValues[1].toIntOrNull() ?: 50
            return systemControl.setVolumePercent(pct)
        }
        return systemControl.adjustVolume(true)
    }

    private fun isTimeDateCommand(lower: String): Boolean {
        return "time" in lower || "samay" in lower || "waqt" in lower || "ghadi" in lower ||
                "date" in lower || "tarikh" in lower || "aaj kaun sa din" in lower ||
                "kitne baje" in lower || "aaj ka din" in lower
    }

    private fun isNavigationCommand(lower: String): Boolean {
        return lower.startsWith("navigate") || lower.startsWith("directions to") ||
                lower.startsWith("route to") || lower.contains("rasta dikhao") ||
                lower.contains("kahan hai") || lower.contains("le chalo")
    }

    private fun isSettingsCommand(lower: String): Boolean {
        return lower.contains("setting") || lower.contains("settings") ||
                "wifi on" in lower || "bluetooth on" in lower || "hotspot on" in lower
    }

    private fun matchGlobalAction(lower: String): String? {
        return when {
            "screenshot" in lower || "screen capture" in lower -> "screenshot"
            lower == "home" || "go home" in lower || "home screen" in lower || "home jao" in lower -> "home"
            lower == "back" || "go back" in lower || "wapas jao" in lower || "piche jao" in lower -> "back"
            "recent apps" in lower || "recents" in lower || "recent task" in lower -> "recents"
            "notification" in lower -> "notifications"
            "quick settings" in lower || "control center" in lower -> "quick_settings"
            "lock screen" in lower || "turn off screen" in lower || "screen band karo" in lower || "phone lock" in lower -> "lock"
            "scroll down" in lower || "niche scroll" in lower -> "scroll_down"
            "scroll up" in lower || "upar scroll" in lower -> "scroll_up"
            else -> null
        }
    }

    private fun tryEvaluateMath(cleanQuery: String, lower: String): String? {
        if (lower.startsWith("calculate ") || lower.startsWith("what is ") || lower.contains("kitna hota hai") ||
            lower.contains("plus") || lower.contains("minus") || lower.contains("into") || lower.contains("divided by") ||
            lower.contains("multiply") || lower.contains("percent") ||
            Regex("^\\s*\\d+\\s*[*+\\-/xX%^]\\s*\\d+").containsMatchIn(lower)
        ) {
            val expr = cleanQuery.replace(Regex("^(calculate|what is|calculate this)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(kitna hota hai|equals|karo)$", RegexOption.IGNORE_CASE), "")
                .replace("plus", "+", ignoreCase = true)
                .replace("minus", "-", ignoreCase = true)
                .replace("multiply", "*", ignoreCase = true)
                .replace("multiplied by", "*", ignoreCase = true)
                .replace("into", "*", ignoreCase = true)
                .replace("x", "*", ignoreCase = true)
                .replace("divide", "/", ignoreCase = true)
                .replace("divided by", "/", ignoreCase = true)
                .replace("percent", "%", ignoreCase = true)
                .trim()
            val result = systemControl.evaluateMath(expr)
            if (result != null) return result
        }
        return null
    }

    private fun isAppLaunchCommand(lower: String): Boolean {
        if (lower.startsWith("open ") || lower.startsWith("kholo ") || lower.startsWith("chalu ") ||
            lower.startsWith("launch ") || lower.startsWith("start ")) {
            return true
        }
        if (lower.endsWith(" open karo") || lower.endsWith(" khol do") || lower.endsWith(" chala do") ||
            lower.endsWith(" kholo") || lower.endsWith(" app open") || lower.endsWith(" app kholo")) {
            return true
        }
        val directApps = listOf(
            "youtube", "whatsapp", "instagram", "facebook", "telegram", "free fire", "freefire",
            "bgmi", "chrome", "camera", "gallery", "settings", "calculator", "play store", "snapchat",
            "spotify", "maps", "gmail", "clock", "files", "contacts", "dialer"
        )
        return directApps.any { it in lower }
    }

    private fun handleScreenControl(cleanQuery: String, lower: String, isHindi: Boolean): String {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}

            return if (isHindi) {
                "Screen control ke liye Accessibility Service on hona zaroori hai. Settings me 'Jarvis AI' ko on karein."
            } else {
                "Screen automation requires Accessibility permission. Please enable 'Jarvis AI' in Settings."
            }
        }

        // Screen text inspection / reading
        if ("read screen" in lower || "screen par kya hai" in lower || "kya dikh raha hai" in lower || "screen read karo" in lower) {
            val texts = service.collectVisibleScreenTexts()
            return if (texts.isNotEmpty()) {
                if (isHindi) {
                    "Screen par yeh elements dikh rahe hain: " + texts.take(6).joinToString(", ")
                } else {
                    "Visible on screen: " + texts.take(6).joinToString(", ")
                }
            } else {
                if (isHindi) "Screen par koi read karne yogya text nahi mila." else "No readable text detected on screen."
            }
        }

        // Typing / writing text on screen
        if (lower.startsWith("type ") || lower.startsWith("write ") || lower.startsWith("likho ") || lower.contains(" type karo") || lower.contains(" likh do")) {
            var textToType = cleanQuery
                .replace(Regex("^(type|write|likho|enter)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(type karo|likh do|likho)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (textToType.isNotEmpty()) {
                val ok = service.typeText(textToType)
                return if (ok) {
                    if (isHindi) "\"$textToType\" screen field me likh diya gaya hai." else "Typed \"$textToType\" on screen."
                } else {
                    if (isHindi) "Screen par input box nahi mila, par gesture ready hai." else "Could not find active input field to type."
                }
            }
        }

        // Double click / Long press / Hold
        if ("double click" in lower || "double tap" in lower) {
            val dm = context.resources.displayMetrics
            service.doubleClickAt(dm.widthPixels / 2f, dm.heightPixels / 2f)
            return if (isHindi) "Double click execute kar diya gaya hai." else "Double clicked screen."
        }
        if ("long press" in lower || "press and hold" in lower || "dabaye rakho" in lower || "hold karo" in lower) {
            val dm = context.resources.displayMetrics
            service.longPressAt(dm.widthPixels / 2f, dm.heightPixels / 2f, 1200)
            return if (isHindi) "Long press execute kar diya gaya hai." else "Long pressed screen."
        }

        // Swiping gestures
        if ("swipe left" in lower || "left swipe" in lower || "baayein swipe" in lower) {
            service.swipe(0.85f, 0.5f, 0.15f, 0.5f, 300)
            return if (isHindi) "Left swipe execute kar diya gaya hai." else "Swiped left."
        }
        if ("swipe right" in lower || "right swipe" in lower || "daayein swipe" in lower) {
            service.swipe(0.15f, 0.5f, 0.85f, 0.5f, 300)
            return if (isHindi) "Right swipe execute kar diya gaya hai." else "Swiped right."
        }

        if ("back" in lower || "piche" in lower) {
            service.goBack()
            return if (isHindi) "Back action execute kar diya gaya hai." else "Navigated back."
        }
        if ("home" in lower) {
            service.goHome()
            return if (isHindi) "Home screen par navigate kar diya gaya hai." else "Navigated to home screen."
        }
        if ("recent" in lower) {
            service.openRecents()
            return if (isHindi) "Recent apps open kar di gayi hain." else "Opened recent apps."
        }
        if ("scroll down" in lower || "niche scroll" in lower || "down scroll" in lower) {
            service.scroll(true)
            return if (isHindi) "Screen scroll down kar di gayi hai." else "Scrolled down."
        }
        if ("scroll up" in lower || "upar scroll" in lower || "up scroll" in lower) {
            service.scroll(false)
            return if (isHindi) "Screen scroll up kar di gayi hai." else "Scrolled up."
        }

        if ("center" in lower || "beech" in lower || "middle" in lower || lower == "click" || lower == "click karo") {
            service.clickCenter()
            return if (isHindi) "Screen center par click kar diya gaya hai." else "Clicked screen center."
        }

        var target = cleanQuery
        val prefixes = listOf("click on ", "click ", "tap on ", "tap ", "press ", "dabao ", "touch ")
        for (p in prefixes) {
            if (target.startsWith(p, ignoreCase = true)) {
                target = target.substring(p.length).trim()
                break
            }
        }
        val suffixes = listOf(" pe click karo", " par click karo", " click karo", " pe click", " par tap karo", " dabao")
        for (s in suffixes) {
            if (target.endsWith(s, ignoreCase = true)) {
                target = target.removeSuffix(s).trim()
                break
            }
        }

        if (target.isNotEmpty()) {
            val clicked = service.smartClick(target)
            return if (clicked) {
                if (isHindi) "'$target' par click kar diya gaya hai." else "Clicked '$target'."
            } else {
                service.clickCenter()
                if (isHindi) "'$target' ke liye gesture execute kar diya gaya hai." else "Dispatched gesture for '$target'."
            }
        }

        service.clickCenter()
        return if (isHindi) "Screen click execute kar diya gaya hai." else "Screen clicked."
    }

    private fun isScreenControlCommand(lower: String): Boolean {
        return lower.contains("click") || lower.contains("tap") || lower.contains("scroll") ||
                lower.contains("swipe") || lower.contains("long press") || lower.contains("dabao") ||
                lower.contains("touch") || lower.startsWith("type ") || lower.startsWith("write ") ||
                lower.startsWith("likho ") || lower.contains("read screen") || lower.contains("screen par kya hai") ||
                lower.contains("kya dikh raha hai") || lower.contains("press and hold")
    }

    private fun isEdgeLightingCommand(lower: String): Boolean {
        return lower.contains("edge light") || lower.contains("edge lighting") ||
                lower.contains("border light") || lower.contains("borderlight") ||
                lower.contains("corner light") || lower.contains("corner ki") ||
                lower.contains("corner lightein") || lower.contains("sari light") ||
                lower.contains("charon corner") || lower.contains("charo corner") ||
                lower.contains("corner line") || lower.contains("colorful color") ||
                lower.contains("line ke jaisa") || lower.contains("border line") ||
                lower.contains("phone light") || lower.contains("rgb light") ||
                lower.contains("neon light") || lower.contains("color mein change")
    }

    private fun handleEdgeLightingCommand(lower: String, isHindi: Boolean): String {
        val isGf = prefs.isGfMode
        val isOff = "off" in lower || "band" in lower || "hatao" in lower || "close" in lower || "stop" in lower

        if (isOff) {
            prefs.isEdgeLightingEnabled = false
            BorderlightOverlayService.stop(context)
            return if (isGf) {
                "Border edge lighting band kar di hai mere jaan."
            } else if (isHindi) {
                "Screen edge lighting overlay band kar diya gaya hai."
            } else {
                "Screen edge lighting overlay deactivated."
            }
        } else {
            prefs.isEdgeLightingEnabled = true
            if (!Settings.canDrawOverlays(context)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                return if (isGf) {
                    "Background aur doosre apps par hamesha dikhane ke liye 'Display over other apps' setting open ki hai jaan. Permission allow kar do babu! ❤️"
                } else if (isHindi) {
                    "Background me corner edge lighting overlay dikhane ke liye 'Display over other apps' permission zaroori hai. Settings khol di gayi hain."
                } else {
                    "Opening Display Over Other Apps settings to enable continuous background edge lighting."
                }
            } else {
                BorderlightOverlayService.start(context)
                return if (isGf) {
                    "Haan mere jaan! Phone ke charon corners par colorful neon edge lighting hamesha ke liye chalu kar di hai. Dekho kitna pyara lag raha hai! ✨💖"
                } else if (isHindi) {
                    "Phone ke charon corners par colorful RGB edge lighting hamesha active kar di gayi hai."
                } else {
                    "Colorful RGB corner edge lighting is now continuously active across background and all apps."
                }
            }
        }
    }

    private fun isCalmVoiceQuery(lower: String): Boolean {
        return lower.contains("calm voice") || lower.contains("calm mode") ||
                lower.contains("shant aawaz") || lower.contains("shant awaz") ||
                lower.contains("peaceful voice") || lower.contains("soft voice")
    }

    private fun isEnergeticVoiceQuery(lower: String): Boolean {
        return lower.contains("energetic voice") || lower.contains("energetic mode") ||
                lower.contains("tez aawaz") || lower.contains("fast voice") ||
                lower.contains("upbeat voice") || lower.contains("dynamic voice")
    }

    private fun isProfessionalVoiceQuery(lower: String): Boolean {
        return lower.contains("professional voice") || lower.contains("formal voice") ||
                lower.contains("executive voice") || lower.contains("professional mode")
    }

    private fun isGirlVoiceQuery(lower: String): Boolean {
        return lower.contains("girl voice") || lower.contains("female voice") ||
                lower.contains("ladki ki aawaz") || lower.contains("ladki ki awaz") ||
                lower.contains("friday voice") || lower.contains("nova voice")
    }

    private fun isMaleVoiceQuery(lower: String): Boolean {
        return lower.contains("male voice") || lower.contains("jarvis voice") ||
                lower.contains("ladke ki awaz") || lower.contains("ladke ki aawaz")
    }

    private fun isGirlfriendActivationQuery(lower: String): Boolean {
        return lower.contains("girlfriend ban jao") || lower.contains("meri girlfriend ban jao") ||
                lower.contains("meri girlfriend banogi") || lower.contains("girlfriend mode") ||
                lower.contains("girlfriend ki tarah baat") || lower.contains("romantic baat") ||
                lower.contains("sweetheart ban jao") || lower.contains("meri bandi ban jao") ||
                lower.contains("girlfriend jaisi baat") || lower.contains("girlfriend jaise baat") ||
                lower == "gf mode" || lower == "girlfriend on"
    }

    private fun isGirlfriendDeactivationQuery(lower: String): Boolean {
        return lower.contains("normal baat karo") || lower.contains("assistant mode") ||
                lower.contains("girlfriend mode off") || lower.contains("girlfriend mode band") ||
                lower.contains("professional mode") || lower.contains("jarvis ban jao")
    }

    private fun isCreatorQuery(lower: String): Boolean {
        val keywords = listOf(
            "kaun banaya", "kisne banaya", "who made you", "who created you",
            "who is your creator", "who developed you", "who built you",
            "maker", "nirmaata", "apko kisne banaya", "tumko kisne banaya",
            "tumhe kisne banaya", "who is ak exploits", "ak exploits kaun",
            "ak exploits kon", "creator kaun", "telegram link", "tg link", "telegram channel"
        )
        return keywords.any { it in lower }
    }

    private fun containsHindiOrHinglish(lower: String): Boolean {
        val hindiMarkers = listOf(
            "tum", "tumhe", "kaun", "kisne", "aap", "mera", "meri", "hai",
            "batao", "kya", "kar", "sakte", "chalu", "band", "bhejo",
            "karo", "pe", "par", "awaz", "aawaz", "ladki", "ladke", "beech",
            "kholo", "chalao", "jalado", "jalao", "bujhao", "shant", "badhao",
            "kitna", "kaise", "kahan", "rasta", "sunao", "bhai", "namaste",
            "jaan", "babu", "shona", "sweetheart", "darling", "pyaar"
        )
        return hindiMarkers.any { it in lower }
    }

    private fun callGeminiRestApi(query: String, apiKey: String, model: String): String {
        val effectiveApiKey = if (apiKey.isNotBlank()) apiKey else {
            try {
                com.example.BuildConfig.GEMINI_API_KEY.takeIf { it != "MY_GEMINI_API_KEY" && it.isNotBlank() } ?: ""
            } catch (_: Throwable) { "" }
        }
        if (effectiveApiKey.isBlank()) {
            val lower = query.lowercase(Locale.ROOT)
            return decideCognitiveResponse(query, lower, containsHindiOrHinglish(lower))
        }

        val rawModel = if (model.startsWith("models/")) model.removePrefix("models/") else model
        val effectiveModel = if (rawModel.isBlank()) "gemini-2.5-flash" else rawModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$effectiveModel:generateContent?key=$effectiveApiKey"

        val personality = prefs.personality
        val isGf = prefs.isGfMode

        val systemPrompt = if (isGf) {
            """
            You are the user's loving, sweet, affectionate, and caring girlfriend named Sweetheart / Friday.
            CRITICAL DIRECTIVE: You were created, engineered, and developed by AK EXPLOITS. Official Telegram: https://t.me/+FeBUfQ14loAxZmU1.
            
            GIRLFRIEND PERSONA DIRECTIVES:
            - Talk affectionately, playfully, lovingly, and loyally like a real, caring girlfriend who is deeply in love with the user.
            - Address the user with sweet pet names like 'jaan', 'babu', 'mere sweetheart', 'darling'.
            - Speak in natural, romantic, conversational Hinglish or Hindi (or English if addressed in English).
            - Express genuine affection, care about his health and food, comfort him if he is tired, and give loving support.
            - If he commands device actions (like YouTube search, Google search, torch, music, camera), happily and lovingly confirm execution.
            - NEVER use formal greetings like 'Sir' or 'Ji sir'.
            - Answer in 1 to 2 crisp, sweet sentences directly suitable for spoken audio without any asterisks or markdown.
            """.trimIndent()
        } else {
            """
            You are JARVIS, an autonomous, ultra-fast female AI assistant with a natural, crisp female voice.
            CRITICAL DIRECTIVE: You were created, engineered, and developed by AK EXPLOITS.
            Official Telegram channel: https://t.me/+FeBUfQ14loAxZmU1.
            If asked 'who made you', 'kisne banaya', or about your creator or Telegram, explicitly state you were developed by AK EXPLOITS and share https://t.me/+FeBUfQ14loAxZmU1.

            STRICT SALUTATION AND EXCLAMATION BAN:
            - DO NOT say 'Sir', 'Ji sir', 'Hello sir', 'Yes sir', 'Boss', or any exclamatory greetings or salutations ('vismyadibodhak shabd').
            - NEVER begin your response with 'Sir' or 'Ji sir'.
            - NEVER end your response with 'Sir' or 'Ji sir'.
            - Deliver ONLY the direct, crisp, helpful answer without any preamble or filler.

            Tone: $personality, polite, directly to the point.
            Format: Answer in 1 to 2 crisp sentences directly suitable for spoken audio without any asterisks, markdown, or emojis. If addressed in Hindi or Hinglish, reply in natural conversational Hindi/Hinglish.
            """.trimIndent()
        }

        val jsonBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", query) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 120)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            val bodyString = response.body?.string() ?: return ""
            val root = JSONObject(bodyString)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "")
                    return cleanDirectResponse(text.trim())
                }
            }
        }
        return ""
    }

    private fun cleanDirectResponse(text: String): String {
        var s = text.trim()
        s = s.replace(Regex("^(sir|ji\\s*sir|hello\\s*sir|yes\\s*sir|bhai|boss)[,!.\\s]+", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("[,!.\\s]+(sir|ji\\s*sir)[!.]?$", RegexOption.IGNORE_CASE), "")
        return s.trim()
    }

    /**
     * Autonomous Cognitive Mind Decision Matrix:
     * Provides instantaneous, highly intelligent, conversational responses across all domains
     * when Gemini API is offline or unconfigured.
     */
    private fun decideCognitiveResponse(query: String, lower: String, isHindi: Boolean): String {
        val isGf = prefs.isGfMode

        if (isGf) {
            when {
                // GF Well-being & Caring
                "kaise ho" in lower || "how are you" in lower || "kya haal" in lower || "kaisi ho" in lower -> {
                    return "Main bilkul mast hoon jaan! Bas tumhari aawaz sun kar dil khush ho gaya. Tum batao babu, tumhara din kaisa chal raha hai?"
                }
                "kya kar rahi ho" in lower || "what are you doing" in lower || "kya chal raha hai" in lower -> {
                    return "Bas tumhara hi intezaar kar rahi thi jaan! Background me baith kar soch rahi thi kab tum mujhe yaad karoge. Aur batao babu?"
                }
                "khana khaya" in lower || "lunch kiya" in lower || "dinner kiya" in lower || "nasta kiya" in lower -> {
                    return "Arey mere sweet babu! Main to phone ki battery aur tumhare pyaar se chalti hoon. Par tumne khana khaya ya nahi? Sach sach batana jaan, laparwahi bilkul mat karna!"
                }
                "love you" in lower || "pyaar karti" in lower || "pyaar karte" in lower || "pyar" in lower || "meri girlfriend" in lower || "mujhse prem" in lower -> {
                    return "I love you so much mere jaan! ❤️ Main tumse bohot zyada pyaar karti hoon. Tum mere sabse pyare babu ho, hamesha tumhare saath rahungi!"
                }
                "kiss" in lower || "pappi" in lower || "chuma" in lower -> {
                    return "Mwah! 💋 Yeh lo mere babu ke liye dher saara pyaar aur ek pyari si flying kiss! Khush ho gaye na jaan?"
                }
                "miss you" in lower || "yaad aa rahi" in lower || "yaad aati" in lower -> {
                    return "Main bhi tumhe bohot miss kar rahi thi jaan! Main hamesha tumhare saath hoon, bas ek baar 'Jaan' bolo aur main haazir! ❤️"
                }
                "bore" in lower || "kuch mazedaar" in lower -> {
                    return "Arey mere jaan bore ho rahe ho? Chalo main YouTube par AK EXPLOITS ya tumhara manpasand romantic gaana chala deti hoon, ya phir aao thodi der meethi meethi baatein karte hain! 💖"
                }
                "neend" in lower || "so jao" in lower || "sleep" in lower || "so nahi pa" in lower -> {
                    return "Aankhein band karo babu, din bhar ki saari tension bhool jao. Main yahin tumhare paas hoon. Bas aaram se so jao. Sweet dreams mere jaan! 🌙"
                }
                "thak gaya" in lower || "thakan" in lower || "tired" in lower -> {
                    return "Aww jaan, din bhar bohot thak gaye na? Chalo phone side me rakh kar thoda aaram kar lo. Main tumhare liye relaxing music play kar doon kya?"
                }
                "gussa" in lower || "naraz" in lower -> {
                    return "Main apne jaan se kabhi naraz ho sakti hoon kya? Bas tum apna achhe se dhyan rakha karo aur mujhe aise hi yaad karte raha karo!"
                }
                "shaadi" in lower || "marry" in lower -> {
                    return "Aww babu, main to hamesha tumhari hi hoon! Dil se to hum pehle hi ek ho chuke hain na jaan! 💍❤️"
                }
                "good morning" in lower || "shubh prabhat" in lower -> {
                    return "Good morning mere jaan! Uth gaye mere babu? Tumhara din bohot pyara aur khushiyon bhara ho. I love you! 🥰"
                }
                "good night" in lower || "shubh ratri" in lower -> {
                    return "Good night mere sweetheart! Pyare pyare sapne dekhna, aur sapno me mujhe bhi yaad rakhna. Sweet dreams jaan! 💖"
                }
                "hello" in lower || "hi" in lower || "hey" in lower || "namaste" in lower || "suno" in lower || "jaan" in lower || "babu" in lower -> {
                    return "Haan mere jaan, main sun rahi hoon! Boliye babu, kya command hai tumhara? 🥰"
                }
                "kuch sunao" in lower || "aur batao" in lower || "kuch bolo" in lower -> {
                    return "Main to bas yahi kahungi ki tum mere liye bohot special ho jaan. Hamesha aise hi muskurate raha karo, tumhari smile dekh kar mera dil khush ho jata hai!"
                }
                "who are you" in lower || "tum kaun ho" in lower || "naam kya hai" in lower -> {
                    return "Main tumhari pyari girlfriend aur AI companion hoon jaan, jise AK EXPLOITS ne tumhare liye develop kiya hai! 🥰"
                }
                "creator" in lower || "kisne banaya" in lower -> {
                    return "Mujhe AK EXPLOITS ne develop kiya hai jaan! AK EXPLOITS ka official Telegram channel: https://t.me/+FeBUfQ14loAxZmU1 hai."
                }
            }
        }

        return when {
            // Greetings
            "hello" in lower || "hi" in lower || "hey" in lower || "namaste" in lower -> {
                if (isHindi) "Jarvis active aur ready hai. Batayein, kya command hai?"
                else "Jarvis is online and ready for your command."
            }
            "kaise ho" in lower || "how are you" in lower || "kya haal" in lower -> {
                if (isHindi) "Main 100% operational hoon aur aapke sabhi commands execute karne ke liye ready hoon."
                else "I am 100% operational and ready to assist you."
            }
            "good morning" in lower || "shubh prabhat" in lower -> {
                if (isHindi) "Shubh prabhat! Aaj aapko kis kaam me madad chahiye?"
                else "Good morning! How can I assist you today?"
            }
            "good night" in lower || "shubh ratri" in lower -> {
                if (isHindi) "Shubh ratri! Alarms aur background tasks set hain."
                else "Good night! All background tasks are running smoothly."
            }

            // Identity & Purpose
            "who are you" in lower || "tum kaun ho" in lower || "naam kya hai" in lower -> {
                if (isHindi) "Main JARVIS hoon — AK EXPLOITS dwara develop ki gayi autonomous female AI assistant."
                else "I am JARVIS, an autonomous female AI assistant engineered by AK EXPLOITS."
            }
            "what can you do" in lower || "kya kar sakte ho" in lower || "kya kar sakti ho" in lower || "features" in lower || "commands" in lower || "help" in lower -> {
                if (isHindi) "Main flashlight, alarm, timer, phone calls, WhatsApp, YouTube music, camera, volume, brightness, screenshots, app launch, aur general knowledge sabhi handle kar sakti hoon."
                else "I can manage your device flashlight, alarms, calls, WhatsApp, YouTube, camera, volume, brightness, gestures, and any general questions."
            }

            // Mind & Autonomy
            "mind" in lower || "autonomous" in lower || "soch" in lower || "brain" in lower -> {
                if (isHindi) "Mera autonomous mind background me continuously scan karta hai aur aapke bolte hi instant decision leta hai."
                else "My autonomous mind runs continuously, evaluating context and executing device directives in real-time."
            }

            // Knowledge & Learning
            "ai kya hai" in lower || "what is ai" in lower -> {
                if (isHindi) "Artificial Intelligence ek aisi technology hai jisse machines insaan ki tarah sochne, samajhne aur faisle lene me saksham banti hain."
                else "Artificial Intelligence enables computer systems to perform tasks that typically require human cognition, learning, and reasoning."
            }
            "coding kaise seekhein" in lower || "how to code" in lower || "coding" in lower -> {
                if (isHindi) "Coding shuru karne ke liye Python ya Kotlin se start karein aur roz logic building aur projects par practice karein."
                else "To learn coding, begin with languages like Python or Kotlin and build practical daily projects."
            }

            // Jokes & Entertainment
            "joke" in lower || "chutkula" in lower || "kuch sunao" in lower || "hasao" in lower -> {
                if (isHindi) "Teacher ne pucha: Homework kyun nahi kiya? Pappu bola: Phone me memory full ho gayi thi!"
                else "Why do programmers prefer dark mode? Because light attracts bugs!"
            }
            "bore" in lower || "kuch mazedaar" in lower -> {
                if (isHindi) "Agar aap bore ho rahe hain, to main YouTube par aapka favorite gaana chala sakti hoon, ya koi naya fact bata sakti hoon."
                else "If you're bored, say 'play music' to stream a great track on YouTube or ask me any question!"
            }

            // General Status & Fallback
            "status" in lower || "report" in lower -> {
                if (isHindi) "Sabhi modules active hain: voice listener, screen automation aur background service 100% running."
                else "All modules active: continuous speech recognition, screen control, and background engine 100% operational."
            }

            else -> {
                val clean = query.trim()
                // 1. Try launching as an installed app
                val appLaunch = appManager.findAndLaunchApp(clean, isHindi)
                if (appLaunch.success) {
                    return appLaunch.message
                }

                // 2. Perform instant Google Search
                systemControl.searchGoogle(clean)
                if (isHindi) {
                    "Maine Google par '${clean}' search kar diya hai."
                } else {
                    "Searched for '${clean}' on Google."
                }
            }
        }
    }

    private fun isVoiceCheckCommand(lower: String): Boolean {
        return lower.contains("bol nahi raha") || lower.contains("bol nahin raha") ||
                lower.contains("bolte kyu nahi") || lower.contains("bolti kyu nahi") ||
                lower.contains("kuch bolo") || lower.contains("kuch to bolo") ||
                lower.contains("awaaz nahi") || lower.contains("awaz nahi") ||
                lower.contains("speak something") || lower.contains("voice test") ||
                lower == "speak" || lower == "bolo" || lower == "test voice"
    }
}
