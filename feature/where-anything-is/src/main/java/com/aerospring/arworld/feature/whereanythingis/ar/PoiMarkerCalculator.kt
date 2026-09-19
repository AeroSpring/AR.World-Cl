package com.aerospring.arworld.feature.whereanythingis.ar

import com.aerospring.arworld.core.data.geo.GeoMath
import com.aerospring.arworld.core.data.model.Poi
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Готовая к отрисовке точка. arXMeters/arZMeters — это НЕ настоящее расстояние в метрах,
 * а сжатая в удобный для AR-обзора диапазон позиция, вычисленная ИСКЛЮЧИТЕЛЬНО из абсолютной
 * дистанции точки (не зависит от текущего радиуса слайдера!) — иначе при движении слайдера
 * все видимые точки "плавали" бы по сцене, хотя их реальное положение не менялось.
 * distanceMeters — настоящая дистанция, для отображения пользователю.
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

// Реальные расстояния (от метров до 1000 км) сжимаются в этот диапазон AR-сцены по
// ЛОГАРИФМИЧЕСКОЙ шкале от абсолютной дистанции — радиус слайдера тут ни при чём,
// он влияет только на то, какие точки видимы (фильтрация), не на их позицию.
const val AR_MIN_DISPLAY_DISTANCE_METERS = 5f
const val AR_MAX_DISPLAY_DISTANCE_METERS = 60f

object PoiMarkerCalculator {
    fun computeVisibleMarkers(
        userLat: Double,
        userLon: Double,
        pois: List<Poi>,
        minRadiusKm: Double = 0.0,
        radiusKm: Double,
        selectedCategoryIds: Set<String>,
        headingDegrees: Float
    ): List<VisibleMarker> {
        val radiusMeters = radiusKm * 1000.0
        val minRadiusMeters = minRadiusKm * 1000.0
        val headingRad = Math.toRadians(headingDegrees.toDouble())
        val cosH = cos(headingRad)
        val sinH = sin(headingRad)

        return pois
            .filter { it.category in selectedCategoryIds }
            .mapNotNull { poi ->
                val distance = GeoMath.distanceMeters(userLat, userLon, poi.latitude, poi.longitude)
                // Радиус используется ТОЛЬКО для фильтрации видимости — не для позиции в сцене.
                if (distance > radiusMeters || distance < minRadiusMeters) return@mapNotNull null

                val (east, north) = GeoMath.localOffsetMeters(userLat, userLon, poi.latitude, poi.longitude)

                // Логарифмическое сжатие от АБСОЛЮТНОЙ дистанции (0..1000 км), не от текущего радиуса.
                val distanceKm = distance / 1000.0
                val logFraction = (ln(distanceKm + 1.0) / ln(1000.0 + 1.0)).coerceIn(0.0, 1.0)
                val compressedDistance = AR_MIN_DISPLAY_DISTANCE_METERS +
                        (AR_MAX_DISPLAY_DISTANCE_METERS - AR_MIN_DISPLAY_DISTANCE_METERS) * logFraction
                val compressionFactor = if (distance > 0.01) compressedDistance / distance else 1.0
                val compressedEast = east * compressionFactor
                val compressedNorth = north * compressionFactor

                val arX = compressedEast * cosH - compressedNorth * sinH
                val arZ = -(compressedEast * sinH + compressedNorth * cosH)

                // Масштаб модели — тоже от абсолютной дистанции, радиус-независим (без изменений).
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