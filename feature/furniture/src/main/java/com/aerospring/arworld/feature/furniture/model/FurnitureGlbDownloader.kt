package com.aerospring.arworld.feature.furniture.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Намеренная копия ArbcGlbDownloader — см. комментарий в оригинале про изоляцию разделов. */
sealed class FurnitureDownloadState {
    data class Progress(val percent: Int, val isMegabytes: Boolean = false) : FurnitureDownloadState()
    data class Done(val localFile: File) : FurnitureDownloadState()
    data class Error(val message: String) : FurnitureDownloadState()
}

object FurnitureGlbDownloader {
    fun download(context: Context, url: String): Flow<FurnitureDownloadState> = callbackFlow {
        val fileName = url.toMd5() + ".glb"
        val cacheFile = File(context.cacheDir, "furniture_glb_models/$fileName")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            trySend(FurnitureDownloadState.Progress(100))
            trySend(FurnitureDownloadState.Done(cacheFile))
            close()
            return@callbackFlow
        }

        cacheFile.parentFile?.mkdirs()
        val tempFile = File(cacheFile.parentFile, "$fileName.part")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.connect()

            val totalBytes = connection.contentLength
            var downloadedBytes = 0
            var lastReportedPercent = -1

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val percent = (downloadedBytes * 100 / totalBytes).coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                trySend(FurnitureDownloadState.Progress(percent))
                            }
                        } else {
                            val mb = downloadedBytes / (1024 * 1024)
                            if (mb != lastReportedPercent) {
                                lastReportedPercent = mb
                                trySend(FurnitureDownloadState.Progress(mb, isMegabytes = true))
                            }
                        }
                    }
                }
            }

            tempFile.renameTo(cacheFile)
            trySend(FurnitureDownloadState.Done(cacheFile))
        } catch (e: Exception) {
            tempFile.delete()
            trySend(FurnitureDownloadState.Error(e.message ?: "Ошибка загрузки модели"))
        }

        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun String.toMd5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}