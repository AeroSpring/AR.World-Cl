package com.aerospring.arworld.feature.whereanythingis.ui

import androidx.compose.ui.graphics.Color
import com.aerospring.arworld.core.data.model.defaultCategories

/** Единый источник цвета категории — используется и для чипов на верхней панели, и для лучей/маркеров в AR-сцене. */
fun categoryColor(categoryId: String): Color =
    defaultCategories.firstOrNull { it.id == categoryId }
        ?.let { Color(android.graphics.Color.parseColor(it.colorHex)) }
        ?: Color(0xFFAAAAAA)