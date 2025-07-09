package com.example.heylisa.util

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.example.heylisa.R
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException

class VoskWakeWordService : Service() {

    private lateinit var model: Model
    private var wakeWordRecognizer: Recognizer? = null
    private var speechRecognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var wakeWordJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification("Listening for 'Hey Lisa'..."))

        serviceScope.launch {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e("HeyLisa", "Microphone permission not granted")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            try {
                initModel()
                startWakeWordDetection()
            } catch (e: Exception) {
                Log.e("HeyLisa", "Model init failed", e)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun initModel() {
        val modelDir = File(filesDir, "vosk-model")
        if (!modelDir.exists()) throw IOException("Model not found")

        withContext(Dispatchers.IO) {
            withTimeout(60_000) {
                // Optional: delete rnnlm/final.raw to avoid crash
                val rnnlmFile = File("$modelDir/rnnlm/final.raw")
                if (rnnlmFile.exists()) {
                    Log.w("HeyLisa", "Deleting rnnlm/final.raw to prevent crash")
                    rnnlmFile.delete()
                }
                model = Model(modelDir.absolutePath)
            }
        }

        Log.d("HeyLisa", "Wake word recognizer initialized")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startWakeWordDetection() {
        if (isListening) return

        wakeWordRecognizer?.close()
        wakeWordRecognizer = Recognizer(model, 16000.0f)

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
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

        wakeWordJob?.cancel()
        wakeWordJob = CoroutineScope(Dispatchers.Default).launch {
            val startTime = System.currentTimeMillis()
            val timeout = 60_000L

            while (isListening && System.currentTimeMillis() - startTime < timeout) {
                try {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        wakeWordRecognizer?.acceptWaveForm(buffer, read)
                        val partial = wakeWordRecognizer?.partialResult
                        Log.d("HeyLisa", "🔸 Partial: $partial")
                        if (partial?.contains("lisa", ignoreCase = true) == true) {
                            Log.i("HeyLisa", "✅ Wake word detected")
                            isListening = false
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@VoskWakeWordService, "Hey Lisa detected!", Toast.LENGTH_SHORT).show()
                            }
                            stopListening()
                            startSpeechRecognition()
                            return@launch
                        }
                    }
                    delay(10)
                } catch (e: Exception) {
                    Log.e("HeyLisa", "Wake loop error", e)
                    stopListening()
                    break
                }
            }

            stopListening()
        }
    }

    private fun startSpeechRecognition() {
        serviceScope.launch {
            try {
                speechRecognizer?.close()
                delay(100) // ensure it's fully released

                speechRecognizer = Recognizer(model, 16000.0f)

                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )

                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("HeyLisa", "Microphone permission not granted during recognition")
                    stopSelf()
                    return@launch
                }

                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
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

                var previousPartial = ""
                while (System.currentTimeMillis() - lastVoiceTime < silenceTimeout &&
                    System.currentTimeMillis() - startTime < maxListenTime) {

                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        speechRecognizer?.acceptWaveForm(buffer, read)
                        val partial = speechRecognizer?.partialResult
                        Log.d("HeyLisa", "👂 Partial: $partial")

                        val currentText = Regex("\"partial\" ?: ?\"(.*?)\"").find(partial ?: "")?.groupValues?.getOrNull(1)
                        if (!currentText.isNullOrBlank()) {
                            Log.d("HeyLisa", "👂 Interim: $currentText")
                            if (currentText != previousPartial) {
                                previousPartial = currentText
                                lastVoiceTime = System.currentTimeMillis()
                            }
                        }

                    }

                    delay(10)
                }

                val finalResult = speechRecognizer?.finalResult
                val finalText = Regex("\"text\" ?: ?\"(.*?)\"").find(finalResult ?: "")?.groupValues?.getOrNull(1)
                if (!finalText.isNullOrBlank()) {
                    speechBuilder.append(finalText)
                }

                record.stop()
                record.release()

                val finalSpeech = cleanRepeatedWords(speechBuilder.toString().trim())
                Log.i("HeyLisa", "✅ You said: $finalSpeech")

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "You said: $finalSpeech", Toast.LENGTH_LONG).show()
                }


                sendBroadcast(Intent("com.example.heylisa.RECOGNIZED_TEXT").apply {
                    putExtra("result", finalSpeech)
                })

            } catch (e: Exception) {
                Log.e("HeyLisa", "❌ Speech recognition failed", e)
            } finally {
                speechRecognizer?.close()
                speechRecognizer = null
                startWakeWordDetection()
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


    private fun stopListening() {
        isListening = false
        wakeWordJob?.cancel()
        audioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        audioRecord = null
        wakeWordRecognizer?.close()
        wakeWordRecognizer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        speechRecognizer?.close()
        model.close()
        serviceScope.cancel()
        stopForeground(true)
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, "vosk_channel")
            .setContentTitle("Hey Lisa")
            .setContentText(content)
            .setSmallIcon(R.drawable.mic)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "vosk_channel",
            "Vosk Wake Word Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}