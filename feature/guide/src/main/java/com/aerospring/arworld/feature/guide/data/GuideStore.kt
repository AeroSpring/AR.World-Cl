package com.aerospring.arworld.feature.guide.data

import android.content.Context
import android.util.Log
import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/** Ход скачивания здания: для «Загрузка N%». */
internal data class GuideProgress(val doneFiles: Int, val totalFiles: Int, val fileFraction: Float = 0f) {
    /** С учётом доли текущего файла — полоса движется и внутри большого файла, а не прыгает по файлам. */
    val percent: Int get() = if (totalFiles > 0) (((doneFiles + fileFraction) * 100) / totalFiles).toInt().coerceIn(0, 100) else 0
}

/**
 * Копия здания на телефоне: скачал один раз — путеводитель работает без сети
 * (в ТЦ мобильный интернет бывает слабым).
 *
 *   filesDir/guide/{slug}/bundle.json   — здание, каким его отдал сервер
 *   filesDir/guide/{slug}/files/…       — эталоны опорных картинок
 *
 * В именах файлов на сервере есть версия (anchor-a1.v{ms}.png): новый файл — новое имя, поэтому
 * уже скачанное не перекачивается. bundle.json пишется последним: если скачивание прервалось,
 * старая копия остаётся рабочей.
 */
internal class GuideStore(context: Context) {
    private val root = File(context.filesDir, "guide").apply { mkdirs() }

    private fun dir(slug: String) = File(root, slug.replace(Regex("[^A-Za-z0-9_-]"), "_"))
    private fun filesDir(slug: String) = File(dir(slug), "files")
    private fun bundleFile(slug: String) = File(dir(slug), "bundle.json")

    fun localFile(slug: String, url: String): File = File(filesDir(slug), url.substringAfterLast('/'))

    fun cachedBundle(slug: String): GuideBundle? = try {
        bundleFile(slug).takeIf { it.isFile }?.let { guideJson.decodeFromString(GuideBundle.serializer(), it.readText()) }
    } catch (e: Exception) {
        null
    }

    fun cachedBundles(): List<GuideBundle> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { cachedBundle(it.name) }

    /** Скачана ли копия целиком: bundle.json и все эталоны на месте. */
    fun isComplete(b: GuideBundle): Boolean =
        b.anchors.filter { it.imageUrl.isNotBlank() }.all { localFile(b.slug, it.imageUrl).isFile }

    /** Скачивает здание (bundle + эталоны) и возвращает новую копию. */
    suspend fun download(slug: String, onProgress: (GuideProgress) -> Unit): GuideBundle = withContext(Dispatchers.IO) {
        val text = GuideApi.getText(GuideApi.bundleUrl(slug))
        val bundle = guideJson.decodeFromString(GuideBundle.serializer(), text)
        val target = filesDir(slug).apply { mkdirs() }
        val urls = bundle.anchors.map { it.imageUrl }.filter { it.isNotBlank() }.distinct()
        onProgress(GuideProgress(0, urls.size))
        urls.forEachIndexed { i, url ->
            val file = localFile(slug, url)
            if (!file.isFile) GuideApi.downloadTo(GuideApi.absolute(url), file) { f -> onProgress(GuideProgress(i, urls.size, f)) }
            onProgress(GuideProgress(i + 1, urls.size))
        }
        bundleFile(slug).writeText(text)
        val keep = urls.map { it.substringAfterLast('/') }.toSet()
        target.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
        bundle
    }
}

/** Запросы к серверу раздела (HttpURLConnection, как в остальных разделах приложения). */
internal object GuideApi {
    private const val TAG = "GuideApi"
    private val base = ArWorldServerConfig.BASE_URL

    fun absolute(url: String) = if (url.startsWith("http")) url else base + url

    fun bundleUrl(slug: String) = "$base/guide/buildings/${URLEncoder.encode(slug, "UTF-8")}/bundle"

    /** Опубликованные здания. С примерным местом телефона — только те, что рядом (сервер: 50 км), ближние первыми. */
    suspend fun buildings(lat: Double? = null, lon: Double? = null): List<GuideBuildingItem> = withContext(Dispatchers.IO) {
        val q = if (lat != null && lon != null) "?lat=%.4f&lon=%.4f".format(java.util.Locale.US, lat, lon) else ""
        guideJson.decodeFromString(ListSerializer(GuideBuildingItem.serializer()), getText("$base/guide/buildings$q"))
    }

    /** Обезличенная статистика (что искали, куда шли, дошли ли). Ошибки сети молча пропускаем. */
    suspend fun event(slug: String, kind: String, query: String = "", found: Boolean? = null,
                      fromAnchorId: String? = null, toPlaceId: String? = null) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("building", slug)
            put("kind", kind)
            put("query", query.take(100))
            found?.let { put("found", it) }
            fromAnchorId?.let { put("fromAnchorId", it) }
            toPlaceId?.let { put("toPlaceId", it) }
        }.toString()
        try {
            val c = URL("$base/guide/events").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                c.responseCode
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Log.i(TAG, "Статистика не отправлена: ${e.javaClass.simpleName}")
        }
    }

    fun getText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** Скачать во временный файл и только потом дать итоговое имя (обрыв связи не оставит «полфайла»). */
    suspend fun downloadTo(url: String, target: File, onFraction: (Float) -> Unit = {}) {
        val c = URL(url).openConnection() as HttpURLConnection
        val part = File(target.parentFile, target.name + ".part")
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code: ${target.name}")
            val total = c.contentLengthLong
            var got = 0L
            var lastShown = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        // Показываем ход не чаще, чем через каждые ~64 КБ.
                        if (total > 0 && got - lastShown >= 64 * 1024) {
                            lastShown = got
                            onFraction((got.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить ${target.name}")
        } finally {
            c.disconnect()
            part.delete()
        }
    }
}
