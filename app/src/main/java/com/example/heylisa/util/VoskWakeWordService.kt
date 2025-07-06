package com.example.heylisa.util

import android.app.*
import android.content.Intent
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


    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        startForeground(NOTIFICATION_ID, buildForegroundNotification())
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

        Handler(Looper.getMainLooper()).postDelayed({
            stopSelf()
        }, 3000)


        if (AppStateObserver.isAppInForeground) {
            val intent = Intent(this, VoiceInputActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } else {
            showVoiceNotification()
        }
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("service", "destroyed")
        isListening = false
        model?.close()
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
            CHANNEL_ID,
            "HeyLisa Wake Word",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Wake word and speech input notifications"
            enableLights(true)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val silentChannel = NotificationChannel(
            SILENT_CHANNEL_ID,
            "HeyLisa running in background...",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Silent background notifications"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(silentChannel)
    }


    private fun showVoiceNotification() {
        val intent = Intent(this, VoiceInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hey Lisa Detected")
            .setContentText("Tap to speak your command")
            .setSmallIcon(R.drawable.mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setFullScreenIntent(pendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .addAction(R.drawable.mic, "Listen Now", pendingIntent)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(VOICE_NOTIFICATION_ID, notification)
    }



    companion object {
        private const val CHANNEL_ID = "hey_lisa_voice_channel"
        private const val SILENT_CHANNEL_ID = "hey_lisa_silent_channel"
        private const val NOTIFICATION_ID = 101
        private const val VOICE_NOTIFICATION_ID = 102
    }
}