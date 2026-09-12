package com.aerospring.arworld.feature.whereanythingis.ui

import androidx.compose.foundation.layout.padding
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
 * Компактная плашка статуса загрузки — задача не в информативности, а в том, чтобы
 * привлечь внимание к месту, где идёт загрузка. Стиль соответствует верхней панели —
 * тот же alpha и отсутствие тени, для целостного коммерческого вида продукта.
 */
@Composable
fun MarkerLoadingBadge(statusText: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = statusText,
            fontSize = 26.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}