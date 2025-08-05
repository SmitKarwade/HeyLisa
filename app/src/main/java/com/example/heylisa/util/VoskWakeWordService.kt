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
import com.example.heylisa.service.CustomTtsService
import com.example.heylisa.voice.VoiceInputActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale

class VoskWakeWordService : Service() {

    private lateinit var smallModel: Model  // Only for wake word detection
    private var wakeWordRecognizer: Recognizer? = null
    private var currentSpeechRecognizer: SpeechRecognizer? = null  // Singleton speech recognizer
    private var audioRecord: AudioRecord? = null

    @Volatile private var isListening = false
    @Volatile private var isShuttingDown = false
    @Volatile private var isSessionActive = false
    @Volatile private var isTtsSpeaking = false
    @Volatile private var isProcessingResult = false
    @Volatile private var sessionPaused = false
    @Volatile private var inFollowUp = false


    // Synchronization
    private val recognizerLock = Any()
    private val speechRecognitionMutex = Mutex()
    private val wakeWordMutex = Mutex()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var speechRecognitionJob: Job? = null
    private var wakeWordJob: Job? = null

    // Android Speech Recognition state
    private var speechRecognitionStartTime = 0L
    private var lastResultTime = 0L
    // ------------- timing constants -------------
    private val MAX_SESSION_MS        = 60_000L   // 60 s hard cap
    private val START_SPEAK_MS        = 5_000L    // must begin within 5 s
    private val SILENCE_END_MS        = 3_000L
    // inside class
    private var speechStarted          = false
    private var startSpeechTimeoutTask: Job? = null



    // Minimal broadcast receiver - only restore wake word
    private val stateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CustomTtsService.TTS_STARTED -> {
                    Log.d("HeyLisa", "🔊 Custom TTS started - stopping ALL audio recognition")
                    isTtsSpeaking = true
                    sessionPaused = true

                    stopAndroidSpeechRecognizer()

                    stopListening()

                    wakeWordJob?.cancel()

                    Log.d("HeyLisa", "🛑 All audio recognition stopped for TTS")
                }

                CustomTtsService.TTS_FINISHED, CustomTtsService.TTS_ERROR -> {
                    Log.d("HeyLisa", "🔇 Custom TTS finished")
                    isTtsSpeaking = false

                    // Don't start anything immediately - wait for PROCESSING_COMPLETE
                    Log.d("HeyLisa", "⏳ Waiting for PROCESSING_COMPLETE before resuming")
                }

                "com.example.heylisa.PROCESSING_COMPLETE" -> {
                    isProcessingResult = false
                    val expectFollowUp = intent?.getBooleanExtra("expect_follow_up", true) ?: true
                    Log.d("HeyLisa", "📥 PROCESSING_COMPLETE (expectFollowUp=$expectFollowUp)")

                    stopAndroidSpeechRecognizer()

                    if (!isShuttingDown && !isTtsSpeaking) {
                        if (expectFollowUp) {
                            Log.d("HeyLisa", "🔄 Starting follow-up listening")
                            serviceScope.launch {
                                delay(800)
                                startFollowUpListening()
                            }
                        } else {
                            Log.d("HeyLisa", "🏁 No follow-up requested – back to wake word")
                            serviceScope.launch {
                                delay(800)
                                startWakeWordDetection()
                            }
                        }
                    }
                }

                "com.example.heylisa.PROCESSING_STARTED" -> {
                    Log.d("HeyLisa", "📤 Processing started - setting processing flag")
                    isProcessingResult = true
                }

                "com.example.heylisa.FOLLOWUP_TIMEOUT" -> {
                    Log.d("HeyLisa", "⏰ Follow-up session timed out - returning to wake word")
                    isSessionActive = false
                    serviceScope.launch {
                        delay(1000)
                        if (!isShuttingDown && !isTtsSpeaking) {
                            startWakeWordDetection()
                        }
                    }
                }

