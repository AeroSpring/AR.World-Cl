package com.aerospring.arworld.feature.whereanythingis.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Плашка со статусом загрузки модели, показывается на месте маркера в 3D-пространстве
 * через ViewNode. Размер задан явно (в dp) — ViewNode не подстраивает размер автоматически
 * под содержимое, и без явного размера плашка рендерится нечитаемо мелкой.
 */
@Composable
fun MarkerLoadingBadge(
    title: String,
    statusText: String
) {
    Card(
        modifier = Modifier.width(560.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = "$title\n$statusText",
            fontSize = 40.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp)
        )
    }
}