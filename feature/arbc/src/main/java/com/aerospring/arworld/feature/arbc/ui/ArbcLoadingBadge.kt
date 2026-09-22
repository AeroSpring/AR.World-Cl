package com.aerospring.arworld.feature.arbc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Клик по этой плашке — сама по себе не интерактивна (концепт требует "кликабельную
 * панель загрузки", уточним семантику клика при работе с интеракциями на шаге 4:
 * пока просто показывает статус, дальнейшее поведение по тапу — открытый вопрос).
 */
@Composable
fun ArbcLoadingBadge(statusText: String) {
    Text(
        text = statusText,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}