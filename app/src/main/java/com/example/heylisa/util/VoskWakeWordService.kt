package com.example.heylisa.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.example.heylisa.R
import com.example.heylisa.constant.Noisy
import com.example.heylisa.service.CloudTtsService
import com.example.heylisa.voice.VoiceInputActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import kotlin.math.max

class VoskWakeWordService : Service() {

    private lateinit var smallModel: Model  // Only for wake word detection
    private var wakeWordRecognizer: Recognizer? = null
    private var androidSpeechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null

    @Volatile private var isListening = false
    @Volatile private var isShuttingDown = false
    @Volatile private var speechSessionCancelled = false
    @Volatile private var isSessionActive = false

    // TTS and Processing state management
    @Volatile private var isTtsSpeaking = false
    @Volatile private var isProcessingResult = false
    @Volatile private var sessionPaused = false

    private val recognizerLock = Any()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var speechRecognitionJob: Job? = null
    private var wakeWordJob: Job? = null

    // Wake word detection timing
    private var lastSpeechTime = System.currentTimeMillis()
    private val WAKE_WORD_TIMEOUT = 10000L // 10 seconds timeout

    // Android Speech Recognition state
    private var speechRecognitionStartTime = 0L
    private val MAX_SPEECH_RECOGNITION_TIME = 60000L // 60 seconds max
    private val MEANINGFUL_SILENCE_TIMEOUT = 8000L // 8 seconds
    private var lastResultTime = 0L

    // Combined broadcast receiver for all states
    private val stateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // TTS State Management
                CloudTtsService.TTS_STARTED -> {
                    Log.d("HeyLisa", "🔊 TTS started - pausing speech recognition")
                    isTtsSpeaking = true
                    sessionPaused = true
                    stopAndroidSpeechRecognizer()
                }

                CloudTtsService.TTS_FINISHED, CloudTtsService.TTS_ERROR -> {
                    Log.d("HeyLisa", "🔇 TTS finished")
                    isTtsSpeaking = false
                }

                // Backend Processing State Management
                "com.example.heylisa.PROCESSING_STARTED" -> {
                    Log.d("HeyLisa", "🔄 Result processing started")
                    isProcessingResult = true
                    sessionPaused = true
                    stopAndroidSpeechRecognizer()
                }

                "com.example.heylisa.PROCESSING_COMPLETE" -> {
                    Log.d("HeyLisa", "📥 PROCESSING_COMPLETE received")
                    isProcessingResult = false

                    if (!isTtsSpeaking && !isShuttingDown) {
                        if (isSessionActive) {
                            Log.d("HeyLisa", "🔄 Session active - resuming existing session")
                            serviceScope.launch {
                                delay(500)
                                resumeSpeechSession()
                            }
                        } else {
                            Log.d("HeyLisa", "🔄 Session ended - starting new session")
                            serviceScope.launch {
                                delay(500)
                                startAndroidSpeechRecognition()
                            }
                        }
                    }
                }

