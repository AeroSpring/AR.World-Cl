package com.aerospring.arworld.feature.whereanythingis.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Открывает навигацию до точки: сначала пробует Google Maps напрямую (лучший UX — сразу
 * строит маршрут), при отсутствии приложения — универсальный geo:-intent, и как последний
 * fallback — веб-версия карт в браузере.
 */
fun openNavigationTo(context: Context, latitude: Double, longitude: Double) {
    val googleMapsIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("google.navigation:q=$latitude,$longitude&mode=d")
    ).apply { setPackage("com.google.android.apps.maps") }

    try {
        context.startActivity(googleMapsIntent)
        return
    } catch (e: ActivityNotFoundException) {
        // Google Maps не установлен — пробуем универсальный вариант ниже.
    }

    val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))
    try {
        context.startActivity(geoIntent)
        return
    } catch (e: ActivityNotFoundException) {
        // Нет вообще никакого приложения карт — открываем веб-версию.
    }

    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$latitude,$longitude")
    )
    context.startActivity(webIntent)
}

/** Открывает сайт объявления в браузере по умолчанию. */
fun openSite(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

/** До 1 км — в метрах, дальше — в километрах с одним знаком после запятой до 10 км, иначе целым числом. */
fun formatDistance(distanceMeters: Float): String = when {
    distanceMeters < 1000f -> "${distanceMeters.toInt()} м"
    distanceMeters < 10_000f -> "%.1f км".format(distanceMeters / 1000f)
    else -> "${(distanceMeters / 1000f).toInt()} км"
}