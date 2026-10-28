package com.aerospring.arworld.feature.about

import android.content.Context
import android.content.Intent
import android.net.Uri

fun installApk(context: Context, apkUri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}