package com.example.heylisa.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.texttospeech.v1.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class CloudTtsService(
    private val context: Context,
    private val onInitComplete: (() -> Unit)? = null
) {

    companion object {
        const val TTS_STARTED = "com.example.heylisa.TTS_STARTED"
        const val TTS_FINISHED = "com.example.heylisa.TTS_FINISHED"
        const val TTS_ERROR = "com.example.heylisa.TTS_ERROR"
    }

    private var textToSpeechClient: TextToSpeechClient? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isInitialized = false
    private var isSpeaking = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        initializeCloudTts()
    }

    private fun initializeCloudTts() {
        serviceScope.launch {
            try {
                Log.d("CloudTtsService", "Starting Google Cloud TTS initialization...")

                // Check if credentials file exists
                val credentialsStream: InputStream = try {
                    context.assets.open("heylisa-tts-credentials.json")
                } catch (e: Exception) {
                    Log.e("CloudTtsService", "❌ Credentials file not found in assets!", e)
                    throw e
                }

                Log.d("CloudTtsService", "✅ Credentials file found, loading...")
                val credentials = ServiceAccountCredentials.fromStream(credentialsStream)
                Log.d("CloudTtsService", "✅ Credentials loaded successfully")

                // Build the TTS client
                val settings = TextToSpeechSettings.newBuilder()
                    .setCredentialsProvider { credentials }
                    .build()

                Log.d("CloudTtsService", "Creating TextToSpeechClient...")
                textToSpeechClient = TextToSpeechClient.create(settings)

                Log.d("CloudTtsService", "✅ Google Cloud TTS initialized successfully")
                isInitialized = true

                withContext(Dispatchers.Main) {
                    onInitComplete?.invoke()
                }

            } catch (e: Exception) {
                Log.e("CloudTtsService", "❌ Failed to initialize Google Cloud TTS", e)
                Log.e("CloudTtsService", "Error details: ${e.message}")
                Log.e("CloudTtsService", "Error cause: ${e.cause}")
                isInitialized = false
            }
        }
    }

    fun speak(text: String, voiceName: String = VoiceStyles.MALE_CONFIDENT) {
        Log.d("CloudTtsService", "speak() called with text: '$text', isInitialized: $isInitialized")

        if (!isInitialized) {
            Log.w("CloudTtsService", "❌ TTS not initialized yet - text will be ignored")
            // Optionally, you could queue the text to speak once initialized
            return
        }

        if (text.isBlank()) {
            Log.w("CloudTtsService", "❌ Empty text provided")
            return
        }

        if (isSpeaking) {
            Log.d("CloudTtsService", "🔄 Already speaking - stopping current speech")
            stop()
        }

        serviceScope.launch {
            try {
                isSpeaking = true

                // Notify that TTS started
                context.sendBroadcast(Intent(TTS_STARTED))
                Log.d("CloudTtsService", "🔊 TTS started speaking")

                // Build the synthesis input
                val input = SynthesisInput.newBuilder()
                    .setText(text)
                    .build()

                // Build the voice request
                val voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode("en-US")
                    .setName(voiceName) // Neural2 voices for more human-like speech
                    .build()

                // Select the type of audio file
                val audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.MP3)
                    .setSpeakingRate(1.0) // Normal speed
                    .setPitch(0.0) // Normal pitch
                    .setVolumeGainDb(0.0) // Normal volume
                    .build()

                // Perform the text-to-speech request
                val response = textToSpeechClient!!.synthesizeSpeech(input, voice, audioConfig)

                // Save audio to temporary file
                val audioBytes = response.audioContent.toByteArray()
                val tempFile = File.createTempFile("tts_audio", ".mp3", context.cacheDir)

                withContext(Dispatchers.IO) {
                    FileOutputStream(tempFile).use { it.write(audioBytes) }
                }

                // Play the audio using MediaPlayer
                withContext(Dispatchers.Main) {
                    playAudioFile(tempFile)
                }

            } catch (e: Exception) {
                Log.e("CloudTtsService", "TTS synthesis failed", e)
                isSpeaking = false
                context.sendBroadcast(Intent(TTS_ERROR))
            }
        }
    }

    private fun playAudioFile(audioFile: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                // ✅ Check if screen recording might be active
                val isLikelyRecording = isScreenRecordingLikely()

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(
                            when {
                                // Force to earpiece/call audio when recording detected
                                isLikelyRecording -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                                // Use media for normal operation
                                else -> AudioAttributes.USAGE_MEDIA
                            }
                        )
                        .build()
                )

                // ✅ Force audio routing when recording detected
                if (isLikelyRecording) {
                    // Force to earpiece (less likely to be picked up by screen recorder's mic)
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                    Log.d("CloudTtsService", "📱 Recording detected - TTS routed to earpiece")
                } else {
                    // Normal audio routing
                    audioManager.mode = AudioManager.MODE_NORMAL
                    if (audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn) {
                        Log.d("CloudTtsService", "🎧 Headphones detected - TTS routed to headphones")
                    } else {
                        Log.d("CloudTtsService", "🔊 Normal speaker output")
                    }
                }

                setDataSource(audioFile.absolutePath)

                setOnCompletionListener {
                    if (isLikelyRecording) {
                        audioManager.mode = AudioManager.MODE_NORMAL
                    }
                    isSpeaking = false
                    audioFile.delete()
                    context.sendBroadcast(Intent(TTS_FINISHED))
                    Log.d("CloudTtsService", "🔇 TTS finished speaking")
                }

                setOnErrorListener { _, what, extra ->
                    if (isLikelyRecording) {
                        audioManager.mode = AudioManager.MODE_NORMAL
                    }
                    Log.e("CloudTtsService", "MediaPlayer error: what=$what, extra=$extra")
                    isSpeaking = false
                    audioFile.delete()
                    context.sendBroadcast(Intent(TTS_ERROR))
                    true
                }

                prepareAsync()
                setOnPreparedListener { start() }
            }

        } catch (e: Exception) {
            Log.e("CloudTtsService", "Failed to play audio", e)
            isSpeaking = false
            audioFile.delete()
            context.sendBroadcast(Intent(TTS_ERROR))
        }
    }

    private fun isScreenRecordingLikely(): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Check various indicators that recording might be active
            val hasActiveRecording = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
                    audioManager.isMusicActive ||
                    isScreenRecordingAppRunning()

            // You could also add a manual toggle for demo recordings
            val isManualRecordingMode = getManualRecordingMode()

            hasActiveRecording || isManualRecordingMode
        } catch (e: Exception) {
            false
        }
    }

    private fun isScreenRecordingAppRunning(): Boolean {
        // Check for common screen recording apps
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningApps = activityManager.runningAppProcesses

        val screenRecorderPackages = listOf(
            "com.hecorat.screenrecorder.free",  // AZ Screen Recorder
            "com.mobizen.mirroring.app",        // Mobizen
            "com.duapps.recorder",              // DU Recorder
            "com.android.systemui"              // Built-in recorder
        )

        return runningApps?.any { process ->
            screenRecorderPackages.any { pkg ->
                process.processName.contains(pkg, ignoreCase = true)
            }
        } ?: false
    }

    private fun getManualRecordingMode(): Boolean {
        // You could add a setting/preference for this
        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("demo_recording_mode", false)
    }

    fun stop() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        isSpeaking = false
        context.sendBroadcast(Intent(TTS_FINISHED))
        Log.d("CloudTtsService", "TTS stopped")
    }

    fun isSpeaking(): Boolean = isSpeaking

    fun shutdown() {
        stop()
        textToSpeechClient?.close()
        serviceScope.cancel()
        isInitialized = false
        Log.d("CloudTtsService", "Cloud TTS shutdown")
    }

    // Available voice options for different styles
    object VoiceStyles {
        const val MALE_WARM = "en-US-Neural2-A"      // Warm female voice
        const val MALE_CONFIDENT = "en-US-Neural2-D"    // Confident male voice
        const val FEMALE_PROFESSIONAL = "en-US-Neural2-E" // Professional female
        const val MALE_FRIENDLY = "en-US-Neural2-J"     // Friendly male voice
        const val FEMALE_CASUAL = "en-US-Neural2-F"     // Casual female voice
    }
}