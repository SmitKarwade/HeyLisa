package com.example.heylisa.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {
    fun extract(context: Context, assetName: String): File {
        val outDir = File(context.filesDir, assetName)

        try {
            if (outDir.exists() && outDir.list()?.isNotEmpty() == true) {
                return outDir
            }

            val assetManager = context.assets
            copyAssetFolder(assetManager, assetName, outDir.path)

            return outDir
        } catch (e: Exception) {
            Log.e("AssetExtractor", "Extraction failed: ${e.message}", e)
            throw RuntimeException("Extraction failed", e)
        }
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String): Boolean {
        try {
            val files = assetManager.list(fromAssetPath) ?: return false
            File(toPath).mkdirs()

            for (file in files) {
                val assetPath = "$fromAssetPath/$file"
                val destPath = "$toPath/$file"

                if (assetManager.list(assetPath)?.isNotEmpty() == true) {
                    copyAssetFolder(assetManager, assetPath, destPath) // Recursive copy
                } else {
                    val input = assetManager.open(assetPath)
                    val outFile = File(destPath)
                    val output = FileOutputStream(outFile)
                    input.copyTo(output)
                    input.close()
                    output.close()
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("AssetExtractor", "Error copying folder: ${e.message}", e)
            return false
        }
    }
}


