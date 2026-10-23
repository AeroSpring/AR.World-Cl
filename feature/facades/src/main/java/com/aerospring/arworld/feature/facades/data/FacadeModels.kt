package com.aerospring.arworld.feature.facades.data

import com.aerospring.arworld.core.data.model.AdMarking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Данные раздела AR.Фасады с сервера. Всё приходит с сервера, в приложение ничего не зашито:
 * новый фасад, сцена или модель — только правка в студии (/facades/studio).
 *
 *   GET /facades/nearby?lat&lng&radiusM   → [NearbyFacade]
 *   GET /facades/{id}/scene.json          → [FacadePackage] — всё для работы фасада без сети
 *
 * Поля с умолчаниями: сервер может добавлять новые поля, старое приложение их пропустит.
 */
internal val facadesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
internal data class NearbyFacade(
    val id: String,
    val title: String,
    val lat: Double,
    val lng: Double,
    val distanceM: Int,
    val discoveryRadiusM: Int = 150,
    /** Меняется при любой правке фасада: по ней телефон понимает, что пора перекачать. */
    val version: Long
)

@Serializable
internal data class FacadePackage(
    val facadeId: String,
    val version: Long,
    val title: String,
    val preview: Boolean = false,
    val geo: FacadeGeo = FacadeGeo(),
    val timezone: String = "Europe/Moscow",
    val references: List<FacadeReference> = emptyList(),
    val mask: FacadeMask? = null,
    val calibration: FacadeCalibration = FacadeCalibration(),
    /** Все включённые сцены с расписанием: телефон сам выбирает сцену по дате, без сети. */
    val scenes: List<FacadeScene> = emptyList(),
    /** Подсказка сервера «что показывать сейчас» на момент скачивания. */
    val activeSceneNow: String? = null,
    val ai: FacadeAi = FacadeAi(),
    /** Режим маркировки раздела: testBanner != null → красная плашка «ТЕСТ». */
    val ad: FacadeAdMode = FacadeAdMode()
) {
    /** Все файлы фасада: эталоны, маска, модели сцен и модели-карточки (openDetail). */
    fun fileUrls(): List<FacadeFile> {
        val out = LinkedHashMap<String, FacadeFile>()
        references.forEach { out[it.url] = FacadeFile(it.url, 0L, FacadeFile.Kind.REFERENCE) }
        mask?.let { out[it.url] = FacadeFile(it.url, 0L, FacadeFile.Kind.MASK) }
        scenes.forEach { s ->
            s.models.forEach { m ->
                out.getOrPut(m.url) { FacadeFile(m.url, m.sizeBytes, FacadeFile.Kind.MODEL) }
                m.interactions.forEach { it.detailAssetUrl()?.let { u -> out.getOrPut(u) { FacadeFile(u, 0L, FacadeFile.Kind.DETAIL) } } }
            }
        }
        return out.values.toList()
    }
}

/** Ссылка на модель-карточку у действия openDetail (сервер подставляет assetUrl). */
internal fun JsonObject.detailAssetUrl(): String? =
    if (this["type"]?.jsonPrimitive?.contentOrNull == "openDetail") this["assetUrl"]?.jsonPrimitive?.contentOrNull else null

internal data class FacadeFile(val url: String, val knownSize: Long, val kind: Kind) {
    enum class Kind { REFERENCE, MASK, MODEL, DETAIL }
}

@Serializable
internal data class FacadeGeo(val lat: Double? = null, val lng: Double? = null, val radiusM: Int = 150)

@Serializable
internal data class FacadeReference(
    val id: String,
    val url: String,
    /** Физическая ширина изображённого на фасаде, метры (задаёт ARCore масштаб и дальность). */
    val widthM: Float,
    val lighting: String = "any",
    /** Где центр эталона в координатах фасада; null — эталон и есть начало координат. */
    val pose: FacadePose? = null
)

@Serializable
internal data class FacadePose(
    val position: List<Float> = listOf(0f, 0f, 0f),
    /** Кватернион x, y, z, w. */
    val rotation: List<Float> = listOf(0f, 0f, 0f, 1f)
)

@Serializable
internal data class FacadeMask(val url: String)

@Serializable
internal data class FacadeCalibration(
    val position: List<Float> = listOf(0f, 0f, 0f),
    val rotationY: Float = 0f,
    val scale: Float = 1f
)

@Serializable
internal data class FacadeScene(
    val id: String,
    val title: String,
    val isDefault: Boolean = false,
    val status: String = "active",
    val schedules: List<FacadeSchedule> = emptyList(),
    val models: List<FacadeSceneModel> = emptyList(),
    /** erid сцены (null — нет). */
    val erid: String? = null,
    /** Готовая строка маркировки; null — строку «Реклама» не рисовать. */
    val ad: AdMarking? = null,
    /** Только в предпросмотре сотрудника: в боевом режиме сцена не покажется. */
    val eridMissing: Boolean = false
)

@Serializable
internal data class FacadeSchedule(
    /** Ежегодное правило — «ММ-ДД», разовое — «ГГГГ-ММ-ДД». */
    val from: String,
    val to: String,
    val yearly: Boolean = false,
    val priority: Int = 0,
    /** 1 = пн … 7 = вс; null — все дни. */
    val daysOfWeek: List<Int>? = null,
    /** «ЧЧ:ММ»; может переходить через полночь. */
    val timeFrom: String? = null,
    val timeTo: String? = null
)

@Serializable
internal data class FacadeSceneModel(
    val id: String,
    val url: String,
    val sizeBytes: Long = 0,
    /** Метры в координатах фасада: X вправо, Y вверх, Z к улице. */
    val position: List<Float> = listOf(0f, 0f, 0f),
    /** Градусы. */
    val rotation: List<Float> = listOf(0f, 0f, 0f),
    val scale: Float = 1f,
    /** {index, mode: loop | once | none} */
    val defaultAnimation: JsonObject? = null,
    /** openUrl {url}, callPhone {phone}, composeEmail {email, subject?}, aiAssistant {},
     *  openDetail {assetId, assetUrl, title?, text?, erid?}; у любого — label?. Разбор — в шаге 3.3. */
    val interactions: List<JsonObject> = emptyList()
)

@Serializable
internal data class FacadeAi(val enabled: Boolean = false)

@Serializable
internal data class FacadeAdMode(
    val mode: String = "on",
    val label: String = "Реклама",
    val testBanner: String? = null
)
