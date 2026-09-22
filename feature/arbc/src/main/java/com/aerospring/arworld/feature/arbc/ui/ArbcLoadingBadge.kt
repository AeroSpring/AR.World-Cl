package com.aerospring.arworld.feature.arbc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Тап по плашке даёт тот же эффект, что и тап по уже загруженной модели —
 * ту же интеракцию, что и model.interactions.firstOrNull() (см. ArBcSceneView).
 * Реализовано обычным Compose-кликом внутри ViewNode, без нового API SceneView —
 * ниже риска, чем что-то менять в 3D-хит-тестинге.
 *
 * Ширина жёстко ограничена (BADGE_WIDTH_DP) — без этого плашка растёт по контенту
 * и в 3D-сцене может занимать весь экран. maxLines/ellipsis — на случай длинного
 * сообщения об ошибке.
 */
private val BADGE_WIDTH_DP = 130.dp

@Composable
fun ArbcLoadingBadge(statusText: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(BADGE_WIDTH_DP)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = statusText,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}