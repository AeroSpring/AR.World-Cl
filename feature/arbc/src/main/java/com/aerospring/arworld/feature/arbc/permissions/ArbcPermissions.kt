package com.aerospring.arworld.feature.arbc.permissions

import android.Manifest
import android.content.Context
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

/** Микрофон НЕ входит в REQUIRED_ARBC_PERMISSIONS — не запрашиваем его при входе в
 *  визитку вместе с камерой, только по факту первого тапа на иконку микрофона в
 *  диалоге (голосовой ввод — опциональная возможность, не обязательная для визита). */
fun isRecordAudioGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED