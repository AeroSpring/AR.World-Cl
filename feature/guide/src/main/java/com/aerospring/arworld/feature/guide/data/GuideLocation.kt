package com.aerospring.arworld.feature.guide.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Примерное место телефона — чтобы показать ТЦ рядом, а не все в стране.
 * Точность не нужна (город, район): берём последнее известное место от сети или GPS,
 * а если его нет — одно свежее от сети (не дольше 5 секунд). Без Google-сервисов.
 */
internal object GuideLocation {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun approximate(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = try { lm.getProviders(true) } catch (e: Exception) { emptyList() }
        val last = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
        // Место не старше суток вполне годится: ТЦ не переезжают, человек — редко между городами за день.
        if (last != null && System.currentTimeMillis() - last.time < 24 * 3600_000L) return last
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                else -> return last
            }
            val fresh = withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    try {
                        lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
                    } catch (e: Exception) {
                        cont.resume(null)
                    }
                }
            }
            return fresh ?: last
        }
        return last
    }
}
