package com.example.heylisa.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class CustomTtsService : Service() {

    companion object {
        const val TTS_STARTED = "com.example.heylisa.TTS_STARTED"
        const val TTS_FINISHED = "com.example.heylisa.TTS_FINISHED"
        const val TTS_ERROR = "com.example.heylisa.TTS_ERROR"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val client = OkHttpClient()
    private var mediaPlayer: MediaPlayer? = null

    // Replace with your actual API endpoint and configuration
    private val TTS_API_URL = "https://api.neeja.io/tts/speak/" // Replace with your API URL

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text")
        if (!text.isNullOrEmpty()) {
            serviceScope.launch {
                speak(text)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun speak(text: String) {
        try {
            Log.d("CustomTtsService", "🔊 Starting TTS for: $text")

            // Send TTS started broadcast
            sendBroadcast(Intent(TTS_STARTED))

            // Make API request to get audio
            val audioFile = requestTtsAudio(text)

            if (audioFile != null) {
                playAudio(audioFile)
            } else {
                throw Exception("Failed to generate audio")
            }

        } catch (e: Exception) {
            Log.e("CustomTtsService", "TTS synthesis failed", e)
            sendBroadcast(Intent(TTS_ERROR))
        }
    }

    private suspend fun requestTtsAudio(text: String): File? = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("text", text)
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(TTS_API_URL)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                // Add other headers as required by your API
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body
                if (responseBody != null) {
                    val audioFile = File(cacheDir, "tts_audio_${System.currentTimeMillis()}.mp3")
                    audioFile.writeBytes(responseBody.bytes())
                    Log.d("CustomTtsService", "✅ Audio file saved: ${audioFile.absolutePath}")
                    return@withContext audioFile
                }
            } else {
                Log.e("CustomTtsService", "API request failed: ${response.code} - ${response.message}")
            }

        } catch (e: Exception) {
            Log.e("CustomTtsService", "Error making TTS API request", e)
        }

        return@withContext null
    }

    private suspend fun playAudio(audioFile: File) = withContext(Dispatchers.Main) {
        try {
            mediaPlayer?.release()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )

                setDataSource(audioFile.absolutePath)

                setOnPreparedListener { mp ->
                    Log.d("CustomTtsService", "🎵 Starting audio playback")
                    mp.start()
                }

                setOnCompletionListener { mp ->
                    Log.d("CustomTtsService", "🔇 Audio playback completed")
                    mp.release()
                    mediaPlayer = null

                    if (audioFile.exists()) {
                        audioFile.delete()
                    }


                    sendBroadcast(Intent(TTS_FINISHED))

                    stopSelf()
                }

                setOnErrorListener { mp, what, extra ->
                    Log.e("CustomTtsService", "MediaPlayer error: what=$what, extra=$extra")
                    mp.release()
                    mediaPlayer = null

                    if (audioFile.exists()) {
                        audioFile.delete()
                    }

                    sendBroadcast(Intent(TTS_ERROR))
                    stopSelf()
                    true
                }

                prepareAsync()
            }

        } catch (e: Exception) {
            Log.e("CustomTtsService", "Error playing audio", e)

            if (audioFile.exists()) {
                audioFile.delete()
            }

            sendBroadcast(Intent(TTS_ERROR))
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        serviceScope.cancel()
        Log.d("CustomTtsService", "🛑 CustomTtsService destroyed")
    }
}
