package com.aerospring.arworld.feature.arbc.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class ArBcSceneFetchResult {
    data class Success(val scene: ArBcScene) : ArBcSceneFetchResult()
    data class Error(val message: String) : ArBcSceneFetchResult()
}

/**
 * Тянет scene.json напрямую по адресу /clients/{clientId}/scene.json — тот самый
 * публичный эндпоинт из arbc.py. Пока без Retrofit/Ktor (в проекте ещё нет общего
 * HTTP-клиента для JSON-эндпоинтов — poiDatabase.json тянется как-то иначе, не через
 * этот слой), простым HttpURLConnection, по аналогии с ArbcGlbDownloader.
 */
object ArBcRepository {
    private const val BASE_URL = "https://autoknowledge.tech"

    suspend fun fetchScene(clientId: String): ArBcSceneFetchResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL("$BASE_URL/clients/$clientId/scene.json")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.connect()

            if (connection.responseCode != 200) {
                return@withContext ArBcSceneFetchResult.Error(
                    "Сервер ответил ${connection.responseCode} — AR.Визитка не найдена или сервер недоступен"
                )
            }

            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            android.util.Log.d("ArBcRepository", "raw scene.json for $clientId: $raw")

            val scene = try {
                arBcJson.decodeFromString(ArBcScene.serializer(), raw)
            } catch (e: Exception) {
                android.util.Log.e("ArBcRepository", "decode failed for $clientId", e)
                // Больше не глотаем ошибку молча — реальный текст исключения нужен,
                // чтобы понять, что именно не так с ответом сервера, а не гадать.
                return@withContext ArBcSceneFetchResult.Error(
                    "Не удалось разобрать scene.json: ${e.message}"
                )
            }

            ArBcSceneFetchResult.Success(scene)
        } catch (e: Exception) {
            ArBcSceneFetchResult.Error(e.message ?: "Ошибка загрузки сцены")
        }
    }
}