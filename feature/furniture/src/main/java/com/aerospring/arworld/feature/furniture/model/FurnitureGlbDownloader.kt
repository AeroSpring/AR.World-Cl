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

/**
 * Кэш моделей на телефоне.
 *
 * Сервер отдаёт ссылки вида ".../models/{modelId}.glb?v={версия}"; при замене модели
 * дизайнером меняется только версия. Имя файла в кэше = md5(ссылка без ?v) + ".v{версия}.glb":
 * - та же версия → берём из кэша, без скачивания;
 * - новая версия → качаем заново, а все прежние версии этой модели удаляем
 *   (иначе кэш рос бы при каждой замене).
 * Старые файлы до версионирования назывались md5(ссылка) + ".glb" — у них тот же префикс,
 * поэтому они удаляются тем же правилом.
 */
object FurnitureGlbDownloader {
    private const val CACHE_DIR = "furniture_glb_models"

    fun download(context: Context, url: String): Flow<FurnitureDownloadState> = callbackFlow {
        val modelKey = url.substringBefore('?').toMd5()
        val version = url.versionParameter()
        val fileName = "$modelKey.v$version.glb"
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        val cacheFile = File(cacheDir, fileName)

        if (cacheFile.exists() && cacheFile.length() > 0) {
            removeOtherVersions(cacheDir, modelKey, fileName)
            trySend(FurnitureDownloadState.Progress(100))
            trySend(FurnitureDownloadState.Done(cacheFile))
            close()
            return@callbackFlow
        }

        cacheDir.mkdirs()
        val tempFile = File(cacheDir, "$fileName.part")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Сервер ответил ${connection.responseCode}")
            }

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
                            val percent = (downloadedBytes.toLong() * 100 / totalBytes).toInt().coerceIn(0, 100)
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

            if (!tempFile.renameTo(cacheFile)) {
                throw IllegalStateException("Не удалось сохранить модель в кэш")
            }
            removeOtherVersions(cacheDir, modelKey, fileName)
            trySend(FurnitureDownloadState.Done(cacheFile))
        } catch (e: Exception) {
            tempFile.delete()
            trySend(FurnitureDownloadState.Error(e.message ?: "Ошибка загрузки модели"))
        }

        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /**
     * Удаляет прежние версии этой же модели. Незаконченные загрузки (.part) других версий
     * не трогаем — их уберёт следующая успешная загрузка. Удаление файла, который сейчас
     * открыт сценой, на Android безопасно: уже прочитанные данные остаются в памяти.
     */
    private fun removeOtherVersions(cacheDir: File, modelKey: String, keepName: String) {
        cacheDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("$modelKey.") && name != keepName && !name.endsWith(".part")) {
                file.delete()
            }
        }
    }

    /** Значение параметра v из ссылки; только цифры, чтобы попасть в имя файла безопасно. */
    private fun String.versionParameter(): String {
        val query = substringAfter('?', "")
        val raw = query.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=").orEmpty()
        return raw.filter { it.isDigit() }.ifEmpty { "0" }
    }

    private fun String.toMd5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}