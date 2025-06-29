package com.example.heylisa.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {
    fun extract(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }
}
