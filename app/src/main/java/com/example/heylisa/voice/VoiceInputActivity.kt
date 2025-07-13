package com.example.heylisa.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.example.heylisa.constant.Noisy

class VoiceInputActivity : ComponentActivity() {

    private val isListening = mutableStateOf(false)

    private val partialText = mutableStateOf("")

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.heylisa.PARTIAL_TEXT" -> {
                    val text = intent.getStringExtra("text") ?: return
                    if (text != null && text !in Noisy.noisyWords) {
                        partialText.value = text
                    } else {
                        Log.d("VoiceInputActivity", "🚫 Skipping noisy partial: $text")
                    }
                }
                "com.example.heylisa.CLEAR_TEXT" -> {
                    partialText.value = ""
                }
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.heylisa.STATE_UPDATE") {
                val state = intent.getStringExtra("state") ?: return
                when (state) {
                    "wake_word_detected" -> {
                        // Maybe show some animation or "Listening..." state
                        Log.d("VoiceInputActivity", "Wake word detected")
                    }
                    "speech_recognition_started" -> {
                        isListening.value = true
                        Log.d("VoiceInputActivity", "Speech recognition started")
                    }
                    "wake_word_listening" -> {
                        isListening.value = false
                        Log.d("VoiceInputActivity", "Back to wake word listening")
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val filter = IntentFilter().apply {
            addAction("com.example.heylisa.PARTIAL_TEXT")
            addAction("com.example.heylisa.CLEAR_TEXT")
        }
        registerReceiver(partialReceiver, filter, RECEIVER_EXPORTED)

        val stateFilter = IntentFilter("com.example.heylisa.STATE_UPDATE")
        registerReceiver(stateReceiver, stateFilter, RECEIVER_EXPORTED)


        enableEdgeToEdge()
        setContent {
            LaunchedEffect(Unit) {
                isListening.value = true
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x00000000))
                    .clickable {
                        finish()
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 50.dp)
                        .background(Color(0x00000000))
                        .align(Alignment.BottomCenter)
                        .clickable(enabled = false){},
                    contentAlignment = Alignment.Center
                ) {
                    HeyLisaBar(text = partialText,
                        onMicClick = {

                        },
                        onSendClick = {
                            partialText.value = ""
                        },
                        onTextChange = {
                            partialText.value = it
                        },
                        isListening = isListening.value
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sendBroadcast(Intent("com.example.heylisa.RESTORE_WAKE_WORD"))
        unregisterReceiver(partialReceiver)
        unregisterReceiver(stateReceiver)
    }
}
