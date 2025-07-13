package com.example.heylisa.util

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.example.heylisa.R
import com.example.heylisa.constant.Noisy
import com.example.heylisa.voice.VoiceInputActivity
import kotlinx.coroutines.*
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
    private var isListening = false


    @Volatile private var isShuttingDown = false
    @Volatile private var speechSessionCancelled = false


    private val recognizerLock = Any()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeWordJob: Job? = null


    private val restoreWakeReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.heylisa.RESTORE_WAKE_WORD") {
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


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("com.example.heylisa.RESTORE_WAKE_WORD")
        registerReceiver(restoreWakeReceiver, filter, RECEIVER_EXPORTED)
        createNotificationChannel()
        createWakeWordAlertChannel()
        startForeground(1, createNotification("Hey Lisa is listening..."))

        serviceScope.launch {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e("HeyLisa", "Microphone permission not granted")
                stopSelfSafely()
                return@launch
            }

            try {
                initModel()
                startWakeWordDetection()
            } catch (e: Exception) {
                Log.e("HeyLisa", "Model init failed", e)
                stopSelfSafely()
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startWakeWordDetection() {
        Log.d("HeyLisa", "🚀 Starting wake word detection... isListening=$isListening, isShuttingDown=$isShuttingDown, modelInit=${::model.isInitialized}")
        if (isListening || isShuttingDown || !::model.isInitialized) return

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
                        Log.d("HeyLisa", "🔸 Partial: $partial")

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

                            Log.d("HeyLisa", "🧹 Cleaned Accumulated: $cleaned | Joined: \"$joined\"")

                            if ("hey lisa" in joined || "he lisa" in joined || "hi lisa" in joined || "hear lisa" in joined || "hey lisa" in fuzzyJoined || "elisa" in joined || "he lisa" in fuzzyJoined || "hi lisa" in fuzzyJoined) {
                                Log.i("HeyLisa", "✅ Wake word detected: $spoken")
                                isListening = false
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@VoskWakeWordService, "Hey Lisa detected!", Toast.LENGTH_SHORT).show()
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
                                    startSpeechRecognition()
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

    private fun startSpeechRecognition() {

        serviceScope.launch {
            var isValidSpeech = false
            try {
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
                val speechBuilder = StringBuilder()
                val silenceTimeout = 3000L
                val maxListenTime = 20000L
                var lastVoiceTime = System.currentTimeMillis()
                val startTime = System.currentTimeMillis()

                record.startRecording()

                repeat(3) {
                    record.read(buffer, 0, buffer.size)
                    delay(50)
                }

                delay(500) // Give user time to start speaking

                var previousPartial = ""
                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                    putExtra("state", "speech_recognition_started")
                })

                while (!isShuttingDown &&
                    System.currentTimeMillis() - lastVoiceTime < silenceTimeout &&
                    System.currentTimeMillis() - startTime < maxListenTime) {

                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0 && read % 2 == 0) {
                        if (isShuttingDown) break
                        synchronized(recognizerLock) {
                            speechRecognizer?.acceptWaveForm(buffer, read)
                        }

                        val partial = speechRecognizer?.partialResult
                        Log.d("HeyLisa", "👂 Partial: $partial")

                        val currentText = Regex("\"partial\"\\s*:\\s*\"(.*?)\"")
                            .find(partial ?: "")?.groupValues?.getOrNull(1)

                        if (!currentText.isNullOrBlank() && currentText != previousPartial) {
                            previousPartial = currentText
                            lastVoiceTime = System.currentTimeMillis()
                            if (currentText !in Noisy.noisyWords) {
                                sendBroadcast(Intent("com.example.heylisa.PARTIAL_TEXT").apply {
                                    putExtra("text", currentText)
                                })
                            } else {
                                Log.d("HeyLisa", "🚫 Ignored noise partial: '$currentText'")
                            }
                        }
                    }

                    delay(10)
                }

                val finalResult = speechRecognizer?.finalResult
                val finalText = Regex("\"text\"\\s*:\\s*\"(.*?)\"").find(finalResult ?: "")?.groupValues?.getOrNull(1)
                if (!finalText.isNullOrBlank()) {
                    speechBuilder.append(finalText)
                }

                record.stop()
                record.release()

                val finalSpeech = cleanRepeatedWords(speechBuilder.toString().trim().lowercase())
                val words = finalSpeech.split(" ").filterNot { it in Noisy.noisyWords }
                isValidSpeech = words.isNotEmpty()

                if (isValidSpeech) {
                    Log.i("HeyLisa", "✅ You said: $finalSpeech")

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "You said: $finalSpeech", Toast.LENGTH_LONG).show()
                    }

                    if (isValidSpeech) {
                        val cleanedFinalSpeech = words.joinToString(" ")
                        sendBroadcast(Intent("com.example.heylisa.RECOGNIZED_TEXT").apply {
                            putExtra("result", cleanedFinalSpeech)
                        })
                    }
                } else {
                    Log.w("HeyLisa", "⚠️ No valid speech or only noise ('$finalSpeech') — returning to wake word")
                }


            } catch (e: Exception) {
                Log.e("HeyLisa", "❌ Speech recognition failed", e)
            } finally {
                closeSpeechRecognizerSafely()
                isListening = false
                delay(200)

                if (!isShuttingDown) {
                    sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))

                    if (speechSessionCancelled) {
                        Log.w("HeyLisa", "⚠️ Main speech session was cancelled — skipping follow-up")
                        speechSessionCancelled = false
                        sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                            putExtra("state", "wake_word_listening")
                        })
                        startWakeWordDetection()
                    } else if (isValidSpeech) {
                        startFollowUpListenerWindow()
                    } else {
                        sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                            putExtra("state", "wake_word_listening")
                        })
                        startWakeWordDetection()
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startFollowUpListenerWindow() {
        serviceScope.launch {
            try {
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

                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("HeyLisa", "Follow-up AudioRecord init failed")
                    return@launch
                }

                val buffer = ByteArray(bufferSize)
                val speechBuilder = StringBuilder()
                val silenceTimeout = 3000L
                val maxListenTime = 20000L
                var lastVoiceTime = System.currentTimeMillis()
                val startTime = System.currentTimeMillis()
                var meaningfulSpeechDetected = false
                var onlyNoiseStart = System.currentTimeMillis()

                record.startRecording()
                repeat(3) { record.read(buffer, 0, buffer.size); delay(50) }
                delay(300)

                var previousPartial = ""
                sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                    putExtra("state", "speech_recognition_started")
                })

                while (!isShuttingDown &&
                    System.currentTimeMillis() - lastVoiceTime < silenceTimeout &&
                    System.currentTimeMillis() - startTime < maxListenTime
                ) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0 && read % 2 == 0) {
                        synchronized(recognizerLock) {
                            speechRecognizer?.acceptWaveForm(buffer, read)
                        }

                        val partial = speechRecognizer?.partialResult
                        val currentText = Regex("\"partial\"\\s*:\\s*\"(.*?)\"")
                            .find(partial ?: "")?.groupValues?.getOrNull(1)?.lowercase()?.trim()

                        if (!currentText.isNullOrBlank() && currentText == previousPartial && currentText in Noisy.noisyWords) {
                            Log.d("HeyLisa", "⏸️ Repeated noise '$currentText' ignored")
                            continue
                        }


                        if (!currentText.isNullOrBlank() && currentText != previousPartial) {
                            previousPartial = currentText

                            val words = currentText.split("\\s+".toRegex())
                            val realWords = words.filterNot { it in Noisy.noisyWords }

                            if (realWords.isNotEmpty()) {
                                lastVoiceTime = System.currentTimeMillis()
                                meaningfulSpeechDetected = true
                                if (realWords.isNotEmpty()) {
                                    lastVoiceTime = System.currentTimeMillis()
                                    meaningfulSpeechDetected = true
                                    onlyNoiseStart = System.currentTimeMillis()
                                } else {
                                    Log.d("HeyLisa", "⚠️ Only noise again: $currentText — timer not reset")
                                }
                                Log.d("HeyLisa", "🗣️ Valid follow-up: $currentText")
                            } else {
                                Log.d("HeyLisa", "⚠️ Ignored noise: $currentText")
                            }

                            if (currentText !in Noisy.noisyWords) {
                                sendBroadcast(Intent("com.example.heylisa.PARTIAL_TEXT").apply {
                                    putExtra("text", currentText)
                                })
                            } else {
                                Log.d("HeyLisa", "🚫 Ignored noise partial: '$currentText'")
                            }
                        }


                        if (!meaningfulSpeechDetected &&
                            System.currentTimeMillis() - onlyNoiseStart > 10_000
                        ) {
                            Log.w("HeyLisa", "🛑 Only noise like 'the' for 10 sec — exiting follow-up")
                            break
                        }
                    }

                    delay(10)
                }

                val finalResult = speechRecognizer?.finalResult
                val finalText = Regex("\"text\"\\s*:\\s*\"(.*?)\"")
                    .find(finalResult ?: "")?.groupValues?.getOrNull(1)

                record.stop()
                record.release()

                val cleanedFinal = cleanRepeatedWords(finalText?.trim().orEmpty().lowercase())

                if (cleanedFinal.isNotBlank() && cleanedFinal !in Noisy.noisyWords) {
                    Log.i("HeyLisa", "✅ Final follow-up: $cleanedFinal")

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "You said: $cleanedFinal", Toast.LENGTH_LONG).show()
                    }

                    sendBroadcast(Intent("com.example.heylisa.RECOGNIZED_TEXT").apply {
                        putExtra("result", cleanedFinal)
                    })

                    delay(300)
                    if (!isShuttingDown) {
                        sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))
                        startFollowUpListenerWindow() // 🔁 Continue follow-up again
                    }
                } else {
                    Log.d("HeyLisa", "⏹️ No valid final speech in follow-up — returning to wake word")
                    if (!isShuttingDown) {
                        sendBroadcast(Intent("com.example.heylisa.CLEAR_TEXT"))
                        sendBroadcast(Intent("com.example.heylisa.STATE_UPDATE").apply {
                            putExtra("state", "wake_word_listening")
                        })
                        startWakeWordDetection()
                    }
                }

            } catch (e: Exception) {
                Log.e("HeyLisa", "❌ Follow-up error", e)
            } finally {
                closeSpeechRecognizerSafely()
            }
        }
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun restartAudioRecordAndRecognizer() {
        // 1. Stop current audio
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        audioRecord?.release()
        audioRecord = null

        // 2. Close current recognizer
        synchronized(recognizerLock) {
            wakeWordRecognizer?.close()
            wakeWordRecognizer = null
        }

        // 3. Recreate recognizer with grammar
        try {
            synchronized(recognizerLock) {
                wakeWordRecognizer = Recognizer(model, 16000.0f, "[\"hey lisa\"]")
            }
        } catch (e: Exception) {
            Log.e("HeyLisa", "Recognizer reinit failed in restartAudioRecordAndRecognizer()", e)
            return
        }

        // 4. Restart listening
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
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "vosk_channel", "Vosk Wake Word Channel", NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
            .setDefaults(Notification.DEFAULT_ALL) // Vibrate, lights, sound
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_CALL) // Can be used with full screen
            .setFullScreenIntent(pendingIntent, true) // 👈 This makes it pop up
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, notification)
    }



    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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



    override fun onDestroy() {
        // 1. Mark service shutting down
        isShuttingDown = true
        unregisterReceiver(restoreWakeReceiver)

        // 2. Clean up all resources
        stopListening()
        closeSpeechRecognizerSafely()
        synchronized(recognizerLock) {
            wakeWordRecognizer?.close()
            wakeWordRecognizer = null
            speechRecognizer = null
        }

        // 3. Close model
        if (::model.isInitialized) {
            model.close()
        }

        // 4. Stop coroutines and foreground
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)

        // 5. Call super last
        super.onDestroy()
    }
}
