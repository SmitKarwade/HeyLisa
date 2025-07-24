package com.example.heylisa.service

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class TtsService(
    private val context: Context,
    private val onInitComplete: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    companion object {
        const val TTS_STARTED = "com.example.heylisa.TTS_STARTED"
        const val TTS_FINISHED = "com.example.heylisa.TTS_FINISHED"
        const val TTS_ERROR = "com.example.heylisa.TTS_ERROR"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isSpeaking = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { textToSpeech ->
                val result = textToSpeech.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TtsService", "Language not supported")
                } else {
                    isInitialized = true
                    setupUtteranceProgressListener()
                    Log.d("TtsService", "TTS initialized successfully")
                    onInitComplete?.invoke()
                }
            }
        } else {
            Log.e("TtsService", "TTS initialization failed")
        }
    }

    private fun setupUtteranceProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                Log.d("TtsService", "🔊 TTS started speaking")
                context.sendBroadcast(Intent(TTS_STARTED))
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                Log.d("TtsService", "🔇 TTS finished speaking")
                context.sendBroadcast(Intent(TTS_FINISHED))
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                Log.e("TtsService", "❌ TTS error occurred")
                context.sendBroadcast(Intent(TTS_ERROR))
            }
        })
    }

    fun speak(text: String) {
        if (!isInitialized) {
            Log.w("TtsService", "TTS not initialized yet")
            return
        }

        if (text.isBlank()) {
            Log.w("TtsService", "Empty text provided to TTS")
            return
        }

        Log.d("TtsService", "Speaking: $text")
        val utteranceId = UUID.randomUUID().toString()

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        if (isSpeaking) {
            tts?.stop()
            isSpeaking = false
            Log.d("TtsService", "TTS stopped")
            context.sendBroadcast(Intent(TTS_FINISHED))
        }
    }

    fun isSpeaking(): Boolean = isSpeaking

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        isSpeaking = false
        Log.d("TtsService", "TTS shutdown")
    }
}