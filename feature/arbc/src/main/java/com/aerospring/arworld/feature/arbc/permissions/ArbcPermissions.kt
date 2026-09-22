package com.aerospring.arworld.feature.arbc.permissions

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

val REQUIRED_ARBC_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

@Composable
fun rememberArbcPermissionsGranted(): Boolean {
    val context = LocalContext.current
    return REQUIRED_ARBC_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}