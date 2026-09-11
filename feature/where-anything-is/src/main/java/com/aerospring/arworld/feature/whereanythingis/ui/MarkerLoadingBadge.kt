package com.aerospring.arworld.feature.whereanythingis.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Кликабельная (по стилю с верхней панелью) плашка со статусом загрузки модели —
 * показывается на месте маркера, пока .glb ещё скачивается.
 */
@Composable
fun MarkerLoadingBadge(
    title: String,
    percent: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = "$title\nЗагрузка $percent%",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}