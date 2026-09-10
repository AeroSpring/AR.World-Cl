package com.aerospring.arworld.feature.whereanythingis.ar

import com.aerospring.arworld.core.data.geo.GeoMath
import com.aerospring.arworld.core.data.model.Poi
import kotlin.math.cos
import kotlin.math.sin

/** Готовая к отрисовке точка: координаты уже в локальном базисе AR-сцены (с учётом поворота на азимут). */
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

object PoiMarkerCalculator {
    /**
     * @param headingDegrees азимут камеры в момент старта AR-сессии (калибровка компаса) —
     * без него east/north смещения POI указывают в случайную сторону относительно AR-сцены.
     */
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

                // Поворот геосмещения в локальный базис AR-сцены, привязанный к начальному азимуту камеры.
                val arX = east * cosH - north * sinH
                val arZ = -(east * sinH + north * cosH)

                val fraction = (distance / ABSOLUTE_MAX_RADIUS_METERS).toFloat().coerceIn(0f, 1f)
                val scale = MAX_SCALE - (MAX_SCALE - MIN_SCALE) * fraction

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