                "com.example.heylisa.RESTORE_WAKE_WORD" -> {
                    if (isShuttingDown || !::smallModel.isInitialized) {
                        Log.w("HeyLisa", "⚠️ Ignored restart — service is shutting down or model is not ready")
                        return
                    }

                    Log.d("HeyLisa", "VoiceInputActivity destroyed — restoring wake word detection")

                    // Force cleanup all states
                    isSessionActive = false

                    stopListening()
                    stopAndroidSpeechRecognizer()

                    serviceScope.launch {
                        delay(2000)
                        if (!isShuttingDown) {
                            startWakeWordDetection()
                        }
                    }
                }

                "com.example.heylisa.FORCE_RESTART" -> {
                    Log.d("HeyLisa", "🔧 Force restart requested")
                    forceRestart()
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

        // Force reset all states on service creation
        isSessionActive = false
        isListening = false

        // Register minimal broadcast receivers
        val stateFilter = IntentFilter().apply {
            addAction(CustomTtsService.TTS_STARTED)
            addAction(CustomTtsService.TTS_FINISHED)
            addAction(CustomTtsService.TTS_ERROR)
            addAction("com.example.heylisa.PROCESSING_STARTED")
            addAction("com.example.heylisa.PROCESSING_COMPLETE")
            addAction("com.example.heylisa.RESTORE_WAKE_WORD")
            addAction("com.example.heylisa.FORCE_RESTART")
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

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun forceRestart() {
        Log.w("HeyLisa", "🔄 Force restarting wake word detection...")

        // Reset all state flags
        isSessionActive = false
        isListening = false

        // Clean up existing resources
        serviceScope.launch {
            speechRecognitionMutex.withLock {
                withContext(Dispatchers.Main) {
                    currentSpeechRecognizer?.destroy()
                    currentSpeechRecognizer = null
                }
            }

            stopListening()

            delay(1000)

            Log.d("HeyLisa", "🚀 Force starting wake word detection after cleanup")
            startWakeWordDetection()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startWakeWordDetection() {
        wakeWordMutex.withLock {
            // Enhanced debugging - check each condition individually
            Log.d("HeyLisa", "🔍 Checking wake word detection conditions:")
            Log.d("HeyLisa", "   isListening: $isListening")
            Log.d("HeyLisa", "   isShuttingDown: $isShuttingDown")
            Log.d("HeyLisa", "   smallModel initialized: ${::smallModel.isInitialized}")
            Log.d("HeyLisa", "   isSessionActive: $isSessionActive")

            // Simplified conditions - removed isProcessingResult
            when {
                isListening -> {
                    Log.w("HeyLisa", "🛑 Wake word detection blocked - already listening")
                    return
                }
                isShuttingDown -> {
                    Log.w("HeyLisa", "🛑 Wake word detection blocked - service shutting down")
                    return
                }
                !::smallModel.isInitialized -> {
                    Log.w("HeyLisa", "🛑 Wake word detection blocked - small model not initialized")
                    return
                }
                isSessionActive -> {
                    Log.w("HeyLisa", "🛑 Wake word detection blocked - speech session active")
                    return
                }
                else -> {
                    Log.d("HeyLisa", "✅ All conditions met - starting wake word detection")
                }
            }

            Log.d("HeyLisa", "🚀 Starting wake word detection with SMALL model...")

            // Cancel any existing wake word job
            wakeWordJob?.cancel()

            // Clean up previous audio resources
            stopListening()

            synchronized(recognizerLock) {
                try {
                    wakeWordRecognizer?.close()
                    wakeWordRecognizer = Recognizer(smallModel, 16000.0f)
                } catch (e: Exception) {
                    Log.e("HeyLisa", "Failed to init wake word recognizer", e)
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
                                    // Keep only recent speech in buffer
                                    wakeWordBuffer.append(" ").append(currentText)
                                    if (wakeWordBuffer.length > 100) {
                                        wakeWordBuffer.delete(0, 50)
                                    }

                                    if (hasMinimumConfidence(partial) && checkForWakeWordSmallModel(partial)) {
                                        Log.d("HeyLisa", "🔍 WAKE WORD FOUND! Stopping wake word detection immediately")

                                        isListening = false

                                        delay(100)
                                        if (validateWakeWordWithFinalResult()) {
                                            Log.i("HeyLisa", "✅ Wake word CONFIRMED!")

//                                            withContext(Dispatchers.Main) {
//                                                Toast.makeText(
//                                                    this@VoskWakeWordService,
//                                                    "Hey Lisa detected!",
//                                                    Toast.LENGTH_SHORT
//                                                ).show()
//                                            }

                                            stopListening()

                                            serviceScope.launch {
                                                delay(500)

                                                // Launch VoiceInputActivity
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
    }

    private fun launchVoiceInputActivity() {
        try {
            val intent = Intent(this, VoiceInputActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION

                putExtra("launched_from_wake_word", true)
                putExtra("wake_word_timestamp", System.currentTimeMillis())
            }

            startActivity(intent)
            Log.d("HeyLisa", "🚀 Launched VoiceInputActivity from wake word detection")

        } catch (e: Exception) {
            Log.e("HeyLisa", "Failed to launch VoiceInputActivity", e)
            showWakeWordDetectedNotification()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startAndroidSpeechRecognition() {
        speechRecognitionMutex.withLock {
            if (isSessionActive || currentSpeechRecognizer != null) {
                Log.w("HeyLisa", "🛑 Speech recognition already active, skipping")
                return
            }

            Log.d("HeyLisa", "🎤 Starting Android Speech Recognition...")

            isSessionActive = true
            speechRecognitionStartTime = System.currentTimeMillis()
            lastResultTime = System.currentTimeMillis()

            speechRecognitionJob?.cancel()
            speechRecognitionJob = serviceScope.launch {
                try {
                    withContext(Dispatchers.Main) {
                        initializeAndroidSpeechRecognizer()
                        if (currentSpeechRecognizer != null) {
                            startSpeechRecognition()
                        } else {
                            throw Exception("Speech recognizer initialization failed")
                        }
                    }

                    while (isSessionActive && !isShuttingDown && currentSpeechRecognizer != null) {
                        val now = System.currentTimeMillis()
                        val sinceLast = now - lastResultTime
                        val total     = now - speechRecognitionStartTime

                        // our own silence detector – 3 consecutive seconds
                        if (speechStarted && sinceLast >= SILENCE_END_MS) {
                            endSpeechSession()
                            break
                        }

                        if (total >= MAX_SESSION_MS) {
                            endSpeechSession()
                            break
                        }
                        delay(1_000L)
                    }
                } catch (e: Exception) {
                    Log.e("HeyLisa", "Speech recognition failed to start", e)
                    endSpeechSession()
                    delay(1000)
                    if (!isShuttingDown) {
                        startWakeWordDetection()
                    }
                }
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun initializeAndroidSpeechRecognizer() {
        // Clean up existing recognizer
        currentSpeechRecognizer?.destroy()
        currentSpeechRecognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("HeyLisa", "Speech recognition not available on this device")
            endSpeechSession()
            return
        }

        currentSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        currentSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {

            @RequiresPermission(
                android.Manifest.permission.RECORD_AUDIO
            )
            override fun onReadyForSpeech(params: Bundle?) {
                speechStarted = false
                startSpeechTimeoutTask?.cancel()
                startSpeechTimeoutTask = serviceScope.launch {
                    delay(START_SPEAK_MS)
                    if (!speechStarted) endSpeechSession()       // user never spoke
                }
            }

            override fun onBeginningOfSpeech() {
                speechStarted = true
                startSpeechTimeoutTask?.cancel()
                lastResultTime = System.currentTimeMillis()
            }

            /* Ignore onEndOfSpeech – we will decide based on our own timer */
            override fun onEndOfSpeech() { /* no-op */ }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

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

                when (error) {
                    SpeechRecognizer.ERROR_CLIENT -> {
                        Log.w("HeyLisa", "🛑 Client error - cleaning up")
                        endSpeechSession()
                    }
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // ✅ CRITICAL FIX: Don't restart if we're in follow-up mode
                        if (inFollowUp) {
                            Log.d("HeyLisa", "🛑 No-match/timeout during follow-up - letting follow-up handler manage it")
                            return
                        }

                        // ✅ Only restart for main sessions, not follow-up
                        if (isSessionActive && currentSpeechRecognizer != null && !isTtsSpeaking && !isProcessingResult) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isSessionActive && currentSpeechRecognizer != null && !isTtsSpeaking && !isProcessingResult && !inFollowUp) {
                                    Log.d("HeyLisa", "🔄 Restarting speech recognition after no match/timeout (main session)")
                                    startSpeechRecognition()
                                } else {
                                    Log.d("HeyLisa", "🛑 Conditions changed - not restarting speech recognition")
                                    endSpeechSession()
                                }
                            }, 1000)
                        } else {
                            Log.d("HeyLisa", "🛑 Not restarting speech - TTS active or session ended")
                            endSpeechSession()
                        }
                    }
                    else -> {
                        endSpeechSession()
                    }
                }
            }

            @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                var hasResult = false // ✅ Add this flag to track if we got a meaningful result

                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0].lowercase().trim()
                    val confidenceScore = confidence?.getOrNull(0) ?: 0.0f

                    Log.d("HeyLisa", "🎯 Recognized: '$recognizedText' (confidence: $confidenceScore)")

                    // Filter out noisy words
                    val words = recognizedText.split(" ")
                    val meaningfulWords = words.filterNot { it in Noisy.noisyWords }

                    if (meaningfulWords.isNotEmpty()) {
                        hasResult = true // ✅ Set flag when we have meaningful words
                        val result = meaningfulWords.joinToString(" ")

//                        serviceScope.launch {
//                            withContext(Dispatchers.Main) {
//                                Toast.makeText(
//                                    this@VoskWakeWordService,
//                                    "You said: $result",
//                                    Toast.LENGTH_LONG
//                                ).show()
//                            }
//                        }

                        // Send result directly
                        sendBroadcast(Intent("com.example.heylisa.RECOGNIZED_TEXT").apply {
                            putExtra("result", result)
                        })

                        lastResultTime = System.currentTimeMillis()

                        serviceScope.launch {
                            delay(300)
                            sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))
                        }
                    }
                }

                // ✅ CRITICAL FIX: Don't continue if we just sent a result - processing will handle next steps
                if (hasResult) {
                    Log.d("HeyLisa", "🛑 Sent result to processing - not continuing speech recognition")
                    return
                }

                // ✅ Only continue listening if conditions are met AND no processing is happening
                if (isSessionActive && currentSpeechRecognizer != null && !isTtsSpeaking && !isProcessingResult) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        // Triple-check conditions before restarting
                        if (isSessionActive && currentSpeechRecognizer != null && !isTtsSpeaking && !isProcessingResult) {
                            Log.d("HeyLisa", "🔄 Continuing speech recognition")
                            startSpeechRecognition()
                        } else {
                            Log.d("HeyLisa", "🛑 Conditions changed - ending session instead of continuing")
                            endSpeechSession()
                        }
                    }, 500)
                } else {
                    Log.d("HeyLisa", "🛑 Not continuing speech - conditions not met (processing: $isProcessingResult)")
                    endSpeechSession()
                }
            }


            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partialText = matches[0]

                    sendBroadcast(Intent("com.example.heylisa.PARTIAL_TEXT").apply {
                        putExtra("text", partialText)
                    })

                    lastResultTime = System.currentTimeMillis()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startSpeechRecognition() {
        if (!isSessionActive || currentSpeechRecognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            // 3-second ceiling; the engine *may* stop earlier, so we guard for that.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_END_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_END_MS)
        }

        try {
            currentSpeechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("HeyLisa", "Failed to start speech recognition", e)
            endSpeechSession()
        }
    }

    private fun stopAndroidSpeechRecognizer() {
        currentSpeechRecognizer?.stopListening()
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun endSpeechSession() {
        Log.d("HeyLisa", "🏁 Ending speech recognition session")

        isSessionActive = false
        inFollowUp = false // ✅ CLEAR FLAG when ending session

        serviceScope.launch {
            speechRecognitionMutex.withLock {
                withContext(Dispatchers.Main) {
                    currentSpeechRecognizer?.stopListening()
                    currentSpeechRecognizer?.destroy()
                    currentSpeechRecognizer = null
                }
            }

            sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))

            // ✅ CRITICAL FIX: Only restart wake word if TTS is NOT speaking and NOT processing
            if (!isShuttingDown && !isTtsSpeaking && !isProcessingResult) {
                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                    putExtra("state", "wake_word_listening")
                })

                delay(1000)
                startWakeWordDetection()
            } else {
                Log.d("HeyLisa", "🛑 Not restarting wake word - TTS speaking: $isTtsSpeaking, Processing: $isProcessingResult")
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

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private suspend fun startFollowUpListening() {
        if (isShuttingDown || isTtsSpeaking) {
            Log.w("HeyLisa", "🛑 Cannot start follow-up - shutting down or TTS active")
            return
        }

        inFollowUp = true // ✅ SET FLAG
        Log.d("HeyLisa", "🎤 Starting follow-up listening session after TTS completion")

        speechRecognitionMutex.withLock {
            // ✅ CRITICAL: Clean up any existing recognizer first
            withContext(Dispatchers.Main) {
                currentSpeechRecognizer?.stopListening()
                currentSpeechRecognizer?.destroy()
                currentSpeechRecognizer = null
            }

            if (isShuttingDown) {
                inFollowUp = false // ✅ CLEAR FLAG
                return
            }

            Log.d("HeyLisa", "🎤 Initializing follow-up speech recognition...")

            // ✅ Reset session state for follow-up
            isSessionActive = true
            speechRecognitionStartTime = System.currentTimeMillis()
            lastResultTime = System.currentTimeMillis()

            // Set a timeout for follow-up listening
            val followUpTimeoutJob = serviceScope.launch {
                delay(MAX_SESSION_MS + 2_000L)              // 62 s safety cap
                if (isSessionActive && !isShuttingDown) {
                    sendBroadcast(Intent("com.example.heylisa.FOLLOWUP_TIMEOUT"))
                    endSpeechSession()
                }
            }

            try {
                withContext(Dispatchers.Main) {
                    initializeAndroidSpeechRecognizer()
                    if (currentSpeechRecognizer != null) {
                        startSpeechRecognition()
                        Log.d("HeyLisa", "✅ Follow-up speech recognition started successfully")
                    } else {
                        throw Exception("Follow-up speech recognizer initialization failed")
                    }
                }


                while (isSessionActive && !isShuttingDown && currentSpeechRecognizer != null) {
                    val now = System.currentTimeMillis()
                    val sinceLast = now - lastResultTime
                    val total     = now - speechRecognitionStartTime

                    if (speechStarted && sinceLast >= SILENCE_END_MS) {
                        endSpeechSession()
                        break
                    }

                    if (total >= MAX_SESSION_MS) {
                        endSpeechSession()
                        break
                    }
                    delay(1_000L)
                }

                Log.d("HeyLisa", "🔚 Follow-up listening session ended")
                followUpTimeoutJob.cancel()

                // End the session and return to wake word detection
                endSpeechSession()

            } catch (e: Exception) {
                Log.e("HeyLisa", "❌ Follow-up listening failed", e)
                followUpTimeoutJob.cancel()
                endSpeechSession()
            } finally {
                inFollowUp = false // ✅ CLEAR FLAG
            }
        }
    }


    // Utility methods (unchanged)
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
            delay(1000)
            if (!isShuttingDown && !isSessionActive) {
                startWakeWordDetection()
            }
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

    // Notification methods (unchanged)
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
            putExtra("launched_from_wake_word", true)
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
            .setAutoCancel(true) // This should auto-dismiss when tapped
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            // Add timeout to auto-dismiss after 10 seconds
            .setTimeoutAfter(2000L)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, notification)

        Log.d("HeyLisa", "🔔 Showed wake word notification with auto-dismiss")
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

        // Clean up Android Speech Recognizer with proper synchronization
        runBlocking {
            speechRecognitionMutex.withLock {
                currentSpeechRecognizer?.destroy()
                currentSpeechRecognizer = null
            }
        }

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