                // Legacy restore receiver
                "com.example.heylisa.RESTORE_WAKE_WORD" -> {
                    if (isShuttingDown || !::smallModel.isInitialized) {
                        Log.w("HeyLisa", "⚠️ Ignored restart — service is shutting down or model is not ready")
                        return
                    }

                    if (isListening) {
                        Log.w("HeyLisa", "🛑 Already listening — skipping restart")
                        return
                    }

                    Log.d("HeyLisa", "VoiceInputActivity destroyed — restoring wake word detection")
                    speechSessionCancelled = true

                    stopListening()
                    stopAndroidSpeechRecognizer()

                    serviceScope.launch {
                        delay(500)
                        startWakeWordDetection()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun onCreate() {
        super.onCreate()

        // Register all broadcast receivers
        val stateFilter = IntentFilter().apply {
            addAction(CloudTtsService.TTS_STARTED)
            addAction(CloudTtsService.TTS_FINISHED)
            addAction(CloudTtsService.TTS_ERROR)
            addAction("com.example.heylisa.PROCESSING_STARTED")
            addAction("com.example.heylisa.PROCESSING_COMPLETE")
            addAction("com.example.heylisa.RESTORE_WAKE_WORD")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, stateFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(stateReceiver, stateFilter)
        }

        checkBatteryOptimization()
        createNotificationChannel()
        createWakeWordAlertChannel()
        startForeground(1, createNotification("Hey Lisa is listening..."))

        serviceScope.launch {
            if (!checkPermissions()) {
                Log.e("HeyLisa", "Required permissions not granted")
                stopSelfSafely()
                return@launch
            }

            sendBroadcast(Intent("com.example.heylisa.MODEL_INIT_STARTED"))
            try {
                // Initialize only small model for wake word detection
                if (initWakeWordModel()) {
                    sendBroadcast(Intent("com.example.heylisa.MODEL_INIT_FINISHED"))
                    startWakeWordDetection()
                } else {
                    throw Exception("Failed to initialize wake word model")
                }
            } catch (e: Exception) {
                Log.e("HeyLisa", "Model init failed", e)
                sendBroadcast(Intent("com.example.heylisa.MODEL_INIT_FAILED").apply {
                    putExtra("error", e.message)
                })
                stopSelfSafely()
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startWakeWordDetection() {
        Log.d("HeyLisa", "🚀 Starting wake word detection with SMALL model...")

        if (isListening || isShuttingDown || !::smallModel.isInitialized || isTtsSpeaking || isProcessingResult) {
            return
        }

        synchronized(recognizerLock) {
            try {
                wakeWordRecognizer?.close()
                wakeWordRecognizer = Recognizer(smallModel, 16000.0f)
            } catch (e: Exception) {
                Log.e("HeyLisa", "Failed to init wake word recognizer with small model", e)
                return
            }
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("HeyLisa", "AudioRecord init failed")
            stopSelf()
            return
        }

        val buffer = ByteArray(bufferSize)
        audioRecord?.startRecording()
        isListening = true
        sessionPaused = false

        wakeWordJob?.cancel()
        wakeWordJob = serviceScope.launch {
            var consecutiveZeroReads = 0
            val maxZeroReads = 50
            var wakeWordBuffer = StringBuilder()

            while (isListening && !isShuttingDown) {
                try {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)

                    if (read == 0) {
                        consecutiveZeroReads++
                        if (consecutiveZeroReads > maxZeroReads) {
                            Log.d("HeyLisa", "🛑 AudioRecord stall detected - restarting")
                            restartAudioRecordAndRecognizer()
                            break
                        }
                        delay(10)
                        continue
                    } else {
                        consecutiveZeroReads = 0
                    }

                    if (read > 0) {
                        synchronized(recognizerLock) {
                            wakeWordRecognizer?.acceptWaveForm(buffer, read)
                        }

                        val partial = synchronized(recognizerLock) {
                            wakeWordRecognizer?.partialResult
                        }

                        if (!partial.isNullOrEmpty()) {
                            val currentText = Regex("\"partial\"\\s*:\\s*\"(.*?)\"")
                                .find(partial)?.groupValues?.getOrNull(1)
                                ?.lowercase()?.trim()

                            if (!currentText.isNullOrEmpty()) {
                                val currentTime = System.currentTimeMillis()

                                if (currentTime - lastSpeechTime > WAKE_WORD_TIMEOUT) {
                                    Log.d("HeyLisa", "🔄 No wake word detected for 10 seconds, resetting recognizer")
                                    synchronized(recognizerLock) {
                                        wakeWordRecognizer?.close()
                                        wakeWordRecognizer = Recognizer(smallModel, 16000.0f)
                                    }
                                    wakeWordBuffer.clear()
                                }

                                lastSpeechTime = currentTime
                                wakeWordBuffer.append(" ").append(currentText)

                                if (wakeWordBuffer.length > 50) {
                                    wakeWordBuffer.delete(0, wakeWordBuffer.length - 50)
                                }

                                if (hasMinimumConfidence(partial) && checkForWakeWordSmallModel(partial)) {
                                    Log.d("HeyLisa", "🔍 WAKE WORD FOUND! Stopping wake word detection immediately")

                                    isListening = false

                                    delay(100)
                                    if (validateWakeWordWithFinalResult()) {
                                        Log.i("HeyLisa", "✅ Wake word CONFIRMED!")

                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                this@VoskWakeWordService,
                                                "Hey Lisa detected!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }

                                        stopListening()

                                        serviceScope.launch {
                                            delay(500)

                                            // **ADD THIS: Launch VoiceInputActivity**
                                            launchVoiceInputActivity()

                                            sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                                                putExtra("state", "wake_word_detected")
                                            })
                                            showWakeWordDetectedNotification()
                                            startAndroidSpeechRecognition()
                                        }
                                        break
                                    } else {
                                        isListening = true
                                        Log.d("HeyLisa", "❌ Final validation failed, continuing to listen")
                                    }
                                }
                            }
                        }
                    }
                    delay(10)
                } catch (e: Exception) {
                    Log.e("HeyLisa", "Wake word detection error", e)
                    break
                }
            }

            Log.d("HeyLisa", "🏁 Wake word detection loop ended")
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAndroidSpeechRecognition() {
        Log.d("HeyLisa", "🎤 Starting Android Speech Recognition...")

        isSessionActive = true
        speechRecognitionStartTime = System.currentTimeMillis()
        lastResultTime = System.currentTimeMillis()

        speechRecognitionJob?.cancel()
        speechRecognitionJob = serviceScope.launch {
            withContext(Dispatchers.Main) {
                initializeAndroidSpeechRecognizer()
                startSpeechRecognition()
            }

            // Monitor session timeout
            while (isSessionActive && !isShuttingDown) {
                val currentTime = System.currentTimeMillis()
                val sessionDuration = currentTime - speechRecognitionStartTime
                val timeSinceLastResult = currentTime - lastResultTime

                // End session if 60 seconds elapsed or 8 seconds of silence
                if (sessionDuration >= MAX_SPEECH_RECOGNITION_TIME ||
                    (timeSinceLastResult >= MEANINGFUL_SILENCE_TIMEOUT && !sessionPaused)) {

                    Log.d("HeyLisa", "🏁 Session timeout - ending speech recognition")
                    endSpeechSession()
                    break
                }

                delay(1000) // Check every second
            }
        }
    }

    private fun launchVoiceInputActivity() {
        try {
            val intent = Intent(this, VoiceInputActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION

                // Optional: Add extra data if needed
                putExtra("launched_from_wake_word", true)
                putExtra("wake_word_timestamp", System.currentTimeMillis())
            }

            startActivity(intent)
            Log.d("HeyLisa", "🚀 Launched VoiceInputActivity from wake word detection")

        } catch (e: Exception) {
            Log.e("HeyLisa", "Failed to launch VoiceInputActivity", e)

            // Fallback: Show notification to manually open
            showWakeWordDetectedNotification()
        }
    }

    private fun initializeAndroidSpeechRecognizer() {
        if (androidSpeechRecognizer != null) {
            androidSpeechRecognizer?.destroy()
        }

        androidSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        androidSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("HeyLisa", "🎤 Android Speech Recognizer ready for speech")
                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                    putExtra("state", "speech_recognition_started")
                })
            }

            override fun onBeginningOfSpeech() {
                Log.d("HeyLisa", "🗣️ Beginning of speech detected")
                lastResultTime = System.currentTimeMillis()
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Audio level monitoring - can be used for UI feedback
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Raw audio buffer - not typically used
            }

            override fun onEndOfSpeech() {
                Log.d("HeyLisa", "🔚 End of speech detected")
            }

            @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech input matched"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error"
                }

                Log.e("HeyLisa", "Speech recognition error: $errorMessage")

                // Handle specific errors
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Restart recognition if no speech detected
                        if (isSessionActive && !sessionPaused) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isSessionActive && !sessionPaused) {
                                    startSpeechRecognition()
                                }
                            }, 1000)
                        }
                    }
                    else -> {
                        // For other errors, end the session
                        endSpeechSession()
                    }
                }
            }

            @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0].lowercase().trim()
                    val confidenceScore = confidence?.getOrNull(0) ?: 0.0f

                    Log.d("HeyLisa", "🎯 Recognized: '$recognizedText' (confidence: $confidenceScore)")

                    // Filter out noisy words
                    val words = recognizedText.split(" ")
                    val meaningfulWords = words.filterNot { it in Noisy.noisyWords }

                    if (meaningfulWords.isNotEmpty()) {
                        val result = meaningfulWords.joinToString(" ")


                        // Send result for processing
                        sendBroadcast(Intent("com.example.heylisa.RECOGNIZED_TEXT").apply {
                            putExtra("result", result)
                        })

                        lastResultTime = System.currentTimeMillis()

                        // Pause session while processing
                        sessionPaused = true

                        serviceScope.launch {
                            delay(300)
                            sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))
                        }
                    }
                }

                // Continue listening if session is still active and not paused
                if (isSessionActive && !sessionPaused) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isSessionActive && !sessionPaused) {
                            startSpeechRecognition()
                        }
                    }, 500)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partialText = matches[0]

                    // Send partial results for UI feedback
                    sendBroadcast(Intent("com.example.heylisa.PARTIAL_TEXT").apply {
                        putExtra("text", partialText)
                    })

                    lastResultTime = System.currentTimeMillis()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Handle speech recognition events
            }
        })
    }
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun startSpeechRecognition() {
        if (sessionPaused || !isSessionActive) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            androidSpeechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("HeyLisa", "Failed to start speech recognition", e)
            endSpeechSession()
        }
    }

    private fun stopAndroidSpeechRecognizer() {
        androidSpeechRecognizer?.stopListening()
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun resumeSpeechSession() {
        if (!isSessionActive || isShuttingDown) return

        Log.d("HeyLisa", "▶️ Resuming speech recognition session")
        sessionPaused = false
        lastResultTime = System.currentTimeMillis()

        serviceScope.launch {
            withContext(Dispatchers.Main) {
                if (isSessionActive && !sessionPaused) {
                    startSpeechRecognition()
                }
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun endSpeechSession() {
        Log.d("HeyLisa", "🏁 Ending speech recognition session")

        isSessionActive = false
        sessionPaused = false

        serviceScope.launch {
            withContext(Dispatchers.Main) {
                androidSpeechRecognizer?.stopListening()
            }

            sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))

            if (!isShuttingDown) {
                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                    putExtra("state", "wake_word_listening")
                })

                delay(1000)
                if (!isProcessingResult && !isTtsSpeaking) {
                    startWakeWordDetection()
                }
            }
        }
    }

    // Wake word detection methods (unchanged)
    private fun checkForWakeWordSmallModel(result: String?): Boolean {
        if (result.isNullOrEmpty()) return false

        try {
            val partial = Regex("\"partial\"\\s*:\\s*\"(.*?)\"")
                .find(result)?.groupValues?.getOrNull(1)
                ?.lowercase()?.trim()

            if (partial.isNullOrEmpty()) return false

            Log.d("HeyLisa", "Heard: '$partial'")

            val words = partial.split("\\s+".toRegex()).filter { it.isNotEmpty() }

            for (i in 0 until words.size - 1) {
                val firstWord = words[i]
                val secondWord = words[i + 1]

                val validFirstWords = setOf("hey", "hi", "hello", "he")
                val validSecondWords = setOf("lisa", "liza", "leesa")

                if (firstWord in validFirstWords && secondWord in validSecondWords) {
                    Log.d("HeyLisa", "✅ Found wake pattern: '$firstWord $secondWord' in speech")
                    return true
                }
            }

            Log.d("HeyLisa", "❌ No wake word pattern found in: '$partial'")
            return false

        } catch (e: Exception) {
            Log.e("HeyLisa", "Error checking wake word", e)
            return false
        }
    }

    private fun validateWakeWordWithFinalResult(): Boolean {
        return try {
            val finalResult = synchronized(recognizerLock) {
                wakeWordRecognizer?.finalResult
            }

            if (finalResult.isNullOrEmpty()) return false

            val finalText = Regex("\"text\"\\s*:\\s*\"(.*?)\"")
                .find(finalResult)?.groupValues?.getOrNull(1)
                ?.lowercase()?.trim()

            if (finalText.isNullOrEmpty()) return false

            Log.d("HeyLisa", "Final validation text: '$finalText'")

            val words = finalText.split("\\s+".toRegex()).filter { it.isNotEmpty() }

            for (i in 0 until words.size - 1) {
                val firstWord = words[i]
                val secondWord = words[i + 1]

                val validFirstWords = setOf("hey", "hi", "hello", "he")
                val validSecondWords = setOf("lisa", "liza", "leesa")

                if (firstWord in validFirstWords && secondWord in validSecondWords) {
                    Log.d("HeyLisa", "✅ Final validation passed for: '$firstWord $secondWord'")
                    return true
                }
            }

            val exactMatches = setOf(
                "hey lisa", "hi lisa", "hello lisa",
                "he lisa", "hey liza", "hi liza"
            )

            val isValid = exactMatches.any { finalText.contains(it) }
            Log.d("HeyLisa", "Final validation result: $isValid for '$finalText'")

            return isValid

        } catch (e: Exception) {
            Log.e("HeyLisa", "Error in final validation", e)
            false
        }
    }

    private fun hasMinimumConfidence(result: String?): Boolean {
        if (result.isNullOrEmpty()) return false

        try {
            val confidenceRegex = Regex("\"confidence\"\\s*:\\s*([0-9.]+)")
            val confidenceMatch = confidenceRegex.find(result)

            if (confidenceMatch != null) {
                val confidence = confidenceMatch.groupValues[1].toDoubleOrNull()
                if (confidence != null) {
                    Log.d("HeyLisa", "Recognition confidence: $confidence")
                    return confidence > 0.5
                }
            }

            val hasText = result.contains("\"text\"") || result.contains("\"partial\"")
            return hasText

        } catch (e: Exception) {
            return false
        }
    }

    private fun stopListening() {
        isListening = false
        audioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        audioRecord = null

        synchronized(recognizerLock) {
            wakeWordRecognizer?.close()
            wakeWordRecognizer = null
        }
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun checkPermissions(): Boolean {
        val audioPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return audioPermission && notificationPermission
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun restartAudioRecordAndRecognizer() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        audioRecord?.release()
        audioRecord = null

        synchronized(recognizerLock) {
            wakeWordRecognizer?.close()
            wakeWordRecognizer = null
        }

        try {
            synchronized(recognizerLock) {
                wakeWordRecognizer = Recognizer(smallModel, 16000.0f)
            }
        } catch (e: Exception) {
            Log.e("HeyLisa", "Recognizer reinit failed", e)
            return
        }

        isListening = false
        serviceScope.launch {
            delay(500)
            startWakeWordDetection()
        }
        Log.i("HeyLisa", "🔁 Restarted wake word detection with small model")
    }

    private suspend fun initWakeWordModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("HeyLisa", "📦 Initializing small model for wake word detection...")

            val smallModelDir = File(filesDir, "vosk-model-small")
            if (!smallModelDir.exists()) {
                if (!extractSmallModelFromAssets()) {
                    return@withContext false
                }
            }
            smallModel = Model(smallModelDir.absolutePath)
            Log.d("HeyLisa", "✅ Small model loaded for wake word detection")

            true
        } catch (e: Exception) {
            Log.e("HeyLisa", "❌ Failed to initialize wake word model", e)
            false
        }
    }

    private suspend fun extractSmallModelFromAssets(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(filesDir, "vosk-model-small")
            modelDir.mkdirs()
            extractAssetFolder("vosk-model-small", modelDir.absolutePath)
            true
        } catch (e: Exception) {
            Log.e("HeyLisa", "❌ Failed to extract small model", e)
            false
        }
    }

    private fun extractAssetFolder(assetPath: String, destinationPath: String) {
        val assetManager = assets
        val assets = assetManager.list(assetPath) ?: return

        val destinationDir = File(destinationPath)
        destinationDir.mkdirs()

        for (asset in assets) {
            val assetFullPath = "$assetPath/$asset"
            val destinationFullPath = "$destinationPath/$asset"

            try {
                val subAssets = assetManager.list(assetFullPath)
                if (subAssets != null && subAssets.isNotEmpty()) {
                    extractAssetFolder(assetFullPath, destinationFullPath)
                } else {
                    assetManager.open(assetFullPath).use { inputStream ->
                        File(destinationFullPath).outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Log.d("HeyLisa", "📄 Extracted: $asset")
                }
            } catch (e: Exception) {
                Log.e("HeyLisa", "❌ Error extracting $asset", e)
            }
        }
    }

    // Notification and utility methods (unchanged)
    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, "vosk_channel")
            .setContentTitle("Hey Lisa")
            .setContentText(content)
            .setSilent(true)
            .setSmallIcon(R.drawable.mic)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "vosk_channel", "Vosk Wake Word Channel", NotificationManager.IMPORTANCE_MIN
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createWakeWordAlertChannel() {
        val channelId = "wake_word_alert_channel"
        val channelName = "Wake Word Alerts"
        val channelDescription = "Notifications when 'Hey Lisa' is detected"
        val importance = NotificationManager.IMPORTANCE_HIGH

        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description = channelDescription
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(false)
            setSound(null, null)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun showWakeWordDetectedNotification() {
        if (isAppInForeground()) {
            Log.d("HeyLisa", "App is in foreground – not showing notification")
            return
        }

        val intent = Intent(this, VoiceInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "wake_word_alert_channel")
            .setContentTitle("Hey Lisa Detected")
            .setContentText("Tap to speak with Lisa")
            .setSmallIcon(R.drawable.mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, notification)
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = packageName

        for (appProcess in appProcesses) {
            if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkBatteryOptimization() {
        if (!isBatteryOptimizationIgnored()) {
            Log.w("HeyLisa", "App is not whitelisted from battery optimization - service may be killed")
        }
    }

    override fun onDestroy() {
        isShuttingDown = true
        unregisterReceiver(stateReceiver)

        stopListening()

        // Clean up Android Speech Recognizer
        androidSpeechRecognizer?.destroy()
        androidSpeechRecognizer = null

        synchronized(recognizerLock) {
            wakeWordRecognizer?.close()
            wakeWordRecognizer = null
        }

        // Close small model
        if (::smallModel.isInitialized) {
            smallModel.close()
        }

        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}