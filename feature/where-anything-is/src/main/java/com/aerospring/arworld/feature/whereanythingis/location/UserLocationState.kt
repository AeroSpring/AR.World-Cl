package com.aerospring.arworld.feature.whereanythingis.location

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
 * Live-позиция пользователя через FusedLocationProviderClient.
 * Вызывается только когда разрешение ACCESS_FINE_LOCATION уже выдано —
 * WhereAnythingIsScreen гарантирует это на уровне UI до отображения AR-сцены.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberUserLocation(updateIntervalMillis: Long = 3000L): State<Location?> {
    val context = LocalContext.current
    val locationState = remember { mutableStateOf<Location?>(null) }

    DisposableEffect(Unit) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(updateIntervalMillis)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { locationState.value = it }
            }
        }

        client.lastLocation.addOnSuccessListener { last ->
            if (last != null) locationState.value = last
        }
        client.requestLocationUpdates(request, callback, context.mainLooper)

        onDispose {
            client.removeLocationUpdates(callback)
        }
    }

    return locationState
}