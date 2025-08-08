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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class VoskWakeWordService : Service() {

    private lateinit var smallModel: Model  // Only for wake word detection
    private var wakeWordRecognizer: Recognizer? = null
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

    private val MAX_SESSION_MS = 60_000L   // 60 s hard cap

    companion object {
        const val ACTION_START_SPEECH_RECOGNITION = "com.example.heylisa.START_SPEECH_RECOGNITION"
        const val ACTION_STOP_SPEECH_RECOGNITION = "com.example.heylisa.STOP_SPEECH_RECOGNITION"
        const val MAX_RECORDING_DURATION_MS = 60_000L
        const val MIN_RECORDING_DURATION_MS = 1_000L
        const val SILENCE_THRESHOLD_MS = 4_000L
        const val VOICE_ACTIVITY_THRESHOLD = 600
        const val NO_VOICE_TIMEOUT_MS = 10_000L
    }


    // Add these class-level variables
    private var whisperAudioRecord: AudioRecord? = null
    private var isWhisperRecording = false
    private var audioBuffer = ByteArrayOutputStream()
    private val audioChunks = mutableListOf<ByteArray>()
    private var recordingStartTime = 0L
    private var lastVoiceActivity = 0L

    // Audio recording configuration
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BYTES_PER_SAMPLE = 2


    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // Whisper can take time
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun sendToWhisperAPI(audioFile: File): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("HeyLisa", "📡 Sending ${audioFile.length()} bytes to Whisper API...")

            // Simple multipart form with just the file
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
                )
                .build()

            // Clean request with no extra headers
            val request = Request.Builder()
                .url("https://api.neeja.io/api/transcribe/")
                .post(requestBody)
                .build()

            // Execute and get response
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                Log.d("HeyLisa", "📬 Response code: ${response.code}")
                Log.d("HeyLisa", "📬 Response: $responseBody")

                if (!response.isSuccessful) {
                    Log.e("HeyLisa", "❌ API failed: ${response.code} - $responseBody")
                    return@withContext null
                }

                if (responseBody.isNullOrEmpty()) {
                    Log.e("HeyLisa", "❌ Empty response")
                    return@withContext null
                }

                return@withContext parseWhisperResponse(responseBody)
            }

        } catch (e: Exception) {
            Log.e("HeyLisa", "❌ API call failed", e)
            return@withContext null
        }
    }


    private fun parseWhisperResponse(responseJson: String): String? {
        return try {
            val jsonObject = JSONObject(responseJson)

            // Adjust these field names based on the actual API response structure
            when {
                jsonObject.has("text") -> {
                    val transcription = jsonObject.getString("text").trim()
                    Log.d("HeyLisa", "✅ Transcription received: '$transcription'")
                    transcription
                }
                jsonObject.has("transcription") -> {
                    val transcription = jsonObject.getString("transcription").trim()
                    Log.d("HeyLisa", "✅ Transcription received: '$transcription'")
                    transcription
                }
                jsonObject.has("result") -> {
                    val transcription = jsonObject.getString("result").trim()
                    Log.d("HeyLisa", "✅ Transcription received: '$transcription'")
                    transcription
                }
                else -> {
                    Log.w("HeyLisa", "Unknown response format: $responseJson")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("HeyLisa", "Error parsing Whisper response: $responseJson", e)
            null
        }
    }

    // Minimal broadcast receiver - only restore wake word
    private val stateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CustomTtsService.TTS_STARTED -> {
                    Log.d("HeyLisa", "🔊 Custom TTS started - stopping ALL audio recognition")
                    isTtsSpeaking = true
                    sessionPaused = true

                    // Stop Whisper recording instead of Android speech recognizer
                    stopWhisperRecording()
                    stopListening()
                    wakeWordJob?.cancel()

                    Log.d("HeyLisa", "🛑 All audio recognition stopped for TTS")
                }

                CustomTtsService.TTS_FINISHED, CustomTtsService.TTS_ERROR -> {
                    Log.d("HeyLisa", "🔇 Custom TTS finished")
                    isTtsSpeaking = false
                    Log.d("HeyLisa", "⏳ Waiting for PROCESSING_COMPLETE before resuming")
                }

                "com.example.heylisa.PROCESSING_COMPLETE" -> {
                    isProcessingResult = false
                    val expectFollowUp = intent?.getBooleanExtra("expect_follow_up", true) ?: true
                    Log.d("HeyLisa", "📥 PROCESSING_COMPLETE (expectFollowUp=$expectFollowUp)")

                    stopWhisperRecording()

                    if (!isShuttingDown && !isTtsSpeaking) {
                        if (expectFollowUp) {
                            Log.d("HeyLisa", "🔄 Starting follow-up listening")
                            serviceScope.launch {
                                delay(800)
                                startFollowUpWhisperListening()
                            }
                        } else {
                            Log.d("HeyLisa", "🏁 No follow-up requested – back to wake word")

                            isSessionActive = false
                            inFollowUp = false

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

                ACTION_START_SPEECH_RECOGNITION -> {
                    Log.d("HeyLisa", "🎤 Received manual speech start request")
                    handleManualSpeechStart()
                }

                ACTION_STOP_SPEECH_RECOGNITION -> {
                    Log.d("HeyLisa", "🛑 Received manual speech stop request")
                    handleManualSpeechStop()
                }

                "com.example.heylisa.RESTORE_WAKE_WORD" -> {
                    if (isShuttingDown || !::smallModel.isInitialized) {
                        Log.w("HeyLisa", "⚠️ Ignored restart — service is shutting down or model is not ready")
                        return
                    }

                    Log.d("HeyLisa", "VoiceInputActivity destroyed — restoring wake word detection")
                    isSessionActive = false

                    stopListening()
                    stopWhisperRecording()

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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_START_SPEECH_RECOGNITION -> {
                Log.d("HeyLisa", "🎤 Manual speech recognition requested via intent")
                handleManualSpeechStart()
            }
            ACTION_STOP_SPEECH_RECOGNITION -> {
                Log.d("HeyLisa", "🛑 Manual speech recognition stop requested")
                handleManualSpeechStop()
            }
            else -> {
                Log.d("HeyLisa", "Unknown intent action: ${intent.action}")
            }
        }
        return START_STICKY
    }

    private fun configureAudioForCapture() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun onCreate() {
        super.onCreate()
        configureAudioForCapture()

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
            addAction(ACTION_START_SPEECH_RECOGNITION)
            addAction(ACTION_STOP_SPEECH_RECOGNITION)
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

    //whisper
    private fun createWavFile(audioData: ByteArray, outputFile: File): Boolean {
        return try {
            FileOutputStream(outputFile).use { fos ->
                // WAV header
                val totalDataLen = audioData.size + 36
                val channels = 1
                val byteRate = SAMPLE_RATE * channels * BYTES_PER_SAMPLE

                fos.write("RIFF".toByteArray())
                fos.write(intToByteArray(totalDataLen))
                fos.write("WAVE".toByteArray())
                fos.write("fmt ".toByteArray())
                fos.write(intToByteArray(16))
                fos.write(shortToByteArray(1))
                fos.write(shortToByteArray(channels.toShort()))
                fos.write(intToByteArray(SAMPLE_RATE))
                fos.write(intToByteArray(byteRate))
                fos.write(shortToByteArray((channels * BYTES_PER_SAMPLE).toShort()))
                fos.write(shortToByteArray((BYTES_PER_SAMPLE * 8).toShort()))
                fos.write("data".toByteArray())
                fos.write(intToByteArray(audioData.size))
                fos.write(audioData)
            }
            true
        } catch (e: IOException) {
            Log.e("HeyLisa", "Error creating WAV file", e)
            false
        }
    }


    private fun detectVoiceActivity(buffer: ByteArray): Boolean {
        var sum = 0L
        var maxSample = 0
        var samples = 0

        for (i in buffer.indices step 2) {
            if (i + 1 < buffer.size) {
                // ✅ Fix: Properly handle signed 16-bit samples
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                val absoluteSample = kotlin.math.abs(sample)
                sum += (absoluteSample * absoluteSample).toLong()
                maxSample = kotlin.math.max(maxSample, absoluteSample)
                samples++
            }
        }

        if (samples == 0) return false

        val rms = kotlin.math.sqrt(sum.toDouble() / samples)

        // ✅ Fix: Use proper thresholds for 16-bit audio
        val hasVoice = rms > VOICE_ACTIVITY_THRESHOLD || maxSample > (VOICE_ACTIVITY_THRESHOLD * 1.5)

        // ✅ Enhanced logging shows proper values now
        if (hasVoice) {
            Log.d("HeyLisa", "🎤 Voice detected - RMS: ${rms.toInt()}, Max: $maxSample, Threshold: $VOICE_ACTIVITY_THRESHOLD")
        }

        return hasVoice
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startWhisperRecording() {
        speechRecognitionMutex.withLock {
            // ✅ Cancel any existing job and wait for completion
            if (speechRecognitionJob?.isActive == true) {
                Log.d("HeyLisa", "🛑 Cancelling existing speech recognition job")
                speechRecognitionJob?.cancel()
                speechRecognitionJob?.join() // Wait for cancellation
            }

            // ✅ Reset states before starting
            if (isWhisperRecording) {
                Log.w("HeyLisa", "⚠️ Previous recording was still active, stopping it")
                stopWhisperRecordingInternal()
            }

            Log.d("HeyLisa", "🎤 Starting Whisper audio recording...")

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

            whisperAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (whisperAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("HeyLisa", "Failed to initialize AudioRecord for Whisper")
                return
            }

            audioBuffer.reset()
            audioChunks.clear()
            recordingStartTime = System.currentTimeMillis()
            lastVoiceActivity = System.currentTimeMillis()
            isWhisperRecording = true
            isSessionActive = true

            sendBroadcast(Intent("com.example.heylisa.WHISPER_RECORDING_STARTED"))
            whisperAudioRecord?.startRecording()

            speechRecognitionJob = serviceScope.launch {
                recordAudioForWhisper()
            }

            Log.d("HeyLisa", "✅ Whisper recording started successfully")
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startWhisperRecordingWithoutMutex() {
        Log.d("HeyLisa", "🎤 [MUTEX-FREE] Starting Whisper audio recording...")

        // Cancel any existing job first
        if (speechRecognitionJob?.isActive == true) {
            Log.d("HeyLisa", "🛑 Cancelling existing speech recognition job")
            speechRecognitionJob?.cancel()
            speechRecognitionJob?.join()
        }

        // Reset states before starting
        if (isWhisperRecording) {
            Log.w("HeyLisa", "⚠️ Previous recording was still active, stopping it")
            stopWhisperRecordingInternal()
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        whisperAudioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (whisperAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("HeyLisa", "Failed to initialize AudioRecord for Whisper")
            return
        }

        // Set states AFTER successful initialization
        audioBuffer.reset()
        audioChunks.clear()
        recordingStartTime = System.currentTimeMillis()
        lastVoiceActivity = System.currentTimeMillis()
        isWhisperRecording = true
        isSessionActive = true

        sendBroadcast(Intent("com.example.heylisa.WHISPER_RECORDING_STARTED"))
        whisperAudioRecord?.startRecording()

        speechRecognitionJob = serviceScope.launch {
            recordAudioForWhisper()
        }

        Log.d("HeyLisa", "✅ [MUTEX-FREE] Whisper recording started successfully")
    }

    private fun stopWhisperRecordingInternal() {
        Log.d("HeyLisa", "🛑 [STOP-1] Internal stop - cleaning up Whisper recording")

        isWhisperRecording = false
        Log.d("HeyLisa", "🛑 [STOP-2] Set isWhisperRecording = false")

        whisperAudioRecord?.apply {
            try {
                Log.d("HeyLisa", "🛑 [STOP-3] Stopping AudioRecord...")
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                    Log.d("HeyLisa", "🛑 [STOP-4] AudioRecord stopped")
                }
                release()
                Log.d("HeyLisa", "🛑 [STOP-5] AudioRecord released")
            } catch (e: Exception) {
                Log.e("HeyLisa", "🛑 [STOP-6] Error stopping AudioRecord: ${e.message}")
            }
        }
        whisperAudioRecord = null
        Log.d("HeyLisa", "🛑 [STOP-7] AudioRecord set to null")
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun recordAudioForWhisper() {
        val buffer = ByteArray(1024)
        var hasVoiceDetected = false
        var silenceCheckJob: Job? = null
        var lastVoiceDetectedTime = System.currentTimeMillis()


        var voiceActivityCounter = 0
        val voiceConfirmationThreshold = 3

        while (isWhisperRecording && !isShuttingDown) {
            try {
                val bytesRead = whisperAudioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (bytesRead > 0) {
                    audioBuffer.write(buffer, 0, bytesRead)

                    logVoiceActivityForTuning(buffer)

                    val hasVoice = detectVoiceActivity(buffer)

                    if (hasVoice) {
                        voiceActivityCounter++
                        if (voiceActivityCounter >= voiceConfirmationThreshold) {
                            if (!hasVoiceDetected) {
                                Log.d("HeyLisa", "🎤 Voice activity CONFIRMED after $voiceActivityCounter detections")
                            }
                            hasVoiceDetected = true
                            lastVoiceDetectedTime = System.currentTimeMillis()
                            lastVoiceActivity = System.currentTimeMillis()

                            silenceCheckJob?.cancel()
                            silenceCheckJob = serviceScope.launch {
                                delay(SILENCE_THRESHOLD_MS)

                                val now = System.currentTimeMillis()
                                val actualSilenceDuration = now - lastVoiceDetectedTime

                                if (actualSilenceDuration >= SILENCE_THRESHOLD_MS && isWhisperRecording) {
                                    Log.d("HeyLisa", "🔇 Confirmed silence after voice activity (${actualSilenceDuration}ms)")
                                    stopWhisperRecordingAndProcess()
                                }
                            }
                        }
                    } else {
                        voiceActivityCounter = 0
                    }

                    val now = System.currentTimeMillis()
                    val recordingDuration = now - recordingStartTime

                    when {
                        recordingDuration >= MAX_RECORDING_DURATION_MS -> {
                            Log.d("HeyLisa", "⏱️ Max recording duration reached")
                            silenceCheckJob?.cancel()
                            stopWhisperRecordingAndProcess()
                            break
                        }
                        !hasVoiceDetected && recordingDuration >= NO_VOICE_TIMEOUT_MS -> {
                            Log.d("HeyLisa", "⏰ No voice detected within ${NO_VOICE_TIMEOUT_MS/1000} seconds")
                            silenceCheckJob?.cancel()
                            stopWhisperRecordingAndProcess()
                            break
                        }
                    }
                }

                delay(50)

            } catch (e: Exception) {
                Log.e("HeyLisa", "Error during Whisper recording", e)
                silenceCheckJob?.cancel()
                break
            }
        }

        silenceCheckJob?.cancel()
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun stopWhisperRecordingAndProcess() {
        Log.d("HeyLisa", "🛑 Stopping Whisper recording and processing...")

        isWhisperRecording = false

        whisperAudioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        whisperAudioRecord = null

        // Check if we have enough audio to process
        val audioData = audioBuffer.toByteArray()
        val recordingDuration = System.currentTimeMillis() - recordingStartTime

        Log.d("HeyLisa", "📊 Audio data size: ${audioData.size}, duration: ${recordingDuration}ms")

        if (audioData.isEmpty() || recordingDuration < MIN_RECORDING_DURATION_MS) {
            Log.w("HeyLisa", "⚠️ Recording too short or empty, not processing")
            endWhisperSession()
            return
        }

        // Send processing started broadcast
        sendBroadcast(Intent("com.example.heylisa.PROCESSING_STARTED"))

        // ✅ Launch processing in a separate coroutine to prevent blocking
        serviceScope.launch {
            try {
                Log.d("HeyLisa", "🚀 Starting Whisper processing in new coroutine")
                processAudioWithWhisper(audioData)
            } catch (e: Exception) {
                Log.e("HeyLisa", "❌ Error in Whisper processing coroutine", e)
                endWhisperSession()
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun processAudioWithWhisper(audioData: ByteArray) = withContext(Dispatchers.IO) {
        try {
            if (!isNetworkAvailable()) {
                Log.e("HeyLisa", "❌ No internet connection available for Whisper API")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VoskWakeWordService, "No internet connection", Toast.LENGTH_SHORT).show()
                }
                endWhisperSession()
                return@withContext
            }

            Log.d("HeyLisa", "🤖 Processing ${audioData.size} bytes with Whisper API...")

            val tempFile = File(cacheDir, "whisper_audio_${System.currentTimeMillis()}.wav")

            if (!createWavFile(audioData, tempFile)) {
                Log.e("HeyLisa", "Failed to create WAV file")
                endWhisperSession()
                return@withContext
            }

            val transcription = sendToWhisperAPI(tempFile)
            tempFile.delete()

            if (!transcription.isNullOrBlank()) {
                val words = transcription.lowercase().trim().split(" ")
                val meaningfulWords = words.filterNot { it in Noisy.noisyWords }

                if (meaningfulWords.isNotEmpty()) {
                    val result = meaningfulWords.joinToString(" ")
                    Log.d("HeyLisa", "🎯 Whisper result: '$result'")

                    // ✅ Send result BEFORE ending session
                    sendBroadcast(Intent("com.example.heylisa.RECOGNIZED_TEXT").apply {
                        putExtra("result", result)
                        putExtra("source", "whisper")
                    })

                    // ✅ Don't launch a separate coroutine - just delay directly
                    delay(300)
                    sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))

                    // ✅ Don't call endWhisperSession() here - let PROCESSING_COMPLETE handle it
                    Log.d("HeyLisa", "✅ Transcription processing completed successfully")
                } else {
                    Log.d("HeyLisa", "❌ No meaningful words in Whisper result")
                    endWhisperSession()
                }
            } else {
                Log.w("HeyLisa", "⚠️ Empty transcription from Whisper")
                endWhisperSession()
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            // ✅ Handle cancellation gracefully
            Log.w("HeyLisa", "⚠️ Whisper processing was cancelled (this is normal during cleanup)")
            throw e // Re-throw to maintain coroutine cancellation behavior
        } catch (e: Exception) {
            Log.e("HeyLisa", "Error processing audio with Whisper", e)
            endWhisperSession()
        }
    }


    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun endWhisperSession() {
        Log.d("HeyLisa", "🏁 Ending Whisper session")

        // ✅ Reset all session flags
        isSessionActive = false
        isWhisperRecording = false
        inFollowUp = false

        // ✅ Clean up audio resources
        stopWhisperRecordingInternal()

        // ✅ Cancel and clean up the job
        speechRecognitionJob?.cancel()
        speechRecognitionJob = null

        audioBuffer.reset()
        audioChunks.clear()

        serviceScope.launch {
            sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))

            if (!isShuttingDown && !isTtsSpeaking && !isProcessingResult) {
                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                    putExtra("state", "wake_word_listening")
                })

                delay(1000)
                startWakeWordDetection()
            } else {
                Log.d("HeyLisa", "🛑 Not restarting wake word - TTS: $isTtsSpeaking, Processing: $isProcessingResult")
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false

        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun forceRestart() {
        Log.w("HeyLisa", "🔄 Force restarting wake word detection...")

        isSessionActive = false
        isListening = false

        serviceScope.launch {
            stopWhisperRecording()

            stopListening()

            delay(1000)

            Log.d("HeyLisa", "🚀 Force starting wake word detection after cleanup")
            startWakeWordDetection()
        }
    }

    private fun logVoiceActivityForTuning(buffer: ByteArray) {
        var sum = 0L
        var maxSample = 0

        for (i in buffer.indices step 2) {
            if (i + 1 < buffer.size) {
                val sample = (buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)
                val absoluteSample = kotlin.math.abs(sample)
                sum += (absoluteSample * absoluteSample).toLong()
                maxSample = kotlin.math.max(maxSample, absoluteSample)
            }
        }

        val rms = kotlin.math.sqrt(sum.toDouble() / (buffer.size / 2))

        // ✅ Log every 10th reading to avoid spam
        if (System.currentTimeMillis() % 500 < 50) { // Log every ~500ms
            Log.d("HeyLisa", "🔊 Audio levels - RMS: ${rms.toInt()}, Max: $maxSample, Threshold: $VOICE_ACTIVITY_THRESHOLD")
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
                            val isFinal = synchronized(recognizerLock) {
                                wakeWordRecognizer?.acceptWaveForm(buffer, read) ?: false
                            }

                            if (isFinal) {
                                val finalResult = synchronized(recognizerLock) {
                                    wakeWordRecognizer?.result
                                }
                                val finalText = Regex("\"text\"\\s*:\\s*\"(.*?)\"")
                                    .find(finalResult ?: "")?.groupValues?.getOrNull(1)
                                    ?.lowercase()?.trim()
                                if (!finalText.isNullOrEmpty()) {
                                    val words = finalText.split("\\s+".toRegex()).filter { it.isNotBlank() }
                                    val meaningfulWords = words.filterNot { it in Noisy.noisyWords }
                                    if (meaningfulWords.isNotEmpty()) {
                                        val displayText = meaningfulWords.joinToString(" ")
                                        // --- Toast will always show, EVEN IF wake word! ---
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@VoskWakeWordService, displayText, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
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

                                            stopListening()

                                            serviceScope.launch {

                                                stopListening()
                                                delay(200)

                                                launchVoiceInputActivity()
                                                delay(200)

                                                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                                                    putExtra("state", "whisper_recording_started") // ✅ Updated
                                                })

                                                delay(300)
                                                startWhisperRecording()
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
                "he lisa", "hey liza", "hi liza", "elissa", "elisa"
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
    private suspend fun startFollowUpWhisperListening() {
        Log.d("HeyLisa", "🎤 [DEBUG-1] Starting follow-up Whisper listening session")

        if (isShuttingDown || isTtsSpeaking) {
            Log.w("HeyLisa", "🛑 Cannot start follow-up - shutting down: $isShuttingDown, TTS: $isTtsSpeaking")
            return
        }

        inFollowUp = true
        Log.d("HeyLisa", "🎤 [DEBUG-2] Set inFollowUp = true, attempting mutex lock")

        try {
            Log.d("HeyLisa", "🔒 [DEBUG-3] Acquiring speechRecognitionMutex...")

            speechRecognitionMutex.withLock {
                Log.d("HeyLisa", "🔓 [DEBUG-4] Mutex acquired successfully")

                // ✅ Check current job state before cleanup
                val currentJobState = speechRecognitionJob?.let {
                    "isActive=${it.isActive}, isCancelled=${it.isCancelled}, isCompleted=${it.isCompleted}"
                } ?: "null"
                Log.d("HeyLisa", "🔄 [DEBUG-5] Current job state: $currentJobState")

                if (isWhisperRecording || speechRecognitionJob?.isActive == true) {
                    Log.d("HeyLisa", "🔄 [DEBUG-6] Cleaning up - isWhisperRecording: $isWhisperRecording, jobActive: ${speechRecognitionJob?.isActive}")

                    stopWhisperRecordingInternal()
                    Log.d("HeyLisa", "🔄 [DEBUG-7] stopWhisperRecordingInternal() completed")

                    speechRecognitionJob?.cancel()
                    Log.d("HeyLisa", "🔄 [DEBUG-8] speechRecognitionJob cancelled")

                    // ✅ Add timeout to join() to prevent infinite wait
                    try {
                        withTimeout(3000L) { // 3 second timeout
                            speechRecognitionJob?.join()
                            Log.d("HeyLisa", "🔄 [DEBUG-9] speechRecognitionJob joined successfully")
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w("HeyLisa", "⚠️ [DEBUG-9a] Job join timed out, continuing anyway")
                        speechRecognitionJob = null // Force reset
                    }
                } else {
                    Log.d("HeyLisa", "🔄 [DEBUG-10] No cleanup needed")
                }

                if (isShuttingDown) {
                    Log.w("HeyLisa", "🛑 [DEBUG-11] Service shutting down, aborting follow-up")
                    inFollowUp = false
                    return@withLock
                }

                Log.d("HeyLisa", "⏱️ [DEBUG-12] Setting up timeout job")
                val followUpTimeoutJob = serviceScope.launch {
                    delay(MAX_SESSION_MS + 2_000L)
                    if (isSessionActive && !isShuttingDown) {
                        Log.d("HeyLisa", "⏰ Follow-up timeout triggered")
                        sendBroadcast(Intent("com.example.heylisa.FOLLOWUP_TIMEOUT"))
                        endWhisperSession()
                    }
                }

                try {
                    Log.d("HeyLisa", "🔄 [DEBUG-13] Resetting session state")
                    isSessionActive = false

                    Log.d("HeyLisa", "🚀 [DEBUG-14] About to call startWhisperRecording()")
                    startWhisperRecordingWithoutMutex()
                    Log.d("HeyLisa", "✅ [DEBUG-15] startWhisperRecording() completed")

                    // Monitor the recording session
                    Log.d("HeyLisa", "👁️ [DEBUG-16] Starting monitoring loop")
                    while (isSessionActive && !isShuttingDown && isWhisperRecording) {
                        Log.d("HeyLisa", "👁️ [DEBUG-17] Monitoring - Active: $isSessionActive, Recording: $isWhisperRecording")
                        delay(1_000L)
                    }

                    Log.d("HeyLisa", "🔚 [DEBUG-18] Follow-up Whisper session ended")
                    followUpTimeoutJob.cancel()

                } catch (e: Exception) {
                    Log.e("HeyLisa", "❌ [DEBUG-19] Follow-up Whisper listening failed", e)
                    followUpTimeoutJob.cancel()
                    endWhisperSession()
                } finally {
                    Log.d("HeyLisa", "🏁 [DEBUG-20] Finally block - setting inFollowUp = false")
                    inFollowUp = false
                }
            }

            Log.d("HeyLisa", "🔓 [DEBUG-21] Mutex released, follow-up session completed")

        } catch (e: Exception) {
            Log.e("HeyLisa", "💥 [DEBUG-22] Exception in follow-up listening", e)
            inFollowUp = false
        }
    }

    private fun stopWhisperRecording() {
        if (!isWhisperRecording) return

        Log.d("HeyLisa", "🛑 Manually stopping Whisper recording")
        isWhisperRecording = false

        whisperAudioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        whisperAudioRecord = null

        speechRecognitionJob?.cancel()
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
    private fun handleManualSpeechStart() {
        serviceScope.launch {
            try {
                Log.d("HeyLisa", "🎤 Manual start requested")

                if (isShuttingDown) {
                    Log.w("HeyLisa", "🛑 Cannot start speech - service shutting down")
                    return@launch
                }

                if (isTtsSpeaking) {
                    Log.w("HeyLisa", "🛑 Cannot start speech - TTS is speaking")
                    return@launch
                }

                // ✅ Stop wake word detection first
                if (isListening) {
                    Log.d("HeyLisa", "🛑 Stopping wake word detection for manual speech")
                    stopListening()
                }

                // ✅ Use mutex to ensure proper synchronization
                speechRecognitionMutex.withLock {
                    if (isWhisperRecording || speechRecognitionJob?.isActive == true) {
                        Log.d("HeyLisa", "🔄 Stopping existing recording for manual start")
                        stopWhisperRecordingInternal()
                        speechRecognitionJob?.cancel()
                        speechRecognitionJob?.join() // Wait for cleanup
                    }
                }

                delay(500) // Small delay to ensure cleanup

                Log.d("HeyLisa", "🚀 Starting manual Whisper recording")
                startWhisperRecording()

                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                    putExtra("state", "whisper_recording_started")
                })

            } catch (e: Exception) {
                Log.e("HeyLisa", "Failed to start manual Whisper recording", e)
                sendBroadcast(Intent("com.example.heylisa.SPEECH_START_ERROR").apply {
                    putExtra("error", e.message)
                })
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun handleManualSpeechStop() {
        serviceScope.launch {
            try {
                Log.d("HeyLisa", "🛑 Manually stopping Whisper recording")
                if (isWhisperRecording) {
                    stopWhisperRecordingAndProcess()
                } else {
                    endWhisperSession()
                }
            } catch (e: Exception) {
                Log.e("HeyLisa", "Error stopping manual Whisper recording", e)
            }
        }
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
        stopWhisperRecording()

        synchronized(recognizerLock) {
            wakeWordRecognizer?.close()
            wakeWordRecognizer = null
        }

        if (::smallModel.isInitialized) {
            smallModel.close()
        }

        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}