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
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var wakeWordJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d("HeyLisa", "Channel created")
        startForeground(1, createNotification("Listening for 'Hey Lisa'..."))
        Log.d("HeyLisa", "Now service is started")

        serviceScope.launch {
            Log.d("HeyLisa", "Entered serviceScope.launch")

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e("HeyLisa", "Microphone permission not granted")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            try {
                Log.d("HeyLisa", "Before initModel()")
                initModel()
                Log.d("HeyLisa", "After initModel()")

                startWakeWordDetection()
            } catch (e: Exception) {
                Log.e("HeyLisa", "Initialization failed", e)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun initModel() {
        Log.d("HeyLisa", "Model Initialization started1")
        val modelDir = File(filesDir, "vosk-model")
        Log.d("HeyLisa", "Model Initialization started2")

        if (!modelDir.exists()) {
            Log.e("HeyLisa", "Model directory not found!")
            throw IOException("Model directory not found")
        }

        try {
            withContext(Dispatchers.Default) {
                withTimeout(30_000) { // Up to 30 seconds if model is large
                    Log.d("HeyLisa", "Loading model from path: ${modelDir.absolutePath}")
                    val rnnlmDir = File(modelDir, "rnnlm")
                    if (rnnlmDir.exists()) {
                        Log.w("HeyLisa", "Removing rnnlm model to avoid crash...")
                        rnnlmDir.deleteRecursively()
                    }
                    model = Model(modelDir.absolutePath)
                    Log.d("HeyLisa", "✅ Model loaded successfully")
                }
            }

            Log.d("HeyLisa", "⚙️ Creating Recognizer...")

            try {
                recognizer = Recognizer(model, 16000.0f)
                Log.d("HeyLisa", "✅ Recognizer initialized")
            } catch (e: Exception) {
                Log.e("HeyLisa", "❌ Recognizer init failed", e)
            }
            Log.d("HeyLisa", "✅ Recognizer initialized")

        } catch (e: TimeoutCancellationException) {
            Log.e("HeyLisa", "❌ Model loading timed out", e)
            throw e
        } catch (e: Exception) {
            Log.e("HeyLisa", "❌ Model or recognizer init failed", e)
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startWakeWordDetection() {
        if (isListening) return

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("HeyLisa", "AudioRecord initialization failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val buffer = ByteArray(bufferSize)
        audioRecord?.startRecording()
        isListening = true

        wakeWordJob?.cancel()
        wakeWordJob = CoroutineScope(Dispatchers.Default).launch {
            val startTime = System.currentTimeMillis()
            val timeout = 60_000L // 1 min

            while (isListening && System.currentTimeMillis() - startTime < timeout) {
                try {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        recognizer?.acceptWaveForm(buffer, read)
                        val partial = recognizer?.partialResult
                        if (partial?.contains("hey lisa", ignoreCase = true) == true) {
                            Log.d("HeyLisa", "✅ Wake word detected!")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@VoskWakeWordService, "Hey Lisa detected", Toast.LENGTH_SHORT).show()
                            }
                            isListening = false
                            wakeWordJob?.cancel()
                            startSpeechRecognition()
                            return@launch
                        }
                    }
                    delay(10)
                } catch (e: Exception) {
                    Log.e("HeyLisa", "Wake word loop error", e)
                    stopListening()
                    break
                }
            }

            Log.d("HeyLisa", "Wake word timeout or stopped")
            stopListening()
        }
    }

    private fun startSpeechRecognition() {
        serviceScope.launch {
            try {
                recognizer?.close()
                delay(100)
                recognizer = Recognizer(model, 16000.0f)

                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
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
                    Log.e("HeyLisa", "Speech recognition mic init failed")
                    stopSelf()
                    return@launch
                }

                val buffer = ByteArray(bufferSize)
                record.startRecording()

                val speechBuilder = StringBuilder()
                val timeoutMs = 8000L
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        recognizer?.acceptWaveForm(buffer, read)
                        val result = recognizer?.result
                        val text = Regex("\"text\" ?: ?\"(.*?)\"").find(result ?: "")?.groupValues?.getOrNull(1)
                        if (!text.isNullOrBlank()) {
                            speechBuilder.append(text).append(" ")
                        }
                    }
                }

                record.stop()
                record.release()

                val finalSpeech = speechBuilder.toString().trim()
                Log.d("HeyLisa", "Recognized: $finalSpeech")

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "You said: $finalSpeech", Toast.LENGTH_LONG).show()
                }

                // Send result to app (optional)
                val resultIntent = Intent("com.example.heylisa.RECOGNIZED_TEXT")
                resultIntent.putExtra("result", finalSpeech)
                sendBroadcast(resultIntent)

            } catch (e: Exception) {
                Log.e("HeyLisa", "Speech recognition error", e)
            } finally {
                recognizer?.close()
                recognizer = Recognizer(model, 16000.0f)
                startWakeWordDetection()
            }
        }
    }

    private fun stopListening() {
        isListening = false
        wakeWordJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        recognizer?.close()
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
