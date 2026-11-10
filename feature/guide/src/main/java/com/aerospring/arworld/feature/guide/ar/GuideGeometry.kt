package com.aerospring.arworld.feature.guide.ar

import com.aerospring.arworld.feature.guide.data.GuideAnchor
import com.aerospring.arworld.feature.guide.data.GuideBundle
import com.google.ar.core.Pose
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Система здания в 3D (её задаёт якорь сцены): X — вправо по плану, Y — вверх, Z — вниз по плану.
 * Точка плана (x, y) на этаже с высотой пола h — это (x, h, y). Система правая: на плане «вверх» — север,
 * тогда X — восток, Z — юг, Y — вверх.
 *
 * Плоскость узнанной картинки в ARCore: X — вправо по картинке, Z — вниз по картинке, Y — из картинки
 * к зрителю (как в AR.Фасадах). Лист висит на вертикальной стене, поэтому в системе здания:
 *   Y картинки = направление «куда смотрит» f = (sin h, 0, −cos h)   (h — headingDeg, 0 = вверх по плану)
 *   Z картинки = вниз (0, −1, 0)
 *   X картинки = Y × Z = (fz, 0, −fx)
 */
internal object GuideGeometry {

    /** Поза центра картинки в системе здания. */
    fun imageInBuilding(b: GuideBundle, a: GuideAnchor): Pose {
        val h = Math.toRadians(a.headingDeg.toDouble())
        val fx = sin(h).toFloat()
        val fz = (-cos(h)).toFloat()
        // Столбцы матрицы поворота — оси картинки в системе здания.
        val q = quaternionFromColumns(
            floatArrayOf(fz, 0f, -fx),
            floatArrayOf(fx, 0f, fz),
            floatArrayOf(0f, -1f, 0f)
        )
        return Pose(floatArrayOf(a.x, b.elevation(a.floorId) + a.centerHeightM, a.y), q)
    }

    /**
     * Поза системы здания в мире по узнанной картинке.
     *
     * Поворот оставляем только вокруг вертикали: картинку ARCore оценивает с небольшим наклоном,
     * а наклон в 1–2° на 10 м — это 20–30 см «пола в воздухе». Пол телефон и так знает (гравитация).
     * Поэтому вертикаль здания = вертикаль мира, а поворот и сдвиг подбираются так, чтобы центр
     * картинки встал ровно туда, где его видит ARCore.
     */
    fun buildingInWorld(imageWorld: Pose, imageBuilding: Pose): Pose {
        val raw = imageWorld.compose(imageBuilding.inverse())
        val xw = raw.xAxis   // ось X здания в мире
        val yaw = atan2(-xw[2], xw[0])
        val half = yaw / 2f
        val q = floatArrayOf(0f, sin(half), 0f, cos(half))
        // Сдвиг: центр картинки (в здании) после поворота должен совпасть с центром в мире.
        val pb = imageBuilding.translation
        val c = cos(yaw)
        val s = sin(yaw)
        val rx = pb[0] * c + pb[2] * s
        val rz = -pb[0] * s + pb[2] * c
        val pw = imageWorld.translation
        return Pose(floatArrayOf(pw[0] - rx, pw[1] - pb[1], pw[2] - rz), q)
    }

    /** Кватернион (x, y, z, w) из матрицы поворота, заданной столбцами. */
    private fun quaternionFromColumns(c0: FloatArray, c1: FloatArray, c2: FloatArray): FloatArray {
        val m00 = c0[0]; val m10 = c0[1]; val m20 = c0[2]
        val m01 = c1[0]; val m11 = c1[1]; val m21 = c1[2]
        val m02 = c2[0]; val m12 = c2[1]; val m22 = c2[2]
        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = kotlin.math.sqrt(trace + 1f) * 2f
            q[3] = 0.25f * s
            q[0] = (m21 - m12) / s
            q[1] = (m02 - m20) / s
            q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s
            q[0] = 0.25f * s
            q[1] = (m01 + m10) / s
            q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s
            q[0] = (m01 + m10) / s
            q[1] = 0.25f * s
            q[2] = (m12 + m21) / s
        } else {
            val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s
            q[0] = (m02 + m20) / s
            q[1] = (m12 + m21) / s
            q[2] = 0.25f * s
        }
        val len = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return floatArrayOf(q[0] / len, q[1] / len, q[2] / len, q[3] / len)
    }
}
