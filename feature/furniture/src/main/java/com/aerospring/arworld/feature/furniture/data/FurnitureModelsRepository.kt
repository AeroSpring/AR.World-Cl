package com.aerospring.arworld.feature.furniture.data

import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class FurnitureModel(
    val modelId: String,
    val modelName: String,
    val url: String, // относительный путь — для скачивания .glb нужно подставить ArWorldServerConfig.BASE_URL
    val previewUrl: String? = null, // тоже относительный; null, если превью ещё не загружено
)

@Serializable
private data class ModelsResponseBody(val models: List<FurnitureModel>)

sealed class FurnitureModelsFetchResult {
    data class Success(val models: List<FurnitureModel>) : FurnitureModelsFetchResult()
    data object Unauthorized : FurnitureModelsFetchResult()
    data class Error(val message: String) : FurnitureModelsFetchResult()
}

/** Каталог моделей текущего дизайнера. Тот же HttpURLConnection-паттерн. */
object FurnitureModelsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchModels(token: String): FurnitureModelsFetchResult =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("${ArWorldServerConfig.BASE_URL}/furniture/models")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.connect()

                if (connection.responseCode == 401) {
                    return@withContext FurnitureModelsFetchResult.Unauthorized
                }
                if (connection.responseCode != 200) {
                    return@withContext FurnitureModelsFetchResult.Error("Сервер ответил ${connection.responseCode}")
                }

                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString(ModelsResponseBody.serializer(), raw)
                FurnitureModelsFetchResult.Success(parsed.models)
            } catch (e: Exception) {
                FurnitureModelsFetchResult.Error(e.message ?: "Ошибка загрузки каталога")
            }
        }
}