package com.example.heylisa.custom

import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.speech.RecognitionService

class LisaRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        // Not needed if you're handling Vosk separately
    }

    override fun onStopListening(listener: Callback?) {}

    override fun onCancel(listener: Callback?) {}
}
