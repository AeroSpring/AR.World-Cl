package com.aerospring.arworld.core.data.model

import kotlinx.serialization.Serializable

/**
 * Категория маркера (POI). Список будет расширяться администратором проекта —
 * это лишь стартовый набор для разработки.
 * @param colorHex используется и для луча-указателя вниз, и для чипа в фильтре категорий.
 */
@Serializable
data class Category(
    val id: String,
    val displayName: String,
    val colorHex: String
)

/** Стартовый набор категорий — расширится позже через админку/бэкенд. */
val defaultCategories = listOf(
    Category(id = "auto", displayName = "Авто", colorHex = "#FF6B6B"),
    Category(id = "it", displayName = "IT", colorHex = "#4D96FF"),
    Category(id = "beauty", displayName = "Красота", colorHex = "#FF6FB5"),
    Category(id = "med", displayName = "Медицина", colorHex = "#3ED598")
)