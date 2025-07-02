package com.example.heylisa.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heylisa.R
import com.example.heylisa.util.PicovoiceWakeWord

class WakeWordService : Service() {

    private lateinit var wakeWordListener: PicovoiceWakeWord

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        // Build notification for foreground
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HeyLisa Assistant")
            .setContentText("Listening for 'Hey Lisa'...")
            .setSmallIcon(R.drawable.mic)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        wakeWordListener = PicovoiceWakeWord(
            context = this,
            onWakeWordDetected = {
                wakeWordListener.stop()

                // Start transparent activity for speech recognition
                val intent = Intent(this, com.example.heylisa.voice.VoiceInputActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        )

        wakeWordListener.start()
        Log.d("WakeWordService", "Wake word service started.")
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeWordListener.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HeyLisa Wake Word",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "hey_lisa_wake_word_channel"
        private const val NOTIFICATION_ID = 101
    }
}
