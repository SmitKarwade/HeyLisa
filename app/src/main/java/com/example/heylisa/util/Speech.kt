package com.example.heylisa.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

private var speechRecognizer: SpeechRecognizer? = null

fun startContinuousSpeechRecognition(
    context: Context,
    restartWakeWord: () -> Unit,
    onResultUpdate: (partialText: String, finalText: String?) -> Unit
) {
    if (speechRecognizer == null)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

    val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            Log.d("SpeechRecognizer", "Error: $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    speechRecognizer?.cancel()
                    stopContinuousSpeechRecognition()
                    restartWakeWord()
                }
                else -> {
                    speechRecognizer?.cancel()
                    Handler(Looper.getMainLooper()).postDelayed({
                        speechRecognizer?.startListening(recognizerIntent)
                    }, 500)
                }
            }
        }


        override fun onResults(results: Bundle?) {
            val finalText = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()

            onResultUpdate("", finalText ?: "")
            speechRecognizer?.cancel()
            Handler(Looper.getMainLooper()).postDelayed({
                speechRecognizer?.startListening(recognizerIntent)
            }, 500)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partialText = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()

            onResultUpdate(partialText ?: "", null)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    speechRecognizer?.setRecognitionListener(listener)
    speechRecognizer?.startListening(recognizerIntent)
}

fun stopContinuousSpeechRecognition() {
    speechRecognizer?.stopListening()
    speechRecognizer?.cancel()
    speechRecognizer?.destroy()
    speechRecognizer = null
}

