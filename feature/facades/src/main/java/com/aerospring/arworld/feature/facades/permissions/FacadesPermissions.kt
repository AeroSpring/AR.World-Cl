package com.aerospring.arworld.feature.facades.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Камера обязательна. Точная геолокация желательна: без неё Geospatial не включается
 * (ARCore не работает с приблизительной геолокацией), но всё остальное работает.
 *
 * [content] получает флаг «точная геолокация разрешена».
 */
@Composable
fun RequireFacadesPermissions(
    onExit: () -> Unit,
    content: @Composable (locationGranted: Boolean) -> Unit
) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    var locationGranted by remember { mutableStateOf(context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: context.isGranted(Manifest.permission.CAMERA)
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted || !locationGranted) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            asked = true
        }
    }

    when {
        cameraGranted && asked -> content(locationGranted)
        !asked -> Unit // ждём ответа на системный запрос
        else -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Нужен доступ к камере для AR")
            Button(onClick = onExit, modifier = Modifier.padding(top = 16.dp)) { Text("Назад") }
        }
    }
}
