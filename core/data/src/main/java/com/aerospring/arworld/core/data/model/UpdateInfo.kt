package com.aerospring.arworld.core.data.model

import kotlinx.serialization.Serializable

/** Схема соответствует JSON на сервере: https://autoknowledge.tech/models/appVersion.json */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String? = null
)