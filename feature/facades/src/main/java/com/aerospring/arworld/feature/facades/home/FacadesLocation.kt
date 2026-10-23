package com.aerospring.arworld.feature.facades.home

import android.annotation.SuppressLint
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Где сейчас телефон — чтобы найти фасады рядом. Копия помощника из «Где что находится»
 * (разделы не зависят друг от друга). Вызывать только при выданном разрешении на геолокацию.
 */
@SuppressLint("MissingPermission")
@Composable
internal fun rememberFacadesLocation(enabled: Boolean, updateIntervalMillis: Long = 5000L): State<Location?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Location?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(updateIntervalMillis)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { state.value = it }
            }
        }
        client.lastLocation.addOnSuccessListener { last -> if (last != null && state.value == null) state.value = last }
        client.requestLocationUpdates(request, callback, context.mainLooper)
        onDispose { client.removeLocationUpdates(callback) }
    }
    return state
}
