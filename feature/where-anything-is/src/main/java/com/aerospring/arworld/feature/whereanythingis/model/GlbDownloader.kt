package com.aerospring.arworld.feature.whereanythingis.model

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

/** Состояние загрузки одной .glb модели. */
sealed class GlbDownloadState {
    data class Progress(val percent: Int, val isMegabytes: Boolean = false) : GlbDownloadState()
    data class Done(val localFile: File) : GlbDownloadState()
    data class Error(val message: String) : GlbDownloadState()
}

/**
 * Скачивает .glb модель во внутренний кэш приложения с отслеживанием прогресса по байтам.
 * Если файл уже скачан ранее (по хешу URL) — сразу отдаёт его, не перекачивая повторно,
 * это и есть "ленивая загрузка" на уровне файлов моделей.
 */
object GlbDownloader {
    fun download(context: Context, url: String): Flow<GlbDownloadState> = callbackFlow {
        val fileName = url.toMd5() + ".glb"
        val cacheFile = File(context.cacheDir, "glb_models/$fileName")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            trySend(GlbDownloadState.Progress(100, isMegabytes = false))
            trySend(GlbDownloadState.Done(cacheFile))
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
                                trySend(GlbDownloadState.Progress(percent))
                            }
                        } else {
                            // Сервер не прислал Content-Length (частый случай для больших файлов) —
                            // показываем мегабайты вместо процентов, лишь бы пользователь видел движение.
                            val mb = downloadedBytes / (1024 * 1024)
                            if (mb != lastReportedPercent) {
                                lastReportedPercent = mb
                                trySend(GlbDownloadState.Progress(mb, isMegabytes = true))
                            }
                        }
                    }
                }
            }

            tempFile.renameTo(cacheFile)
            trySend(GlbDownloadState.Done(cacheFile))
        } catch (e: Exception) {
            tempFile.delete()
            trySend(GlbDownloadState.Error(e.message ?: "Ошибка загрузки модели"))
        }

        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun String.toMd5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}