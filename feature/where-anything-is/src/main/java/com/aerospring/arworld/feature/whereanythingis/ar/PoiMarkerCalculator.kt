package com.aerospring.arworld.feature.whereanythingis.ar

import com.aerospring.arworld.core.data.geo.GeoMath
import com.aerospring.arworld.core.data.model.Poi
import kotlin.math.cos
import kotlin.math.sin

/**
 * Готовая к отрисовке точка. arXMeters/arZMeters — это НЕ настоящее расстояние в метрах,
 * а сжатая в удобный для AR-обзора диапазон позиция (см. AR_MIN/MAX_DISPLAY_DISTANCE_METERS) —
 * иначе объекты на реальных километрах были бы физически неразличимы на экране.
 * distanceMeters — настоящая дистанция, для отображения пользователю (например, в карточке POI).
 */
data class VisibleMarker(
    val poi: Poi,
    val arXMeters: Float,
    val arZMeters: Float,
    val distanceMeters: Float,
    val scale: Float
)

private const val ABSOLUTE_MAX_RADIUS_METERS = 1000 * 1000.0
private const val MIN_SCALE = 0.25f
private const val MAX_SCALE = 1f

// Реальные расстояния (метры, могут достигать 1000 км) сжимаются в этот диапазон AR-сцены,
// иначе дальние объекты физически неразличимы на экране независимо от масштаба модели.
private const val AR_MIN_DISPLAY_DISTANCE_METERS = 5f
private const val AR_MAX_DISPLAY_DISTANCE_METERS = 60f

object PoiMarkerCalculator {
    fun computeVisibleMarkers(
        userLat: Double,
        userLon: Double,
        pois: List<Poi>,
        radiusKm: Double,
        selectedCategoryIds: Set<String>,
        headingDegrees: Float
    ): List<VisibleMarker> {
        val radiusMeters = radiusKm * 1000.0
        val headingRad = Math.toRadians(headingDegrees.toDouble())
        val cosH = cos(headingRad)
        val sinH = sin(headingRad)

        return pois
            .filter { it.category in selectedCategoryIds }
            .mapNotNull { poi ->
                val distance = GeoMath.distanceMeters(userLat, userLon, poi.latitude, poi.longitude)
                if (distance > radiusMeters) return@mapNotNull null

                val (east, north) = GeoMath.localOffsetMeters(userLat, userLon, poi.latitude, poi.longitude)

                // Сжимаем дистанцию в AR-диапазон, сохраняя направление (просто укорачиваем вектор).
                val distanceFraction = (distance / radiusMeters).coerceIn(0.0, 1.0)
                val compressedDistance = AR_MIN_DISPLAY_DISTANCE_METERS +
                        (AR_MAX_DISPLAY_DISTANCE_METERS - AR_MIN_DISPLAY_DISTANCE_METERS) * distanceFraction
                val compressionFactor = if (distance > 0.01) compressedDistance / distance else 1.0
                val compressedEast = east * compressionFactor
                val compressedNorth = north * compressionFactor

                // Поворот сжатого геосмещения в локальный базис AR-сцены, привязанный к азимуту камеры.
                val arX = compressedEast * cosH - compressedNorth * sinH
                val arZ = -(compressedEast * sinH + compressedNorth * cosH)

                // Масштаб модели по-прежнему считаем от НАСТОЯЩЕЙ дистанции (не сжатой) —
                // так дальние объекты остаются визуально мельче, как и задумано в ТЗ.
                val fraction = (distance / ABSOLUTE_MAX_RADIUS_METERS).toFloat().coerceIn(0f, 1f)
                val distanceScale = MAX_SCALE - (MAX_SCALE - MIN_SCALE) * fraction
                val scale = distanceScale * poi.scaleMultiplier.toFloat()

                VisibleMarker(
                    poi = poi,
                    arXMeters = arX.toFloat(),
                    arZMeters = arZ.toFloat(),
                    distanceMeters = distance.toFloat(),
                    scale = scale
                )
            }
    }
}