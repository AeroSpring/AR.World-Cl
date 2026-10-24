package com.aerospring.arworld.feature.facades.data

import android.content.Context
import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Ход скачивания фасада: для «Загрузка N%» и «файл 3 из 7». */
internal data class DownloadProgress(
    val doneBytes: Long,
    val totalBytes: Long,
    val fileIndex: Int,
    val fileCount: Int
) {
    val percent: Int get() = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

/**
 * Копия фасадов на телефоне: один раз скачал — дальше фасад работает без сети
 * (мобильный интернет у фасада может не работать — проверено на стенде).
 *
 *   filesDir/facades/{facadeId}/package.json   — scene.json, каким его отдал сервер
 *   filesDir/facades/{facadeId}/files/…        — эталоны, маска, модели, карточки
 *   filesDir/facades/nearby.json               — последний список «рядом» (для работы без сети)
 *
 * В именах файлов на сервере есть версия ({id}.v{ms}.glb): новый файл — новое имя, поэтому
 * уже скачанное не перекачивается, а старые версии удаляются после успешного обновления.
 * package.json пишется последним: если скачивание прервалось, старая копия остаётся рабочей.
 */
internal class FacadeStore(context: Context, rootName: String = "facades") {
    private val root = File(context.filesDir, rootName).apply { mkdirs() }

    private fun dir(id: String) = File(root, id.replace(Regex("[^A-Za-z0-9_-]"), "_"))
    private fun filesDir(id: String) = File(dir(id), "files")
    private fun packageFile(id: String) = File(dir(id), "package.json")

    /** Локальный файл для ссылки с сервера (имя уникально внутри фасада: в нём id и версия). */
    fun localFile(facadeId: String, url: String): File = File(filesDir(facadeId), url.substringAfterLast('/'))

    fun cachedPackage(id: String): FacadePackage? = try {
        packageFile(id).takeIf { it.isFile }?.let { facadesJson.decodeFromString(FacadePackage.serializer(), it.readText()) }
    } catch (e: Exception) {
        null
    }

    fun cachedPackages(): List<FacadePackage> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
            try { facadesJson.decodeFromString(FacadePackage.serializer(), File(d, "package.json").readText()) } catch (e: Exception) { null }
        }

    /** Скачан ли фасад целиком (package.json и все его файлы на месте). */
    fun isComplete(pkg: FacadePackage): Boolean = pkg.fileUrls().all { localFile(pkg.facadeId, it.url).isFile }

    fun sizeOnPhone(id: String): Long = filesDir(id).listFiles().orEmpty().sumOf { it.length() }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun saveNearby(list: List<NearbyFacade>) {
        try {
            File(root, "nearby.json").writeText(facadesJson.encodeToString(ListSerializer(NearbyFacade.serializer()), list))
        } catch (_: Exception) {
        }
    }

    fun cachedNearby(): List<NearbyFacade> = try {
        facadesJson.decodeFromString(ListSerializer(NearbyFacade.serializer()), File(root, "nearby.json").readText())
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Скачать (или обновить) фасад целиком. Уже скачанные файлы не качаются заново.
     * [onProgress] вызывается из фонового потока.
     */
    suspend fun download(
        facadeId: String,
        /** Предпросмотр монтажника: scene.json?preview=1 со входом — вместе с черновиками. */
        previewToken: String? = null,
        onProgress: (DownloadProgress) -> Unit
    ): FacadePackage =
        withContext(Dispatchers.IO) {
            val url = FacadesApi.sceneJsonUrl(facadeId) + if (previewToken != null) "?preview=1" else ""
            val text = FacadesApi.getText(url, previewToken)
            val pkg = facadesJson.decodeFromString(FacadePackage.serializer(), text)
            val files = pkg.fileUrls()
            val target = filesDir(facadeId).apply { mkdirs() }

            // Итог в байтах: у моделей размер известен заранее, у эталонов и маски — из ответа сервера.
            var total = files.sumOf { f -> localFile(facadeId, f.url).takeIf { it.isFile }?.length() ?: f.knownSize }
            var done = 0L
            files.forEachIndexed { i, f ->
                coroutineContext.ensureActive()
                val local = localFile(facadeId, f.url)
                if (local.isFile) {
                    done += local.length()
                    onProgress(DownloadProgress(done, total, i + 1, files.size))
                    return@forEachIndexed
                }
                val before = done
                FacadesApi.downloadTo(FacadesApi.absolute(f.url), local) { got, length ->
                    if (got == 0L && length > 0 && f.knownSize == 0L) total += length
                    onProgress(DownloadProgress(before + got, maxOf(total, before + got), i + 1, files.size))
                }
                done = before + local.length()
            }
            // Всё на месте — сохраняем описание и убираем файлы, которые больше не нужны.
            val tmp = File(dir(facadeId), "package.json.part")
            tmp.writeText(text)
            if (!tmp.renameTo(packageFile(facadeId))) {
                packageFile(facadeId).delete()
                tmp.renameTo(packageFile(facadeId))
            }
            val keep = files.map { it.url.substringAfterLast('/') }.toSet()
            target.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
            onProgress(DownloadProgress(total, total, files.size, files.size))
            pkg
        }
}

/** Запросы к серверу раздела (HttpURLConnection, как в остальных разделах приложения). */
internal object FacadesApi {
    private val base = ArWorldServerConfig.BASE_URL

    fun absolute(url: String) = if (url.startsWith("http")) url else base + url

    fun sceneJsonUrl(id: String) = "$base/facades/${URLEncoder.encode(id, "UTF-8")}/scene.json"

    suspend fun nearby(lat: Double, lng: Double, radiusM: Int = 3000): List<NearbyFacade> = withContext(Dispatchers.IO) {
        val url = String.format(Locale.US, "%s/facades/nearby?lat=%.6f&lng=%.6f&radiusM=%d", base, lat, lng, radiusM)
        facadesJson.decodeFromString(ListSerializer(NearbyFacade.serializer()), getText(url))
    }

    fun getText(url: String, token: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** Скачать во временный файл и только потом дать итоговое имя (обрыв связи не оставит «полфайла»). */
    suspend fun downloadTo(url: String, target: File, onBytes: (got: Long, length: Long) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        val part = File(target.parentFile, target.name + ".part")
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code: ${target.name}")
            val length = c.contentLengthLong
            onBytes(0L, length)
            var got = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - lastReport >= 128 * 1024) {
                            lastReport = got
                            onBytes(got, length)
                        }
                    }
                }
            }
            if (length > 0 && got != length) throw IOException("Файл скачан не полностью: ${target.name}")
            onBytes(got, length)
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить ${target.name}")
        } finally {
            c.disconnect()
            part.delete()
        }
    }
}
