package com.example.heylisa.custom

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.service.voice.VoiceInteractionSession
import android.app.assist.AssistStructure
import android.app.assist.AssistContent
import android.content.Intent
import com.example.heylisa.voice.VoiceInputActivity

class LisaVoiceSessionService(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
    }

    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        // Do something when the Assistant is triggered
        val intent = Intent(context, VoiceInputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

    }
}

