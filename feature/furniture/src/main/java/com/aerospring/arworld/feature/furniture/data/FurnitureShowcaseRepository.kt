package com.aerospring.arworld.feature.furniture.data

import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** Тип мебели витрины (только непустые — по ним рисуются чипы). */
@Serializable
data class ShowcaseType(
    val id: String,
    val letter: String,
    val name: String,
)

/** Компания-заказчик: куда гость звонит/пишет. Контакты — только заполненные
 *  (phone, email, site, address, telegram, whatsapp, max, vk). */
@Serializable
data class ShowcaseCompany(
    val companyId: String,
    val name: String,
    val contacts: Map<String, String> = emptyMap(),
)

/** Модель витрины. Рабочего названия и контактов дизайнера тут нет и быть не может —
 *  сервер их не отдаёт. */
@Serializable
data class ShowcaseModel(
    val code: String,             // «К-0142» — гость называет его по телефону
    val number: Int,
    val showcaseName: String,
    val furnitureType: String,
    val designerName: String = "",
    val companyId: String,
    val url: String,              // относительный путь, как в каталоге дизайнера
    val previewUrl: String? = null,
    val sizeBytes: Long = 0,
)

@Serializable
data class FurnitureShowcase(
    val types: List<ShowcaseType> = emptyList(),
    val companies: List<ShowcaseCompany> = emptyList(),
    val models: List<ShowcaseModel> = emptyList(),
) {
    fun modelByCode(code: String): ShowcaseModel? = models.firstOrNull { it.code == code }
    fun company(id: String): ShowcaseCompany? = companies.firstOrNull { it.companyId == id }
    fun typeName(id: String): String = types.firstOrNull { it.id == id }?.name.orEmpty()

    /** Для AR-сцены модель витрины — обычная FurnitureModel; modelId = код (он уникален). */
    fun asFurnitureModels(): List<FurnitureModel> = models.map {
        FurnitureModel(
            modelId = it.code,
            modelName = it.showcaseName,
            url = it.url,
            previewUrl = it.previewUrl,
        )
    }
}

sealed class FurnitureShowcaseFetchResult {
    data class Success(val showcase: FurnitureShowcase) : FurnitureShowcaseFetchResult()
    data class Error(val message: String) : FurnitureShowcaseFetchResult()
}

/** Публичная витрина для гостей — без токена. Тот же HttpURLConnection-паттерн. */
object FurnitureShowcaseRepository {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchShowcase(): FurnitureShowcaseFetchResult =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("${ArWorldServerConfig.BASE_URL}/furniture/showcase")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.connect()

                if (connection.responseCode != 200) {
                    return@withContext FurnitureShowcaseFetchResult.Error("Сервер ответил ${connection.responseCode}")
                }
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                FurnitureShowcaseFetchResult.Success(json.decodeFromString(FurnitureShowcase.serializer(), raw))
            } catch (e: Exception) {
                FurnitureShowcaseFetchResult.Error(e.message ?: "Ошибка загрузки витрины")
            }
        }
}