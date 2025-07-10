package com.example.heylisa.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.zip.ZipInputStream

fun unzipModel(
    context: Context,
    zipFile: File,
    onProgress: (Float) -> Unit,
    onDone: () -> Unit
) {
    val outputDir = File(context.filesDir, "vosk-model")

    // Skip if already unzipped
    if (outputDir.exists() && outputDir.list()?.isNotEmpty() == true) {
        Handler(Looper.getMainLooper()).post { onDone() }
        return
    }

    if (!outputDir.exists()) outputDir.mkdirs()

    Thread {
        val startTime = System.currentTimeMillis()
        try {
            val totalBytes = zipFile.length()
            var extractedBytes = 0L

            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val newFile = File(outputDir, entry.name)

                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        newFile.outputStream().use { fos ->
                            val buffer = ByteArray(4096)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                                extractedBytes += len

                                val progress = (extractedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                Handler(Looper.getMainLooper()).post {
                                    onProgress(progress)
                                }
                            }
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Flatten nested dir like vosk-model-en-us-0.22 inside vosk-model
            val subDirs = outputDir.listFiles()?.filter { it.isDirectory }
            if (subDirs?.size == 1 && subDirs[0].name.startsWith("vosk-model-en")) {
                val nestedDir = subDirs[0]
                nestedDir.listFiles()?.forEach { file ->
                    file.renameTo(File(outputDir, file.name))
                }
                nestedDir.deleteRecursively()
            }

            zipFile.delete()

            val unzipTime = (System.currentTimeMillis() - startTime) / 1000
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Model unzipped in $unzipTime seconds", Toast.LENGTH_SHORT).show()
                onDone()
            }

        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Unzip failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        }
    }.start()
}