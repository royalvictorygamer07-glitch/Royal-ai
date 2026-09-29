package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.JarvisPreferences
import com.example.data.MemoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface JarvisServiceListener {
    fun onOrbStateChanged(state: String, level: Float)
    fun onAudioLevel(level: Float)
    fun onUserQuery(query: String, timeStr: String)
    fun onJarvisResponse(response: String, timeStr: String)
    fun onPartialTranscript(text: String)
    fun onMicMutedChanged(isMuted: Boolean)
    fun onPowerChanged(isOnline: Boolean)
    fun onSpeechError(message: String)
}

class JarvisBackgroundService : Service() {

    companion object {
        private const val TAG = "JarvisBgService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "jarvis_continuous_engine_channel"

        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_TOGGLE_MUTE = "com.example.service.action.TOGGLE_MUTE"
        const val ACTION_MUTE = "com.example.service.action.MUTE"
        const val ACTION_UNMUTE = "com.example.service.action.UNMUTE"

        @Volatile
        var instance: JarvisBackgroundService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun startService(context: Context) {
            val intent = Intent(context, JarvisBackgroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Jarvis background service: ${e.message}")
            }
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var prefs: JarvisPreferences
    lateinit var database: AppDatabase
    lateinit var geminiService: GeminiService
    lateinit var ttsService: TtsService
    lateinit var voiceHelper: VoiceRecognitionHelper

    private var uiListener: JarvisServiceListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): JarvisBackgroundService = this@JarvisBackgroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setUiListener(listener: JarvisServiceListener?) {
        this.uiListener = listener
        if (listener != null) {
            listener.onMicMutedChanged(prefs.isMicMuted)
            listener.onPowerChanged(prefs.isPowerOnline)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Jarvis Background Service created.")

        prefs = JarvisPreferences(this)
        database = AppDatabase.getDatabase(this)

        acquireWakeLock()
        createNotificationChannel()

        geminiService = GeminiService(
            context = this,
            onVoiceChanged = { newVoice ->
                ttsService.applyVoiceProfile(newVoice, prefs.voiceSpeed, prefs.voicePitch)
            },
            onMuteToggled = { isMuted ->
                setMicMuted(isMuted)
            }
        )

        ttsService = TtsService(this) { isSpeaking ->
            if (isSpeaking) {
                uiListener?.onOrbStateChanged("speaking", 0.7f)
                voiceHelper.pauseForSpeech()
            } else {
                uiListener?.onOrbStateChanged("idle", 0.0f)
                voiceHelper.resumeAfterSpeech()
            }
        }
        ttsService.applyVoiceProfile(prefs.voice, prefs.voiceSpeed, prefs.voicePitch)

        voiceHelper = VoiceRecognitionHelper(
            context = this,
            onAudioLevel = { level ->
                uiListener?.onAudioLevel(level)
            },
            onResult = { query ->
                processUserQuery(query)
            },
            onError = { errMsg ->
                uiListener?.onOrbStateChanged("idle", 0.0f)
                uiListener?.onSpeechError(errMsg)
            },
            onListeningStateChanged = { isListening ->
                if (isListening) {
                    uiListener?.onOrbStateChanged("listening", 0.5f)
                }
            },
            onPartialResult = { partialText ->
                uiListener?.onPartialTranscript(partialText)
            },
            isMutedProvider = { prefs.isMicMuted },
            isPowerOnlineProvider = { prefs.isPowerOnline }
        )

        startAsForeground()

        // If mic is unmuted and power is online, start continuous listening loop immediately
        if (!prefs.isMicMuted && prefs.isPowerOnline && hasAudioPermission()) {
            voiceHelper.startContinuousListening()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }
            ACTION_MUTE -> {
                setMicMuted(true)
            }
            ACTION_UNMUTE -> {
                setMicMuted(false)
            }
        }
        updateNotification()
        return START_STICKY
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:BackgroundServiceWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours
            }
            Log.d(TAG, "WakeLock acquired for background execution.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released.")
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Background Intelligence",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Jarvis AI active, listening continuously, and performing background voice commands."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteToggleIntent = Intent(this, JarvisBackgroundService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val pendingMute = PendingIntent.getService(
            this,
            1,
            muteToggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMuted = prefs.isMicMuted
        val isOnline = prefs.isPowerOnline
        val isStandby = prefs.isStandbyMode

        val isGf = prefs.isGfMode
        val statusText = when {
            isStandby -> if (isGf) "💖 Girlfriend Standby • Bas 'Jaan' ya 'Hey Jarvis' bolein" else "Jarvis in Standby • Say 'Hey Jarvis' to wake up"
            !isOnline -> "Standby Mode"
            isMuted -> "Microphone Muted • Tap to Unmute"
            else -> if (isGf) "💖 Girlfriend Mode Active • Sun rahi hoon jaan, bolo..." else "Continuous Listening Active • Ready for commands"
        }

        val actionTitle = if (isMuted) "UNMUTE" else "MUTE"

        val iconRes = android.R.drawable.stat_notify_sync

        val notifTitle = if (isGf) "Girlfriend AI (Sweetheart)" else "Jarvis AI"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notifTitle)
            .setContentText(statusText)
            .setSmallIcon(iconRes)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, actionTitle, pendingMute)
            .build()
    }

