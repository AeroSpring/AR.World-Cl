package com.aerospring.arworld.core.data.model

import kotlinx.serialization.Serializable

/**
 * Точка интереса (POI) — маркер в AR-пространстве.
 * Пригодится для фичи "where-anything-is" уже на следующем шаге.
 */
@Serializable
data class Poi(
    val id: String,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val category: String,
    val modelUrl: String,
    val siteUrl: String? = null
)