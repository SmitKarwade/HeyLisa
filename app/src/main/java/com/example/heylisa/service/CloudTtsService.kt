package com.example.heylisa.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )

                setDataSource(audioFile.absolutePath)

                setOnCompletionListener {
                    isSpeaking = false
                    audioFile.delete() // Clean up temp file
                    context.sendBroadcast(Intent(TTS_FINISHED))
                    Log.d("CloudTtsService", "🔇 TTS finished speaking")
                }

                setOnErrorListener { _, what, extra ->
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