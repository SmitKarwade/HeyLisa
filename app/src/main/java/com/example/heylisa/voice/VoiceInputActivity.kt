package com.example.heylisa.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.heylisa.util.WakeWordService
import java.util.*

class VoiceInputActivity : ComponentActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var text by remember { mutableStateOf("Listening...") }

            LaunchedEffect(Unit) {
                isListening = true
                startSpeechRecognition(
                    onResult = {
                        text = it
                        isListening = false
                        //restartWakeWordServiceAndFinish()
                    },
                    onPartial = {
                        text = it
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
                    .background(Color(0xAA000000)),
                contentAlignment = Alignment.Center
            ) {
                HeyLisaBar(text = text.ifEmpty { "Ask Lisa" },
                    onMicClick = {
                        if (!isListening) {
                            text = ""
                            isListening = true
                            startSpeechRecognition(
                                onResult = {
                                    text = it
                                    isListening = false
                                    //restartWakeWordServiceAndFinish()
                                },
                                onPartial = {
                                    text = it
                                },
                                onError = {
                                    isListening = false
                                    //restartWakeWordServiceAndFinish()
                                }
                            )
                        }
                    }
                )
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

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
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

    private fun restartWakeWordServiceAndFinish() {
        Handler(Looper.getMainLooper()).postDelayed({
            val serviceIntent = Intent(this, WakeWordService::class.java)
            startForegroundService(serviceIntent)
            finish()
        }, 1500)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        restartWakeWordServiceAndFinish()
    }
}
