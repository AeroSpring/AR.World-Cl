package com.aerospring.arworld.feature.about

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class ApkDownloadState {
    /** hint — пояснение для пользователя, если загрузка ещё не идёт (очередь, ожидание сети, повтор). */
    data class Progress(val percent: Int, val hint: String? = null) : ApkDownloadState()
    data class Done(val uri: Uri) : ApkDownloadState()
    data class Error(val message: String) : ApkDownloadState()
}

/**
 * Загрузка через системный DownloadManager — тот же механизм, что использует Chrome.
 * Ручная загрузка через HttpURLConnection оказалась медленной и обрывалась на больших файлах
 * (Connection reset / Software caused connection abort) — DownloadManager лишён этих проблем,
 * умеет повторные попытки и не блокируется VPN/фоном так, как наш собственный цикл чтения байт.
 */
object ApkDownloader {
    fun download(context: Context, url: String): Flow<ApkDownloadState> = flow {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Обновление AR.Мир")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update.apk")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadId = downloadManager.enqueue(request)

            while (true) {
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                    ?: run {
                        emit(ApkDownloadState.Error("Не удалось отследить загрузку"))
                        return@flow
                    }

                cursor.use {
                    if (!it.moveToFirst()) {
                        emit(ApkDownloadState.Error("Загрузка не найдена в системе"))
                        return@flow
                    }

                    when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            emit(ApkDownloadState.Done(downloadManager.getUriForDownloadedFile(downloadId)))
                            return@flow
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            emit(ApkDownloadState.Error("Загрузка не удалась (код ошибки $reason)"))
                            return@flow
                        }
                        else -> {
                            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val percent = if (total > 0) {
                                ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            val hint = when (status) {
                                DownloadManager.STATUS_PENDING -> "В очереди на загрузку…"
                                DownloadManager.STATUS_PAUSED -> when (reason) {
                                    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Связь с сервером прервалась, система повторит попытку…"
                                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Ожидание сети…"
                                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Ожидание Wi-Fi…"
                                    else -> "Загрузка приостановлена системой…"
                                }
                                DownloadManager.STATUS_RUNNING -> if (total <= 0) "Подключение к серверу…" else null
                                else -> null
                            }
                            emit(ApkDownloadState.Progress(percent, hint))
                        }
                    }
                }
                delay(400)
            }
        } catch (e: Exception) {
            emit(ApkDownloadState.Error(e.message ?: "Ошибка загрузки обновления"))
        }
    }
}