package com.aerospring.arworld.feature.facades.stand

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Папка в «Загрузках», куда стенд кладёт журналы, записи сессий и снятые эталоны. */
internal const val STAND_DOWNLOADS_SUBDIR = "AR.World"

/**
 * Сохраняет текстовый журнал замеров в «Загрузки/AR.World/<имя>».
 * Возвращает понятный путь для сообщения или null при ошибке.
 */
internal fun saveTextToDownloads(context: Context, fileName: String, text: String): String? =
    saveBytesToDownloads(context, STAND_DOWNLOADS_SUBDIR, fileName, "text/plain", text.toByteArray(Charsets.UTF_8))

/** Любой файл в «Загрузки/<subdir>/<имя>». Возвращает понятный путь или null. */
internal fun saveBytesToDownloads(
    context: Context,
    subdir: String,
    fileName: String,
    mimeType: String,
    bytes: ByteArray
): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subdir")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("open failed")
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subdir)
        dir.mkdirs()
        File(dir, fileName).writeBytes(bytes)
    }
    "Загрузки/$subdir/$fileName"
} catch (e: Exception) {
    null
}
