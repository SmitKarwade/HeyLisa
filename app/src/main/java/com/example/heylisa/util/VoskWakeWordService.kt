package com.example.heylisa.util

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.heylisa.R
import com.example.heylisa.voice.VoiceInputActivity
import org.vosk.Model
import org.vosk.Recognizer


class VoskWakeWordService : Service() {

    private var model: Model? = null
    private var isListening = false

    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.heylisa.RESTART_WAKEWORD") {
                Log.d("VoskWake", "Restarting wake word detection...")
                loadModel()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter("com.example.heylisa.RESTART_WAKEWORD")
        registerReceiver(restartReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        startForeground(101, buildForegroundNotification())
        loadModel()
        Log.d("VoskWake", "Service created")
    }

    private fun loadModel() {
        Thread {
            try {
                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("VoskWake", "RECORD_AUDIO permission not granted.")
                    stopSelf()
                    return@Thread
                }

                model = Model(AssetExtractor.extract(this, "vosk-model-small-en-us-0.15"))
                val recognizer = Recognizer(model, sampleRate.toFloat())

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                val buffer = ByteArray(bufferSize)
                audioRecord.startRecording()
                isListening = true

                while (isListening) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (recognizer.acceptWaveForm(buffer, read)) {
                            val result = recognizer.result
                        } else {
                            val partial = recognizer.partialResult
                            if (partial.contains("hey lisa", ignoreCase = true)) {
                                triggerVoiceInput()
                                break
                            }
                        }
                    }
                }

                audioRecord.stop()
                audioRecord.release()

            } catch (e: Exception) {
                Log.e("VoskWakeWordService", "Error: ${e.message}")
                stopSelf()
            }
        }.start()
    }

    private fun triggerVoiceInput() {
        isListening = false
        Log.d("VoskWake", "Triggering Voice Input...")

        stopSelf()

        val intent = Intent(this, VoiceInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        Handler(Looper.getMainLooper()).post {
            startActivity(intent)
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        model?.close()
        unregisterReceiver(restartReceiver)
    }


    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, "vosk_channel")
            .setContentTitle("HeyLisa Vosk")
            .setContentText("Listening for 'Hey Lisa'...")
            .setSmallIcon(R.drawable.mic)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "vosk_channel",
            "Vosk Wake Word",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}