package com.aerospring.arworld.feature.facades.stand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Эталонные снимки фасада для стенда. Лежат на сервере, в приложение не зашиваются:
 * новый фасад или новый снимок — только правка на сервере.
 *
 * Временно (стенд): обычная статика `/models/facades-test/`, без кода на сервере.
 * В рабочей версии раздела снимки будут приходить в scene.json фасада.
 */
internal const val STAND_REFERENCES_DIR = "/models/facades-test"

@Serializable
private data class StandManifest(val images: List<StandManifestImage> = emptyList())

@Serializable
private data class StandManifestImage(
    val name: String,
    val file: String,
    /** Физическая ширина изображения на фасаде, метры. Сильно помогает ARCore на больших изображениях. */
    val widthMeters: Float
)

/** Загруженный эталон: имя, картинка, физическая ширина. */
internal class StandReference(val name: String, val bitmap: Bitmap, val widthMeters: Float)

internal sealed interface StandReferencesResult {
    /** [fromCache] = сервер недоступен, эталоны взяты из копии на телефоне. */
    data class Loaded(
        val references: List<StandReference>,
        val failed: List<String>,
        val fromCache: Boolean,
        val networkError: String?
    ) : StandReferencesResult
    data class Failed(val reason: String) : StandReferencesResult
}

/**
 * Сначала сервер; при успехе всё сохраняется в копию на телефоне.
 * Если сервер недоступен (нет сети, ограничения мобильного интернета) — берём копию.
 * Это тот же принцип, что будет в рабочей версии: один раз скачал — дальше работает без сети.
 */
internal object StandReferenceRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MANIFEST = "stand-images.json"

    private fun cacheDir(context: Context) = File(context.filesDir, "facades-stand-cache").apply { mkdirs() }

    suspend fun load(context: Context): StandReferencesResult = withContext(Dispatchers.IO) {
        val base = "${ArWorldServerConfig.BASE_URL}$STAND_REFERENCES_DIR"
        val cache = cacheDir(context)
        var networkError = ""
        try {
            val manifestBytes = download("$base/$MANIFEST")
            val manifest = json.decodeFromString(StandManifest.serializer(), String(manifestBytes, Charsets.UTF_8))
            val loaded = mutableListOf<StandReference>()
            val failed = mutableListOf<String>()
            for (img in manifest.images) {
                try {
                    val bytes = download("$base/${img.file}")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("не картинка")
                    File(cache, img.file).writeBytes(bytes)
                    loaded += StandReference(img.name, bitmap, img.widthMeters)
                } catch (e: Exception) {
                    failed += "${img.name} (${e.javaClass.simpleName})"
                }
            }
            if (loaded.isNotEmpty()) File(cache, MANIFEST).writeBytes(manifestBytes)
            return@withContext StandReferencesResult.Loaded(loaded, failed, fromCache = false, networkError = null)
        } catch (e: Exception) {
            networkError = "${e.javaClass.simpleName}: ${e.message}"
        }
        // Сервер недоступен — пробуем копию на телефоне.
        try {
            val manifestFile = File(cache, MANIFEST)
            if (!manifestFile.exists()) {
                return@withContext StandReferencesResult.Failed("сервер недоступен ($networkError), копии на телефоне ещё нет")
            }
            val manifest = json.decodeFromString(StandManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
            val loaded = mutableListOf<StandReference>()
            val failed = mutableListOf<String>()
            for (img in manifest.images) {
                val bitmap = File(cache, img.file).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
                if (bitmap != null) loaded += StandReference(img.name, bitmap, img.widthMeters) else failed += img.name
            }
            StandReferencesResult.Loaded(loaded, failed, fromCache = true, networkError = networkError)
        } catch (e: Exception) {
            StandReferencesResult.Failed("сервер недоступен ($networkError), копия повреждена (${e.javaClass.simpleName})")
        }
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
