package com.example.heylisa.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsService(context: Context, private val onReady: (() -> Unit)? = null) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TtsService", "The Language specified is not supported!")
            } else {
                isReady = true
                Log.d("TtsService", "TTS Engine is ready.")
                onReady?.invoke() // Call the callback only if it's not null
            }
        } else {
            Log.e("TtsService", "TTS Initialization Failed!")
        }
    }

    fun speak(text: String) {
        if (isReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
        } else {
            Log.e("TtsService", "TTS is not ready, cannot speak.")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isReady = false
        Log.d("TtsService", "TTS Engine has been shut down.")
    }
}