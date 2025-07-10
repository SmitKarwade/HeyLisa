package com.example.heylisa.custom

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

class LisaVoiceInteractionService : VoiceInteractionService() {

    fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return LisaVoiceSessionService(this)
    }


    override fun onReady() {
        super.onReady()
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}
