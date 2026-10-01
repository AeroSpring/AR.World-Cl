package com.aerospring.arworld.feature.about

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class ApkDownloadState {
    data class Progress(val percent: Int) : ApkDownloadState()
    data class Done(val file: File) : ApkDownloadState()
    data class Error(val message: String) : ApkDownloadState()
}

object ApkDownloader {
    fun download(context: Context, url: String): Flow<ApkDownloadState> = callbackFlow {
        val targetDir = File(context.cacheDir, "apk_updates").apply { mkdirs() }
        val targetFile = File(targetDir, "update.apk")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                targetFile.delete()
                trySend(ApkDownloadState.Error("Сервер вернул код $responseCode вместо файла — проверь MIME-тип .apk на сервере"))
                close()
                return@callbackFlow
            }

            val totalBytes = connection.contentLength
            var downloadedBytes = 0
            var lastPercent = -1

            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val percent = (downloadedBytes * 100 / totalBytes).coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                trySend(ApkDownloadState.Progress(percent))
                            }
                        }
                    }
                }
            }

            // Проверка целостности: если сервер заявил размер, а скачали меньше — файл обрезан/битый.
            if (totalBytes > 0 && downloadedBytes < totalBytes) {
                targetFile.delete()
                trySend(ApkDownloadState.Error("Файл скачан не полностью ($downloadedBytes из $totalBytes байт)"))
            } else if (targetFile.length() < 1024) {
                // Настоящий APK весит как минимум сотни КБ — файл в пару байт/КБ почти наверняка
                // не APK, а страница ошибки сервера, отданная с кодом 200.
                targetFile.delete()
                trySend(ApkDownloadState.Error("Скачанный файл слишком мал (${targetFile.length()} байт) — это не похоже на APK"))
            } else {
                trySend(ApkDownloadState.Done(targetFile))
            }
        } catch (e: Exception) {
            targetFile.delete()
            trySend(ApkDownloadState.Error(e.message ?: "Ошибка загрузки обновления"))
        }

        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)
}