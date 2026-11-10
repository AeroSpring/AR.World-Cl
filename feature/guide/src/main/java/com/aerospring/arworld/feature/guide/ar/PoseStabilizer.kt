package com.aerospring.arworld.feature.guide.ar

import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Успокаивает положение здания — копия проверенного PoseStabilizer из AR.Фасадов
 * (там он internal в своём модуле; здесь отдельная копия, чтобы не трогать работающий раздел).
 *
 *   1. копим позы из кадров, где картинка видна по-настоящему (FULL_TRACKING);
 *   2. первый якорь — по среднему из [firstSamples] согласных поз;
 *   3. дальше якорь неподвижен; заметно разошлось с новым средним — переставляем один раз.
 */
internal class PoseStabilizer(
    private val firstSamples: Int = 12,
    private val window: Int = 30,
    private val moveMeters: Float = 0.06f,
    private val moveFraction: Float = 0.015f,
    private val turnDegrees: Float = 2f
) {
    private val samples = ArrayDeque<Pose>()

    fun reset() = samples.clear()

    fun add(pose: Pose) {
        samples.addLast(pose)
        while (samples.size > window) samples.removeFirst()
    }

    fun firstPose(cameraDistance: Float): Pose? {
        if (samples.size < firstSamples) return null
        val recent = samples.toList().takeLast(firstSamples)
        val avg = average(recent)
        val spread = recent.maxOf { dist(it, avg) }
        val limit = maxOf(moveMeters, cameraDistance * moveFraction) * 1.5f
        return if (spread <= limit) avg else null
    }

    fun correction(current: Pose, cameraDistance: Float): Pose? {
        if (samples.size < firstSamples) return null
        val limit = maxOf(moveMeters, cameraDistance * moveFraction)
        val recent = samples.toList().takeLast(firstSamples)
        val avg = average(recent)
        if (recent.maxOf { dist(it, avg) } > limit * 1.5f) return null
        val move = dist(avg, current)
        return if (move > limit || angleDegrees(avg, current) > turnDegrees) avg else null
    }

    private fun dist(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx(); val dy = a.ty() - b.ty(); val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun average(list: Collection<Pose>): Pose {
        val n = list.size.toFloat()
        var tx = 0f; var ty = 0f; var tz = 0f
        val first = list.first()
        var qx = 0f; var qy = 0f; var qz = 0f; var qw = 0f
        for (p in list) {
            tx += p.tx(); ty += p.ty(); tz += p.tz()
            val sign = if (p.qx() * first.qx() + p.qy() * first.qy() + p.qz() * first.qz() + p.qw() * first.qw() < 0f) -1f else 1f
            qx += sign * p.qx(); qy += sign * p.qy(); qz += sign * p.qz(); qw += sign * p.qw()
        }
        val len = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).takeIf { it > 1e-6f } ?: 1f
        return Pose(floatArrayOf(tx / n, ty / n, tz / n), floatArrayOf(qx / len, qy / len, qz / len, qw / len))
    }

    private fun angleDegrees(a: Pose, b: Pose): Float {
        val dot = abs(a.qx() * b.qx() + a.qy() * b.qy() + a.qz() * b.qz() + a.qw() * b.qw()).coerceAtMost(1f)
        return Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
    }
}
