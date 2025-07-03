package com.example.heylisa.util

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heylisa.R
import com.example.heylisa.voice.VoiceInputActivity

class WakeWordService : Service() {

    private lateinit var wakeWordListener: PicovoiceWakeWord

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
            .setContentTitle("HeyLisa Assistant")
            .setContentText("Listening for 'Hey Lisa'...")
            .setSmallIcon(R.drawable.mic)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        wakeWordListener = PicovoiceWakeWord(
            context = this,
            onWakeWordDetected = {
                Log.d("WakeWordService", "Wake word detected!")
                wakeWordListener.stop()
                if (AppStateObserver.isAppInForeground) {
                    val intent = Intent(this, VoiceInputActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                } else {
                    showVoiceNotification()
                }
            }
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wakeWordListener.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeWordListener.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "HeyLisa Wake Word",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Wake word and speech input notifications"
            enableLights(true)
            enableVibration(false)
//            setSound(soundUri, AudioAttributes.Builder()
//                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
//                .build())
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
            .setContentTitle("👋 Hey Lisa Detected")
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
