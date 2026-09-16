package com.aerospring.arworld.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Точка интереса (POI) — маркер в AR-пространстве.
 * Схема соответствует JSON-файлу на сервере администратора проекта
 * (https://autoknowledge.tech/models/poiDatabase.json).
 */
@Serializable
data class Poi(
    val id: String,
    val title: String,
    @SerialName("lat") val latitude: Double,
    @SerialName("lng") val longitude: Double,
    val category: String,
    @SerialName("url") val modelUrl: String,
    val siteUrl: String? = null,
    val scaleMultiplier: Double = 1.0,
    /** Телефон заказчика — опционально, показывается кнопкой "Позвонить" в карточке POI,
     * если задан. Для заказчиков без сайта это единственный способ оставить контакты. */
    val phone: String? = null,
    /** Ручная коррекция поворота модели вокруг оси X (градусы) — на случай, если .glb
     * экспортирован с другой системой координат ("Z вверх" вместо "Y вверх") и оказывается
     * "завален" на бок/носом вниз. По умолчанию 0 — коррекция не применяется. */
    val rotationXDegrees: Double = 0.0,
    /** Дополнительный подъём модели над стандартной высотой (в метрах). Позволяет заказчику
     * разместить очень крупную модель, не перекрывая соседние объекты — луч всё равно будет
     * честно указывать вниз, на истинное место. По умолчанию 0 — обычная высота, как у всех. */
    val modelExceeding: Double = 0.0
)