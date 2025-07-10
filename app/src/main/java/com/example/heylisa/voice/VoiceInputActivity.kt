package com.example.heylisa.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
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

class VoiceInputActivity : ComponentActivity() {

    private var isListening = false

    private val partialText = mutableStateOf("")

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.heylisa.PARTIAL_TEXT" -> {
                    val text = intent.getStringExtra("text") ?: return
                    partialText.value = text
                }
                "com.example.heylisa.CLEAR_TEXT" -> {
                    partialText.value = ""
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
        registerReceiver(partialReceiver, filter, Context.RECEIVER_EXPORTED)

        enableEdgeToEdge()
        setContent {
            LaunchedEffect(Unit) {
                isListening = true
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
                            if (!isListening) {
                                partialText.value = ""
                                isListening = true
                            }
                        },
                        onSendClick = {
                            partialText.value = ""
                        },
                        onTextChange = {
                            partialText.value = it
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(partialReceiver)
    }
}
