package com.aerospring.arworld.feature.arbc.model

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

/**
 * Состояние загрузки одной .glb модели для AR.Визиток.
 *
 * Намеренная копия GlbDownloadState/GlbDownloader из feature:whereanythingis, а не
 * общая зависимость — чтобы ни одна правка здесь не могла задеть протестированный
 * раздел "Где что находится" (см. правило Aero про риск для стабильных разделов).
 * Если оба варианта долго останутся идентичными, вынос в :core:data — отдельный,
 * осознанный шаг, не сейчас.
 */
sealed class ArbcDownloadState {
    data class Progress(val percent: Int, val isMegabytes: Boolean = false) : ArbcDownloadState()
    data class Done(val localFile: File) : ArbcDownloadState()
    data class Error(val message: String) : ArbcDownloadState()
}

object ArbcGlbDownloader {
    fun download(context: Context, url: String): Flow<ArbcDownloadState> = callbackFlow {
        val fileName = url.toMd5() + ".glb"
        val cacheFile = File(context.cacheDir, "arbc_glb_models/$fileName")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            trySend(ArbcDownloadState.Progress(100, isMegabytes = false))
            trySend(ArbcDownloadState.Done(cacheFile))
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
                                trySend(ArbcDownloadState.Progress(percent))
                            }
                        } else {
                            val mb = downloadedBytes / (1024 * 1024)
                            if (mb != lastReportedPercent) {
                                lastReportedPercent = mb
                                trySend(ArbcDownloadState.Progress(mb, isMegabytes = true))
                            }
                        }
                    }
                }
            }

            tempFile.renameTo(cacheFile)
            trySend(ArbcDownloadState.Done(cacheFile))
        } catch (e: Exception) {
            tempFile.delete()
            trySend(ArbcDownloadState.Error(e.message ?: "Ошибка загрузки модели"))
        }

        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun String.toMd5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}