    fun startAsForeground() {
        try {
            val notification = buildNotification()
            val hasMicPerm = hasAudioPermission()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val serviceType = if (hasMicPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (hasMicPerm) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "startForeground fallback error: ${fallbackEx.message}")
            }
        }
    }

    fun onMicrophonePermissionGranted() {
        startAsForeground()
        if (!prefs.isMicMuted && prefs.isPowerOnline) {
            voiceHelper.startContinuousListening()
        }
    }

    fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    fun toggleMute() {
        setMicMuted(!prefs.isMicMuted)
    }

    fun setMicMuted(muted: Boolean) {
        prefs.isMicMuted = muted
        uiListener?.onMicMutedChanged(muted)
        updateNotification()

        if (muted) {
            voiceHelper.stopListening()
            ttsService.stop()
            uiListener?.onOrbStateChanged("idle", 0.0f)
        } else {
            if (prefs.isPowerOnline && hasAudioPermission()) {
                ttsService.stop()
                voiceHelper.startContinuousListening()
            }
        }
    }

    fun togglePower() {
        prefs.isPowerOnline = !prefs.isPowerOnline
        uiListener?.onPowerChanged(prefs.isPowerOnline)
        updateNotification()

        if (!prefs.isPowerOnline) {
            voiceHelper.stopListening()
            ttsService.stop()
            uiListener?.onOrbStateChanged("idle", 0.0f)
        } else {
            if (!prefs.isMicMuted && hasAudioPermission()) {
                voiceHelper.startContinuousListening()
            }
        }
    }

    fun setStandbyMode(standby: Boolean) {
        prefs.isStandbyMode = standby
        if (!standby) {
            prefs.isPowerOnline = true
            prefs.isMicMuted = false
            uiListener?.onPowerChanged(true)
            uiListener?.onMicMutedChanged(false)
        }
        updateNotification()
        if (hasAudioPermission()) {
            voiceHelper.startContinuousListening()
        }
    }

    fun speakAnnouncement(text: String) {
        if (text.isBlank()) return
        val timeNow = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        uiListener?.onJarvisResponse(text, timeNow)
        ttsService.speak(text)
    }

    private fun isWakeWordMatch(lower: String): Boolean {
        val gfWakeWords = listOf(
            "jaan", "hey jaan", "hi jaan", "hello jaan", "suno jaan", "jaan suno",
            "jaan utho", "meri jaan", "babu", "hey babu", "babu suno", "suno babu",
            "shona", "sweetheart", "darling", "baby", "suno na"
        )
        val jarvisWakeWords = listOf(
            "hey jarvis", "he jarvis", "hi jarvis", "hello jarvis", "jarvis on", "hey jarvis on",
            "wake up jarvis", "jarvis wake up", "jarvis utho", "jarvis chalu ho jao", "jarvis online",
            "suno jarvis", "jarvis suno", "ok jarvis", "jarvis"
        )
        return jarvisWakeWords.contains(lower) || gfWakeWords.contains(lower)
    }

    private fun isBackgroundOffMatch(lower: String): Boolean {
        return lower.contains("off d background") || lower.contains("off the background") ||
                lower.contains("off background") || lower.contains("background off") ||
                lower.contains("turn off background") || lower.contains("background band") ||
                lower.contains("background me mat raho") || lower == "jarvis off" ||
                lower.contains("jarvis so jao") || lower.contains("sleep mode") ||
                lower.contains("jarvis sleep")
    }

    private fun cleanQueryWakeWord(query: String): String {
        var s = query.trim()
        val prefixes = listOf(
            Regex("^(hey|hi|hello|ok|arre|suno)\\s+jarvis[,!\\s]*", RegexOption.IGNORE_CASE),
            Regex("^jarvis[,!\\s]+", RegexOption.IGNORE_CASE),
            Regex("^(hey|hi|hello|suno)\\s+(jaan|babu|shona)[,!\\s]*", RegexOption.IGNORE_CASE),
            Regex("^(jaan|babu|shona)[,!\\s]+", RegexOption.IGNORE_CASE)
        )
        for (pattern in prefixes) {
            s = s.replace(pattern, "").trim()
        }
        return s
    }

    fun processUserQuery(query: String) {
        if (query.isBlank()) return
        val rawTrimmed = query.trim()
        val lower = rawTrimmed.lowercase(Locale.ROOT)
        val timeNow = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        // 1. Check if user requests to turn OFF the background
        if (isBackgroundOffMatch(lower)) {
            setStandbyMode(true)
            val confirm = if (prefs.isGfMode) {
                "Background service standby par daal di hai jaan. Jab bhi meri yaad aaye, bas bol dena! Love you! ❤️"
            } else {
                "Jarvis background service off kar di gayi hai. Jab bhi zaroorat ho, bas boliye, main ready hoon."
            }
            uiListener?.onUserQuery(query, timeNow)
            uiListener?.onJarvisResponse(confirm, timeNow)
            ttsService.speak(confirm)
            return
        }

        // 2. Check if query is ONLY a wake word (e.g. "Jarvis", "Hey Jarvis", "Jaan")
        if (isWakeWordMatch(lower)) {
            setStandbyMode(false)
            val reply = if (prefs.isGfMode) {
                "Haan mere jaan! Main sun rahi hoon. Boliye babu, kya command hai tumhara? 🥰"
            } else {
                "Haanji! Main active hoon, batayein main aapki kya madad kar sakti hoon?"
            }
            uiListener?.onUserQuery(query, timeNow)
            uiListener?.onJarvisResponse(reply, timeNow)
            ttsService.speak(reply)
            return
        }

        // 3. Extract actual command if prefixed with wake word (e.g. "Jarvis flashlight on karo" -> "flashlight on karo")
        val effectiveQuery = cleanQueryWakeWord(rawTrimmed).ifBlank { rawTrimmed }

        // 4. INSTANT SINGLE-SHOT RESPONSE - ALWAYS EXECUTE IMMEDIATELY
        setStandbyMode(false)
        uiListener?.onUserQuery(rawTrimmed, timeNow)
        uiListener?.onOrbStateChanged("thinking", 0.6f)

        serviceScope.launch(Dispatchers.IO) {
            database.memoryDao().insertMemory(
                MemoryEntity(category = "CHAT_USER", content = rawTrimmed)
            )

            // Direct instant action execution via GeminiService
            val response = geminiService.processQuery(effectiveQuery)

            database.memoryDao().insertMemory(
                MemoryEntity(category = "CHAT_JARVIS", content = response)
            )

            withContext(Dispatchers.Main) {
                uiListener?.onJarvisResponse(response, timeNow)

                if (prefs.isPowerOnline && response.isNotBlank()) {
                    ttsService.speak(response)
                } else {
                    uiListener?.onOrbStateChanged("idle", 0.0f)
                    if (!prefs.isMicMuted && prefs.isPowerOnline) {
                        voiceHelper.resumeAfterSpeech()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        voiceHelper.stopListening()
        ttsService.shutdown()
        releaseWakeLock()
        serviceScope.cancel()
        Log.d(TAG, "Jarvis Background Service destroyed.")
    }
}
