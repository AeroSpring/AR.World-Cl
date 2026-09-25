package com.aerospring.arworld.feature.arbc.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class ArBcShowcaseFetchResult {
    data class Success(val clients: List<ShowcaseClient>) : ArBcShowcaseFetchResult()
    data class Error(val message: String) : ArBcShowcaseFetchResult()
}

/**
 * Тянет GET /arbc/showcase — список активных клиентов для витрины внутри раздела
 * AR.Визитки. Тот же стиль, что ArBcRepository (простой HttpURLConnection, в
 * проекте пока нет общего HTTP-слоя для JSON-эндпоинтов).
 */
object ArBcShowcaseRepository {
    private const val BASE_URL = "https://autoknowledge.tech"

    suspend fun fetchShowcase(): ArBcShowcaseFetchResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL("$BASE_URL/arbc/showcase")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.connect()

            if (connection.responseCode != 200) {
                return@withContext ArBcShowcaseFetchResult.Error(
                    "Сервер ответил ${connection.responseCode} — не удалось загрузить витрину"
                )
            }

            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val response = try {
                arBcJson.decodeFromString(ShowcaseResponse.serializer(), raw)
            } catch (e: Exception) {
                return@withContext ArBcShowcaseFetchResult.Error(
                    "Не удалось разобрать ответ витрины: ${e.message}"
                )
            }

            ArBcShowcaseFetchResult.Success(response.clients)
        } catch (e: Exception) {
            ArBcShowcaseFetchResult.Error(e.message ?: "Ошибка загрузки витрины")
        }
    }

    /** previewUrl из ответа сервера относительный — склеиваем с базовым доменом здесь,
     *  а не храним абсолютный URL на сервере (тот не должен знать про свой домен). */
    fun absolutePreviewUrl(relativePreviewUrl: String): String = "$BASE_URL$relativePreviewUrl"
}