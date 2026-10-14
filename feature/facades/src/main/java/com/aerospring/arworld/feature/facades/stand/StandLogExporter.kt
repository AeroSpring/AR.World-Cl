package com.aerospring.arworld.feature.facades.stand

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Папка в «Загрузках», куда стенд кладёт журналы и записи сессий. */
internal const val STAND_DOWNLOADS_SUBDIR = "AR.World"

/**
 * Сохраняет текстовый журнал замеров в «Загрузки/AR.World/<имя>».
 * Возвращает понятный путь для сообщения или null при ошибке.
 */
internal fun saveTextToDownloads(context: Context, fileName: String, text: String): String? = try {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$STAND_DOWNLOADS_SUBDIR")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("open failed")
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), STAND_DOWNLOADS_SUBDIR)
        dir.mkdirs()
        File(dir, fileName).writeBytes(bytes)
    }
    "Загрузки/$STAND_DOWNLOADS_SUBDIR/$fileName"
} catch (e: Exception) {
    null
}
