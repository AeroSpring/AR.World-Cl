package com.aerospring.arworld.feature.whereanythingis.storage

import android.content.Context

private const val PREFS_NAME = "where_anything_is_prefs"
private const val KEY_RADIUS_KM = "last_radius_km"

/** Хранит последний выбранный пользователем радиус между запусками приложения. */
object RadiusPreferences {
    fun save(context: Context, radiusKm: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_RADIUS_KM, radiusKm.toFloat())
            .apply()
    }

    fun load(context: Context, default: Double): Double {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_RADIUS_KM)) {
            prefs.getFloat(KEY_RADIUS_KM, default.toFloat()).toDouble()
        } else {
            default
        }
    }
}