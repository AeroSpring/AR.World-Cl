package com.aerospring.arworld.feature.facades.stand

import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.SemanticLabel
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteOrder

/**
 * Замеры стенда. Каждая функция возвращает одну строку для экрана и журнала
 * и никогда не бросает исключение: ошибка превращается в текст.
 */

/**
 * Глубина в центре кадра (среднее 5×5 по карте глубины ARCore) и, для сравнения,
 * расстояние до плоскости, в которую попадает центр экрана (стена или пол).
 * Код перенесён из диагностики AR.Мебели, где он проверен.
 */
internal fun probeDepthAtCenter(frame: Frame, viewW: Int, viewH: Int): String {
    val planeText = run {
        if (viewW <= 0 || viewH <= 0) return@run null
        val hit = try {
            frame.hitTest(viewW / 2f, viewH / 2f).firstOrNull { r ->
                val t = r.trackable
                t is Plane && t.isPoseInPolygon(r.hitPose)
            }
        } catch (e: Exception) {
            null
        } ?: return@run null
        val kind = if ((hit.trackable as Plane).type == Plane.Type.VERTICAL) "стена" else "пол"
        kind to hit.distance
    }
    val depthM: Float? = try {
        frame.acquireDepthImage16Bits().use { img ->
            val plane = img.planes[0]
            val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val cx = img.width / 2
            val cy = img.height / 2
            var sum = 0L
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val x = (cx + dx).coerceIn(0, img.width - 1)
                val y = (cy + dy).coerceIn(0, img.height - 1)
                val mm = buf.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFFFF
                if (mm > 0) { sum += mm; n++ }
            }
            if (n == 0) null else sum / n / 1000f
        }
    } catch (e: Exception) {
        null
    }
    val d = depthM?.let { "%.2f м".format(it) } ?: "нет"
    val p = planeText?.let { "%s %.2f м".format(it.first, it.second) } ?: "нет плоскости"
    val diff = if (depthM != null && planeText != null) {
        "  разница %+.0f см".format((planeText.second - depthM) * 100f)
    } else ""
    return "Глубина в центре: $d · $p$diff"
}

private val LABEL_NAMES = mapOf(
    SemanticLabel.SKY to "небо",
    SemanticLabel.BUILDING to "здание",
    SemanticLabel.TREE to "дерево",
    SemanticLabel.ROAD to "дорога",
    SemanticLabel.SIDEWALK to "тротуар",
    SemanticLabel.TERRAIN to "земля",
    SemanticLabel.STRUCTURE to "конструкция",
    SemanticLabel.OBJECT to "предмет",
    SemanticLabel.VEHICLE to "машина",
    SemanticLabel.PERSON to "человек",
    SemanticLabel.WATER to "вода",
    SemanticLabel.UNLABELED to "?"
)

/**
 * Scene Semantics: какой класс в центре кадра и доли главных классов во всём кадре.
 * Модель ARCore рассчитана на улицу и портретную ориентацию.
 */
internal fun probeSemantics(frame: Frame): String {
    val center: SemanticLabel? = try {
        frame.acquireSemanticImage().use { img ->
            val plane = img.planes[0]
            val x = img.width / 2
            val y = img.height / 2
            val ordinal = plane.buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
            SemanticLabel.values().getOrNull(ordinal)
        }
    } catch (e: Exception) {
        null
    }
    if (center == null) return "Семантика: нет данных"

    fun pct(label: SemanticLabel): String = try {
        "%.0f%%".format(frame.getSemanticLabelFraction(label) * 100f)
    } catch (e: Exception) {
        "—"
    }

    return "Семантика: в центре «${LABEL_NAMES[center] ?: center.name}» · " +
        "здание ${pct(SemanticLabel.BUILDING)} · люди ${pct(SemanticLabel.PERSON)} · " +
        "машины ${pct(SemanticLabel.VEHICLE)} · небо ${pct(SemanticLabel.SKY)}"
}

/** Последние координаты Earth — нужны для однократной проверки VPS. */
internal data class GeoFix(val latitude: Double, val longitude: Double)

/**
 * Geospatial: состояние Earth и точность текущей позы камеры.
 * Возвращает строку и координаты (если поза есть).
 */
internal fun probeEarth(session: Session): Pair<String, GeoFix?> {
    val earth: Earth = try {
        session.earth
    } catch (e: Exception) {
        null
    } ?: return "Geospatial: выключен" to null

    val state = earth.earthState
    if (state != Earth.EarthState.ENABLED) {
        val hint = if (state == Earth.EarthState.ERROR_INTERNAL) " (часто: нет интернета)" else ""
        return "Geospatial: ошибка $state$hint" to null
    }
    if (earth.trackingState != TrackingState.TRACKING) {
        return "Geospatial: ищу позицию (${earth.trackingState})" to null
    }
    return try {
        val pose = earth.cameraGeospatialPose
        val text = "Geospatial: ±%.1f м · курс ±%.1f° · высота ±%.1f м".format(
            pose.horizontalAccuracy, pose.orientationYawAccuracy, pose.verticalAccuracy
        )
        text to GeoFix(pose.latitude, pose.longitude)
    } catch (e: Exception) {
        "Geospatial: ошибка позы (${e.javaClass.simpleName})" to null
    }
}
