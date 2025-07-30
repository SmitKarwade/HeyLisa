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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import kotlin.math.max

class VoskWakeWordService : Service() {

    private lateinit var model: Model
    private var wakeWordRecognizer: Recognizer? = null
    private var speechRecognizer: Recognizer? = null
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
                }

                "com.example.heylisa.PROCESSING_COMPLETE" -> {
                    Log.d("HeyLisa", "📥 PROCESSING_COMPLETE received - Current state: isTtsSpeaking=$isTtsSpeaking, isShuttingDown=$isShuttingDown, sessionPaused=$sessionPaused, isSessionActive=$isSessionActive")
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
                                startSingleContinuousSpeechSession()
                            }
                        }
                    } else {
                        Log.d("HeyLisa", "❌ Cannot resume - isTtsSpeaking=$isTtsSpeaking, isShuttingDown=$isShuttingDown")
                    }
                }

                // Legacy restore receiver
                "com.example.heylisa.RESTORE_WAKE_WORD" -> {
                    if (isShuttingDown || !::model.isInitialized) {
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
                    closeSpeechRecognizerSafely()

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
            // Android 11-12 compatible registration
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
                initModel()
                sendBroadcast(Intent("com.example.heylisa.MODEL_INIT_FINISHED"))
                startWakeWordDetection()
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
        Log.d("HeyLisa", "🚀 Starting wake word detection... isListening=$isListening, isShuttingDown=$isShuttingDown, modelInit=${::model.isInitialized}, isTtsSpeaking=$isTtsSpeaking")

        // Don't start if TTS is speaking or processing or already listening
        if (isListening || isShuttingDown || !::model.isInitialized || isTtsSpeaking || isProcessingResult) {
            if (isTtsSpeaking) {
                Log.d("HeyLisa", "⏳ TTS is speaking - will start wake word detection after TTS finishes")
            }
            if (isProcessingResult) {
                Log.d("HeyLisa", "⏳ Backend processing - will start wake word detection after processing finishes")
            }
            return
        }

        synchronized(recognizerLock) {
            try {
                wakeWordRecognizer?.close()
                wakeWordRecognizer = Recognizer(model, 16000.0f, "[\"hey lisa\", \"lisa\"]")
            } catch (e: Exception) {
                Log.e("HeyLisa", "Failed to init wakeWordRecognizer", e)
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
        var lastValidSpeechTime = System.currentTimeMillis()

        wakeWordJob?.cancel()
        wakeWordJob = serviceScope.launch {
            val noisyWords = setOf("the", "a", "an", "at", "and", "of", "in", "on", "to", "is", "with", "for", "by", "from", "us", "usa", "litter")
            val partialBuffer = StringBuilder()
            var zeroReadCount = 0
            val maxZeroCount = 50

            while (isListening && !isShuttingDown) {
                try {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read == 0) {
                        zeroReadCount++
                        if (zeroReadCount > maxZeroCount) {
                            Log.d("HeyLisa", "🛑 Detected AudioRecord stall — restarting mic")
                            restartAudioRecordAndRecognizer()
                            zeroReadCount = 0
                        }
                    } else {
                        zeroReadCount = 0
                    }

                    if (read > 0) {
                        if (isShuttingDown) break

                        synchronized(recognizerLock) {
                            wakeWordRecognizer?.acceptWaveForm(buffer, read)
                        }

                        val partial = synchronized(recognizerLock) { wakeWordRecognizer?.partialResult }
                        val spoken = Regex("\"partial\"\\s*:\\s*\"(.*?)\"")
                            .find(partial ?: "")?.groupValues?.getOrNull(1)
                            ?.lowercase()?.trim()

                        if (!spoken.isNullOrEmpty() && spoken !in noisyWords) {
                            lastValidSpeechTime = System.currentTimeMillis()
                        }

                        if (partialBuffer.length > 150) partialBuffer.delete(0, partialBuffer.length / 2)

                        if (!spoken.isNullOrEmpty()) {
                            if (partialBuffer.endsWith(spoken)) {
                                partialBuffer.clear()
                            }

                            partialBuffer.append(" ").append(spoken)

                            val cleaned = partialBuffer.toString()
                                .split(" ")
                                .filterNot { it in noisyWords }
                                .map { it.trim() }

                            val joined = cleaned.joinToString(" ")
                            val fuzzyJoined = joined.replace("here", "hey")

                            if ("hey lisa" in joined || "he lisa" in joined || "hi lisa" in joined ||
                                "hear lisa" in joined || "hey lisa" in fuzzyJoined || "elisa" in joined ||
                                "he lisa" in fuzzyJoined || "hi lisa" in fuzzyJoined) {

                                Log.i("HeyLisa", "✅ Wake word detected: $spoken")
                                isListening = false
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@VoskWakeWordService,
                                        "Hey Lisa detected!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                stopListening()
                                serviceScope.launch {
                                    val isAssistant = CheckRole.isDefaultAssistant(this@VoskWakeWordService)
                                    Log.d("HeyLisa", "🎙 Is Default Assistant: $isAssistant")

                                    if (isAssistant) {
                                        val assistIntent = Intent(Intent.ACTION_ASSIST)
                                        assistIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        startActivity(assistIntent)
                                    } else {
                                        Handler.createAsync(Looper.getMainLooper()).post {
                                            Toast.makeText(this@VoskWakeWordService, "Assistant role is not set.", Toast.LENGTH_LONG).show()
                                        }
                                    }

                                    delay(500)
                                    sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                                        putExtra("state", "wake_word_detected")
                                    })
                                    showWakeWordDetectedNotification()
                                    startSingleContinuousSpeechSession()
                                }
                            }
                        }
                    }

                    if (System.currentTimeMillis() - lastValidSpeechTime > 10_000) {
                        Handler.createAsync(Looper.getMainLooper()).post {
                            Toast.makeText(this@VoskWakeWordService, "No speech detected.", Toast.LENGTH_SHORT).show()
                        }
                        Log.w("HeyLisa", "🌀 Silence for too long — full refresh of recognizer and mic")

                        launch {
                            restartAudioRecordAndRecognizer()
                        }
                        break
                        lastValidSpeechTime = System.currentTimeMillis()
                    }

                    delay(10)
                } catch (e: Exception) {
                    Log.e("HeyLisa", "Wake loop error", e)
                    stopListening()
                    break
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startSingleContinuousSpeechSession() {
        speechRecognitionJob?.cancel()
        speechRecognitionJob = serviceScope.launch {
            try {
                isSessionActive = true
                closeSpeechRecognizerSafely()
                delay(200)

                if (isShuttingDown) return@launch

                synchronized(recognizerLock) {
                    speechRecognizer = Recognizer(model, 16000.0f)
                }

                val sampleRate = 16000
                val bufferSize = max(
                    AudioRecord.getMinBufferSize(
                        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    ), 2048 * 2
                )

                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("HeyLisa", "Microphone permission not granted during recognition")
                    stopSelf()
                    return@launch
                }

                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("HeyLisa", "Speech AudioRecord init failed")
                    stopSelf()
                    return@launch
                }

                val buffer = ByteArray(bufferSize)
                val maxSessionTime = 60000L // 60 seconds max
                val meaningfulSilenceTimeout = 8000L // 8 seconds of meaningful silence to end session
                val processTimeout = 3000L // 3 seconds to process current speech


                var pauseStartTime = 0L
                var totalPausedTime = 0L

                var sessionStartTime = System.currentTimeMillis()
                var lastMeaningfulSpeechTime = System.currentTimeMillis()
                var lastAnyActivityTime = System.currentTimeMillis()
                var activeListeningTime = 0L // Track time actually listening (not paused)

                var currentSpeechBuilder = StringBuilder()
                var previousPartial = ""
                var hasCollectedSpeech = false

                sessionPaused = false
                isListening = true

                record.startRecording()

                // Skip initial buffer noise
                repeat(3) {
                    record.read(buffer, 0, buffer.size)
                    delay(50)
                }

                delay(500) // Give user time to start speaking

                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                    putExtra("state", "speech_recognition_started")
                })

                Log.d("HeyLisa", "🎤 Started single continuous speech session (60s max, 8s meaningful silence timeout)")

                while (!isShuttingDown && isListening) {

                    // Check if session is paused (TTS speaking or backend processing)
                    if (sessionPaused) {
                        if (pauseStartTime == 0L) {
                            pauseStartTime = System.currentTimeMillis()
                            Log.d("HeyLisa", "⏸️ Session paused - timer stopped")
                        }

                        Log.d("HeyLisa", "⏸️ Session paused - waiting...")
                        delay(100)
                        continue
                    }else {
                        // ✅ Add paused time to total when resuming
                        if (pauseStartTime > 0L) {
                            val pauseDuration = System.currentTimeMillis() - pauseStartTime
                            totalPausedTime += pauseDuration
                            pauseStartTime = 0L

                            sessionStartTime = System.currentTimeMillis()
                            activeListeningTime = 0L

                            // ✅ RESET the meaningful speech timer when resuming
                            val currentAdjustedTime = System.currentTimeMillis() - totalPausedTime
                            lastMeaningfulSpeechTime = currentAdjustedTime
                            lastAnyActivityTime = currentAdjustedTime

                            Log.d("HeyLisa", "▶️ Session resumed - adding ${pauseDuration}ms to paused time (total: ${totalPausedTime}ms)")
                            Log.d("HeyLisa", "🔄 Reset timers - giving user fresh 8 seconds to speak")

                            Log.d("HeyLisa", "🎤 Confirming audio processing restart...")
                        }
                    }

                    val currentTime = System.currentTimeMillis()
                    val adjustedCurrentTime = currentTime - totalPausedTime
                    val timeSinceMeaningfulSpeech = adjustedCurrentTime - lastMeaningfulSpeechTime
                    val timeSinceAnyActivity = adjustedCurrentTime - lastAnyActivityTime
                    val timeSinceSessionStart = currentTime - sessionStartTime

                    activeListeningTime += 100
                    if (activeListeningTime % 5000 == 0L) { // Every 5 seconds
                        Log.d("HeyLisa", "🔄 Audio loop active - listening for speech...")
                    }
                    // If 60 seconds of active listening, end session
                    if (timeSinceSessionStart >= maxSessionTime) {
                        Log.d("HeyLisa", "⏰ 60 seconds completed for this interaction cycle - ending speech session")
                        break
                    }

                    // ✅ If 8 seconds of meaningful silence (excluding paused time), end the entire session
                    if (timeSinceMeaningfulSpeech > meaningfulSilenceTimeout) {
                        Log.d("HeyLisa", "🔚 8 seconds of meaningful silence - ending speech session (excluding ${totalPausedTime}ms paused time)")
                        break
                    }

                    // If 3 seconds of any silence and we have speech, process it
                    if (timeSinceAnyActivity > processTimeout && hasCollectedSpeech && !sessionPaused) {
                        Log.d("HeyLisa", "📝 3 seconds of silence - processing current speech")

                        val finalResult = synchronized(recognizerLock) {
                            speechRecognizer?.finalResult
                        }
                        val finalText = Regex("\"text\"\\s*:\\s*\"(.*?)\"")
                            .find(finalResult ?: "")?.groupValues?.getOrNull(1)

                        if (!finalText.isNullOrBlank()) {
                            currentSpeechBuilder.append(" ").append(finalText)
                        }

                        val cleanedSpeech = cleanRepeatedWords(currentSpeechBuilder.toString().trim().lowercase())
                        val meaningfulWords = cleanedSpeech.split(" ").filterNot { it in Noisy.noisyWords }

                        if (meaningfulWords.isNotEmpty()) {
                            val result = meaningfulWords.joinToString(" ")
                            Log.i("HeyLisa", "✅ Processed speech: $result")

                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    applicationContext,
                                    "You said: $result",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            // Send result - this will trigger backend processing and TTS
                            sendBroadcast(Intent("com.example.heylisa.RECOGNIZED_TEXT").apply {
                                putExtra("result", result)
                            })

                            delay(300)
                            sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))

                            // Session will be paused by PROCESSING_STARTED broadcast
                            // and resumed by PROCESSING_COMPLETE broadcast after TTS finishes
                        }

                        // Reset for next speech segment within same session
                        synchronized(recognizerLock) {
                            speechRecognizer?.close()
                            speechRecognizer = Recognizer(model, 16000.0f)
                        }

                        currentSpeechBuilder.clear()
                        previousPartial = ""
                        hasCollectedSpeech = false
                        lastAnyActivityTime = adjustedCurrentTime

                        Log.d("HeyLisa", "🔄 Ready for next speech segment in same session...")
                        continue
                    }

                    // Only read audio if not paused
                    if (!sessionPaused) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            Log.d("HeyLisa", "🎵 Reading audio: $read bytes")
                        } else if (read == 0) {
                            Log.d("HeyLisa", "⚠️ Audio read returned 0 bytes")
                        }
                        if (read > 0 && read % 2 == 0) {
                            if (isShuttingDown) break

                            synchronized(recognizerLock) {
                                speechRecognizer?.acceptWaveForm(buffer, read)
                            }

                            val partial = synchronized(recognizerLock) {
                                speechRecognizer?.partialResult
                            }
                            val currentText = Regex("\"partial\"\\s*:\\s*\"(.*?)\"")
                                .find(partial ?: "")?.groupValues?.getOrNull(1)

                            if (!currentText.isNullOrBlank() && currentText != previousPartial) {
                                previousPartial = currentText
                                lastAnyActivityTime = adjustedCurrentTime

                                // Check if this is meaningful speech (not just noise)
                                val words = currentText.lowercase().split(" ")
                                val meaningfulWords = words.filterNot { it in Noisy.noisyWords }

                                if (meaningfulWords.isNotEmpty()) {
                                    lastMeaningfulSpeechTime = adjustedCurrentTime
                                    hasCollectedSpeech = true
                                    Log.d("HeyLisa", "🗣️ Meaningful speech: $currentText")

                                    sendBroadcast(Intent("com.example.heylisa.PARTIAL_TEXT").apply {
                                        putExtra("text", currentText)
                                    })
                                } else {
                                    Log.d("HeyLisa", "🔇 Noise ignored: $currentText")
                                }
                            }
                        }
                    }

                    delay(10)
                }

                record.stop()
                record.release()

                // Process any remaining speech at end of session
                if (hasCollectedSpeech && !sessionPaused) {
                    val finalResult = synchronized(recognizerLock) {
                        speechRecognizer?.finalResult
                    }
                    val finalText = Regex("\"text\"\\s*:\\s*\"(.*?)\"")
                        .find(finalResult ?: "")?.groupValues?.getOrNull(1)

                    if (!finalText.isNullOrBlank()) {
                        currentSpeechBuilder.append(" ").append(finalText)
                    }

                    val cleanedSpeech = cleanRepeatedWords(currentSpeechBuilder.toString().trim().lowercase())
                    val meaningfulWords = cleanedSpeech.split(" ").filterNot { it in Noisy.noisyWords }

                    if (meaningfulWords.isNotEmpty()) {
                        val result = meaningfulWords.joinToString(" ")
                        Log.i("HeyLisa", "✅ Final speech: $result")

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                applicationContext,
                                "Final: $result",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        sendBroadcast(Intent("com.example.heylisa.RECOGNIZED_TEXT").apply {
                            putExtra("result", result)
                        })
                    }
                }

                Log.d("HeyLisa", "🏁 Single continuous speech session ended")

            } catch (e: Exception) {
                Log.e("HeyLisa", "❌ Speech recognition failed", e)
            } finally {
                isSessionActive = false
                closeSpeechRecognizerSafely()
                isListening = false
                sessionPaused = false

                if (!isShuttingDown) {
                    sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))

                    if (speechSessionCancelled) {
                        Log.w("HeyLisa", "⚠️ Speech session was cancelled — returning to wake word")
                        speechSessionCancelled = false
                    } else {
                        Log.d("HeyLisa", "🔄 Returning to wake word detection")
                    }

                    sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                        putExtra("state", "wake_word_listening")
                    })

                    // Wait for any final processing to complete before returning to wake word
                    delay(1000)
                    if (!isProcessingResult && !isTtsSpeaking) {
                        startWakeWordDetection()
                    }
                }
            }
        }
    }


    private fun resumeSpeechSession() {
        Log.d("HeyLisa", "🔄 resumeSpeechSession called - Current state: isShuttingDown=$isShuttingDown, sessionPaused=$sessionPaused")

        if (isShuttingDown) {
            Log.d("HeyLisa", "❌ Cannot resume - service is shutting down")
            return
        }

        if (!isSessionActive) {
            Log.w("HeyLisa", "⚠️ Session not active - cannot resume, need to restart")
            return
        }

        Log.d("HeyLisa", "▶️ Resuming speech recognition session")
        sessionPaused = false

        // ✅ Add this: Ensure the main loop continues properly
        Log.d("HeyLisa", "🎤 Audio processing should now resume in main loop")
    }


    private fun stopListening() {
        Handler.createAsync(Looper.getMainLooper()).post{
            Toast.makeText(this, "Stopping listening...", Toast.LENGTH_SHORT).show()
        }
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

        // Check notification permission for Android 13+
        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required for Android 11-12
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
                wakeWordRecognizer = Recognizer(model, 16000.0f, "[\"hey lisa\"]")
            }
        } catch (e: Exception) {
            Log.e("HeyLisa", "Recognizer reinit failed in restartAudioRecordAndRecognizer()", e)
            return
        }

        isListening = false
        serviceScope.launch {
            delay(300)
            startWakeWordDetection()
        }
        Log.i("HeyLisa", "🔁 Restarted recognizer and mic after invalid input")
    }

    private fun closeSpeechRecognizerSafely() {
        synchronized(recognizerLock) {
            try {
                speechRecognizer?.close()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }

    private suspend fun initModel() {
        val modelDir = File(filesDir, "vosk-model")
        if (!modelDir.exists()) throw IOException("Model not found")

        withContext(Dispatchers.IO) {
            withTimeout(60_000) {
                val rnnlmFile = File("$modelDir/rnnlm/final.raw")
                if (rnnlmFile.exists()) {
                    Log.w("HeyLisa", "Deleting rnnlm/final.raw to prevent crash")
                    rnnlmFile.delete()
                }
                model = Model(modelDir.absolutePath)
            }
        }
    }

    private fun cleanRepeatedWords(text: String): String {
        val words = text.split(" ")
        val result = mutableListOf<String>()
        for (i in words.indices) {
            if (i == 0 || words[i] != words[i - 1]) {
                result.add(words[i])
            }
        }
        return result.joinToString(" ")
    }

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

    // Add this method to handle battery optimization
    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkBatteryOptimization() {
        if (!isBatteryOptimizationIgnored()) {
            Log.w("HeyLisa", "App is not whitelisted from battery optimization - service may be killed")
            //Toast.makeText(this, "App is not whitelisted from battery optimization - service may be killed", Toast.LENGTH_LONG).show()
        }
    }



    override fun onDestroy() {
        isShuttingDown = true
        unregisterReceiver(stateReceiver)

        stopListening()
        closeSpeechRecognizerSafely()
        synchronized(recognizerLock) {
            wakeWordRecognizer?.close()
            wakeWordRecognizer = null
            speechRecognizer = null
        }

        if (::model.isInitialized) {
            model.close()
        }

        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
