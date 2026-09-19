package com.aerospring.arworld.feature.whereanythingis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.core.data.model.Category
import kotlin.math.roundToInt

private const val MAX_RADIUS_KM = 1000.0
// Логарифм от (радиус + 1), а не просто от радиуса — это даёт настоящий, нативный 0
// на старте шкалы (обычный log10(0) не определён, отсюда раньше и была "упрямая" единица).
private val LOG_SCALE_FACTOR = kotlin.math.ln(MAX_RADIUS_KM + 1.0)

/** Переводит позицию слайдера [0,1] в километры по логарифмической шкале [0..1000]. */
fun sliderPositionToRadiusKm(position: Float): Double =
    kotlin.math.exp(position * LOG_SCALE_FACTOR) - 1.0

/** Обратное преобразование — из километров в позицию слайдера [0,1]. */
fun radiusKmToSliderPosition(radiusKm: Double): Float =
    (kotlin.math.ln(radiusKm + 1.0) / LOG_SCALE_FACTOR).toFloat().coerceIn(0f, 1f)

/** До 10 км — один знак после запятой (точность важна на малых радиусах), от 10 км — целое число. */
private fun formatRadiusKm(radiusKm: Double): String =
    when {
        radiusKm <= 0.0 -> "0"
        radiusKm < 10.0 -> "%.1f".format(radiusKm)
        else -> radiusKm.roundToInt().toString()
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopControlPanel(
    sliderRange: ClosedFloatingPointRange<Float>,
    onSliderRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    categories: List<Category>,
    selectedCategoryIds: Set<String>,
    onCategoryToggle: (String) -> Unit,
    categoriesExpanded: Boolean,
    onCategoriesExpandedToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val minRadiusKm = sliderPositionToRadiusKm(sliderRange.start)
    val maxRadiusKm = sliderPositionToRadiusKm(sliderRange.endInclusive)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RangeSlider(
                    value = sliderRange,
                    onValueChange = onSliderRangeChange,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${formatRadiusKm(minRadiusKm)}–${formatRadiusKm(maxRadiusKm)} км",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Категории")
                IconButton(onClick = onCategoriesExpandedToggle) {
                    Icon(
                        imageVector = if (categoriesExpanded) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = if (categoriesExpanded) "Свернуть категории" else "Развернуть категории"
                    )
                }
            }

            if (categoriesExpanded) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        val selected = category.id in selectedCategoryIds
                        val chipColor = categoryColor(category.id)
                        FilterChip(
                            selected = selected,
                            onClick = { onCategoryToggle(category.id) },
                            label = { Text(category.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipColor,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }
            }
        }
    }
}