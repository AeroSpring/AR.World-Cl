package com.aerospring.arworld.feature.whereanythingis.permissions

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Разрешения, необходимые AR-сервису "Где что находится": камера + точная геолокация. */
val REQUIRED_AR_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION
)

/**
 * Проверка, все ли требуемые разрешения уже выданы.
 * Композируемая функция-хелпер — пересчитывается при каждой рекомпозиции с новым состоянием.
 */
@Composable
fun rememberArPermissionsGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            REQUIRED_AR_PERMISSIONS.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    return granted
}