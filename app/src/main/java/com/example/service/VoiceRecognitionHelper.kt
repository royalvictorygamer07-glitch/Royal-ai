package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceRecognitionHelper(
    private val context: Context,
    private val onAudioLevel: (Float) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
    private val onPartialResult: ((String) -> Unit)? = null,
    private val isMutedProvider: () -> Boolean,
    private val isPowerOnlineProvider: () -> Boolean = { true },
    private val onFallbackRequested: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "VoiceRecognitionHelper"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isContinuousListening = false
    private var isListening = false
    private var isPausedForTts = false
    private var isStarting = false
    private var lastRecognizedPartial: String = ""
    private var endOfSpeechFallbackRunnable: Runnable? = null

    private val restartRunnable = Runnable {
        if (isContinuousListening && !isMutedProvider() && isPowerOnlineProvider() && !isPausedForTts) {
            startListeningInternal()
        }
    }

    fun isRecognitionAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            false
        }
    }

    fun startContinuousListening() {
        isContinuousListening = true
        isPausedForTts = false
        mainHandler.removeCallbacks(restartRunnable)
        startListeningInternal()
    }

    fun startListening() {
        startContinuousListening()
    }

    fun pauseForSpeech() {
        isPausedForTts = true
        mainHandler.removeCallbacks(restartRunnable)
        stopRecognizerCleanly()
        onListeningStateChanged(false)
    }

    fun resumeAfterSpeech() {
        isPausedForTts = false
        if (isContinuousListening && !isMutedProvider() && isPowerOnlineProvider()) {
            scheduleRestart(200L)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartRunnable)
        if (isContinuousListening && !isMutedProvider() && isPowerOnlineProvider() && !isPausedForTts) {
            mainHandler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun stopRecognizerCleanly() {
        endOfSpeechFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false
        isStarting = false
    }

    private fun cancelSession() {
        endOfSpeechFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
        isStarting = false
    }

    private fun startListeningInternal() {
        mainHandler.post {
            if (isMutedProvider() || !isPowerOnlineProvider() || isPausedForTts) {
                isListening = false
                onListeningStateChanged(false)
                return@post
            }

            if (isStarting || isListening) return@post
            isStarting = true

            try {
                if (!isRecognitionAvailable()) {
                    Log.w(TAG, "SpeechRecognizer not directly available on device")
                    isStarting = false
                    isContinuousListening = false
                    onError("Speech recognition not available on this system.")
                    return@post
                }

                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                isStarting = false
                                isListening = true
                                onListeningStateChanged(true)
                            }

                            override fun onBeginningOfSpeech() {
                                isListening = true
                            }

                            override fun onRmsChanged(rmsdB: Float) {
                                val normalized = ((rmsdB + 2f) / 10f).coerceIn(0f, 1f)
                                onAudioLevel(normalized)
                            }

                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {
                                isListening = false
                                onListeningStateChanged(false)
                                endOfSpeechFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                                val fallback = Runnable {
                                    if (lastRecognizedPartial.isNotBlank()) {
                                        val text = lastRecognizedPartial.trim()
                                        lastRecognizedPartial = ""
                                        cancelSession()
                                        onResult(text)
                                    }
                                }
                                endOfSpeechFallbackRunnable = fallback
                                mainHandler.postDelayed(fallback, 350L)
                            }

                            override fun onError(error: Int) {
                                endOfSpeechFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                                isStarting = false
                                isListening = false
                                onListeningStateChanged(false)

                                Log.d(TAG, "SpeechRecognizer error: $error")

                                if (lastRecognizedPartial.isNotBlank()) {
                                    val text = lastRecognizedPartial.trim()
                                    lastRecognizedPartial = ""
                                    cancelSession()
                                    onResult(text)
                                    return
                                }

                                when (error) {
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                                    SpeechRecognizer.ERROR_NO_MATCH -> {
                                        cancelSession()
                                        scheduleRestart(150L)
                                    }
                                    SpeechRecognizer.ERROR_AUDIO,
                                    SpeechRecognizer.ERROR_CLIENT,
                                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                        stopRecognizerCleanly()
                                        scheduleRestart(250L)
                                    }
                                    SpeechRecognizer.ERROR_NETWORK,
                                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                                        cancelSession()
                                        scheduleRestart(500L)
                                    }
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                        stopRecognizerCleanly()
                                        isContinuousListening = false
                                        onError("Microphone permission required")
                                    }
                                    else -> {
                                        cancelSession()
                                        scheduleRestart(250L)
                                    }
                                }
                            }

                            override fun onResults(results: Bundle?) {
                                endOfSpeechFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                                isStarting = false
                                isListening = false
                                onListeningStateChanged(false)

                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val text = matches?.firstOrNull()?.trim()?.ifBlank { null } ?: lastRecognizedPartial.trim()
                                lastRecognizedPartial = ""

                                cancelSession()

                                if (text.isNotBlank()) {
                                    onResult(text)
                                } else {
                                    scheduleRestart(150L)
                                }
                            }

                            override fun onPartialResults(partialResults: Bundle?) {
                                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val partialText = matches?.firstOrNull()?.trim() ?: ""
                                if (partialText.isNotBlank()) {
                                    lastRecognizedPartial = partialText
                                    onPartialResult?.invoke(partialText)
                                }
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "hi", "en-US", "hi_IN"))
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 150L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 280L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 180L)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isStarting = false
                isListening = false
                onListeningStateChanged(false)
                Log.e(TAG, "Exception in speech startListening: ${e.message}")
                stopRecognizerCleanly()
                scheduleRestart(500L)
            }
        }
    }

    fun stopListening() {
        isContinuousListening = false
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.post {
            stopRecognizerCleanly()
            onListeningStateChanged(false)
        }
    }

    fun isCurrentlyListening(): Boolean = isListening
}
