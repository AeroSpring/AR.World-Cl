package com.aerospring.arworld.core.data.repository

import com.aerospring.arworld.core.data.model.Poi
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** Результат загрузки — явно различаем успех/ошибку, чтобы экран мог показать понятный статус. */
sealed class PoiFetchResult {
    /** [testBanner] не null — раздел в тестовом режиме маркировки: показать красную плашку. */
    data class Success(val pois: List<Poi>, val testBanner: String? = null) : PoiFetchResult()
    data class Error(val message: String) : PoiFetchResult()
}

/** Ответ GET /poi/objects: режим маркировки раздела и объекты (уже отфильтрованные сервером). */
@Serializable
private data class PoiObjectsResponse(
    val adMode: String = "on",
    val testBanner: String? = null,
    val objects: List<Poi> = emptyList()
)

/**
 * Загружает базу POI с сервера (объекты из базы, с маркировкой рекламы).
 * Заменяет SamplePoiRepository — тестовые данные больше не нужны для боевого использования,
 * но сам SamplePoiRepository можно оставить в проекте как fallback для offline-отладки.
 */
object RemotePoiRepository {
    private const val POI_OBJECTS_URL = "https://autoknowledge.tech/poi/objects"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchAll(): PoiFetchResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(POI_OBJECTS_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext PoiFetchResult.Error("Сервер вернул код $responseCode")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = json.decodeFromString<PoiObjectsResponse>(body)
            PoiFetchResult.Success(response.objects, response.testBanner)
        } catch (e: Exception) {
            PoiFetchResult.Error(e.message ?: "Неизвестная ошибка загрузки базы POI")
        }
    }
}