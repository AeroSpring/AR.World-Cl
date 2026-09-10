package com.aerospring.arworld.core.data.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Геоматематика, перенесённая из веб-версии (формула гаверсинусов + азимут).
 * Используется для расчёта дистанции до POI и направления на него от пользователя.
 */
object GeoMath {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Расстояние между двумя точками по поверхности Земли, в метрах. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /** Азимут (bearing) от первой точки на вторую, в градусах [0, 360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)

        return (Math.toDegrees(theta) + 360) % 360
    }

    /**
     * Смещение целевой точки относительно опорной в метрах восток/север (ENU),
     * упрощённая проекция на основе дистанции+азимута — годится для AR-сцены,
     * где важна локальная плоская геометрия вокруг пользователя.
     */
    fun localOffsetMeters(originLat: Double, originLon: Double, targetLat: Double, targetLon: Double): Pair<Double, Double> {
        val distance = distanceMeters(originLat, originLon, targetLat, targetLon)
        val bearingRad = Math.toRadians(bearingDegrees(originLat, originLon, targetLat, targetLon))
        val east = distance * sin(bearingRad)
        val north = distance * cos(bearingRad)
        return east to north
    }
}