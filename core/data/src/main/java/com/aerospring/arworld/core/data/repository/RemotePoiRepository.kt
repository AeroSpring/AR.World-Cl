package com.aerospring.arworld.core.data.repository

import com.aerospring.arworld.core.data.model.Poi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** Результат загрузки — явно различаем успех/ошибку, чтобы экран мог показать понятный статус. */
sealed class PoiFetchResult {
    data class Success(val pois: List<Poi>) : PoiFetchResult()
    data class Error(val message: String) : PoiFetchResult()
}

/**
 * Загружает базу POI администратора проекта с сервера.
 * Заменяет SamplePoiRepository — тестовые данные больше не нужны для боевого использования,
 * но сам SamplePoiRepository можно оставить в проекте как fallback для offline-отладки.
 */
object RemotePoiRepository {
    private const val POI_DATABASE_URL = "https://autoknowledge.tech/models/poiDatabase.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchAll(): PoiFetchResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(POI_DATABASE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext PoiFetchResult.Error("Сервер вернул код $responseCode")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val pois = json.decodeFromString<List<Poi>>(body)
            PoiFetchResult.Success(pois)
        } catch (e: Exception) {
            PoiFetchResult.Error(e.message ?: "Неизвестная ошибка загрузки базы POI")
        }
    }
}