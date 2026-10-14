package com.aerospring.arworld.feature.facades.stand

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    data class Loaded(val references: List<StandReference>, val failed: List<String>) : StandReferencesResult
    data class Failed(val reason: String) : StandReferencesResult
}

internal object StandReferenceRepository {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): StandReferencesResult = withContext(Dispatchers.IO) {
        val base = "${ArWorldServerConfig.BASE_URL}$STAND_REFERENCES_DIR"
        val manifest = try {
            json.decodeFromString(StandManifest.serializer(), String(download("$base/stand-images.json"), Charsets.UTF_8))
        } catch (e: Exception) {
            return@withContext StandReferencesResult.Failed("список эталонов: ${e.javaClass.simpleName}: ${e.message}")
        }
        val loaded = mutableListOf<StandReference>()
        val failed = mutableListOf<String>()
        for (img in manifest.images) {
            try {
                val bytes = download("$base/${img.file}")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("не картинка")
                loaded += StandReference(img.name, bitmap, img.widthMeters)
            } catch (e: Exception) {
                failed += "${img.name} (${e.javaClass.simpleName})"
            }
        }
        StandReferencesResult.Loaded(loaded, failed)
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
