package com.aerospring.arworld.feature.guide.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * «Путеводитель по ТЦ» — данные здания в том виде, как их отдаёт сервер:
 *
 *   GET /guide/buildings                → [GuideBuildingItem] — опубликованные здания
 *   GET /guide/buildings/{slug}/bundle  → [GuideBundle] — всё здание одним JSON
 *
 * Координаты — метры в системе здания: x вправо, y вниз (как на поэтажном плане),
 * высота пола этажа — [GuideFloor.elevationM]. Поля с умолчаниями: новые поля сервера старое
 * приложение пропустит.
 */
internal val guideJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
internal data class GuideBuildingItem(
    val slug: String,
    val name: String,
    val address: String = "",
    val bundleVersion: Long = 0
)

@Serializable
internal data class GuideBundle(
    val slug: String,
    val name: String,
    val address: String = "",
    val bundleVersion: Long = 0,
    val floors: List<GuideFloor> = emptyList(),
    val nodes: List<GuideNode> = emptyList(),
    val edges: List<GuideEdge> = emptyList(),
    val places: List<GuidePlace> = emptyList(),
    val anchors: List<GuideAnchor> = emptyList()
) {
    fun floor(id: String): GuideFloor? = floors.firstOrNull { it.id == id }
    fun node(id: String): GuideNode? = nodes.firstOrNull { it.id == id }
    fun elevation(floorId: String): Float = floor(floorId)?.elevationM ?: 0f
    fun floorName(floorId: String): String = floor(floorId)?.let { it.name.ifBlank { "${it.level} этаж" } } ?: "?"
}

@Serializable
internal data class GuideFloor(
    val id: String,
    val level: Int,
    val name: String = "",
    val elevationM: Float = 0f
)

@Serializable
internal data class GuideNode(
    val id: String,
    val floorId: String,
    val x: Float,
    val y: Float,
    val kind: String = "corridor",
    val name: String = "",
    /** Высота над полом своего этажа — у точек на лестнице (дорожка идёт по ступеням); обычно 0. */
    val heightM: Float = 0f
)

@Serializable
internal data class GuideEdge(
    val id: String,
    @SerialName("from") val from: String,
    val to: String,
    val kind: String = "walk",
    val lengthM: Float = 0f,
    val oneWay: Boolean = false,
    val accessible: Boolean = true
)

@Serializable
internal data class GuidePlace(
    val id: String,
    val floorId: String,
    val name: String,
    val category: String = "",
    val keywords: List<String> = emptyList(),
    val entranceNodeId: String? = null,
    val phone: String = "",
    val siteUrl: String = "",
    val hours: String = "",
    val isService: Boolean = false,
    val isActive: Boolean = true
)

/**
 * Опорная картинка: лист на стене, который узнаёт ARCore.
 * [headingDeg] — куда «смотрит» лицевая сторона на плане: 0 — вверх по плану, 90 — вправо (по часовой).
 * [centerHeightM] — высота центра картинки над полом своего этажа.
 */
@Serializable
internal data class GuideAnchor(
    val id: String,
    val floorId: String,
    val name: String = "",
    val kind: String = "custom",
    val imageUrl: String = "",
    val widthM: Float,
    val x: Float,
    val y: Float,
    val centerHeightM: Float = 1.4f,
    val headingDeg: Float = 0f,
    val role: String = "start",
    val nodeId: String? = null
)
