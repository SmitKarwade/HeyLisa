package com.example.heylisa.util

import android.content.Context
import android.util.Log
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import com.example.heylisa.constant.MY_CONSTANT

class PicovoiceWakeWord(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {

    private var porcupineManager: PorcupineManager? = null

    fun start() {
        try {
            val keywordPath = AssetExtractor.extract(context, "Hey-Lisa_en_android_v3_0_0.ppn")

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(MY_CONSTANT)
                .setKeywordPath(keywordPath)
                .setSensitivity(0.7f)
                .build(context) {
                    Log.d("WakeWord", "Wake word detected!")
                    onWakeWordDetected()
                }

            porcupineManager?.start()
        } catch (e: PorcupineException) {
            e.printStackTrace()
        }
    }

    fun stop() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (e: PorcupineException) {
            e.printStackTrace()
        }
    }
}

