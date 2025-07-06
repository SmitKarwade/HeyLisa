package com.example.heylisa.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import java.util.*

class VoiceInputActivity : ComponentActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val text = mutableStateOf("")

        enableEdgeToEdge()
        setContent {
            LaunchedEffect(Unit) {
                isListening = true
                startSpeechRecognition(
                    onResult = {
                        text.value = it
                        isListening = false
                        //restartWakeWordServiceAndFinish()
                    },
                    onPartial = {
                        text.value = it
                    },
                    onError = {
                        isListening = false
                        //restartWakeWordServiceAndFinish()
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x00000000))
                    .clickable {
                        speechRecognizer.destroy()
                        restartWakeWordService()
                        finish()
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 30.dp)
                        .background(Color(0x00000000))
                        .align(Alignment.BottomCenter)
                        .clickable(enabled = false){},
                    contentAlignment = Alignment.Center
                ) {
                    HeyLisaBar(text = text,
                        onMicClick = {
                            if (!isListening) {
                                text.value = ""
                                isListening = true
                                startSpeechRecognition(
                                    onResult = {
                                        text.value = it
                                        isListening = false
                                        //restartWakeWordServiceAndFinish()
                                    },
                                    onPartial = {
                                        text.value = it
                                    },
                                    onError = {
                                        isListening = false
                                        //restartWakeWordServiceAndFinish()
                                    }
                                )
                            }
                        },
                        onSendClick = {
                            text.value = ""
                        },
                        onTextChange = {
                            text.value = it
                        }
                    )
                }
            }
        }
    }


    private fun startSpeechRecognition(
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit,
        onError: () -> Unit
    ) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.setRecognitionListener(null)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) = onError()

            override fun onResults(results: Bundle?) {
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onResult(result ?: "")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val result = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onPartial(result ?: "")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(intent)
    }

    private fun restartWakeWordService() {
        val serviceIntent = Intent(this, com.example.heylisa.util.VoskWakeWordService::class.java)
        Log.d("VoiceInput", "Requesting VoskWakeWordService restart")
        startService(serviceIntent)
    }


    override fun onDestroy() {
        super.onDestroy()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.cancel()
        }
    }
}
