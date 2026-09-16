package com.aerospring.arworld.feature.whereanythingis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.feature.whereanythingis.ar.VisibleMarker

/**
 * Карточка с информацией о POI — появляется по клику на маркер. Показывает заголовок,
 * координаты, расстояние и кнопки перехода в навигацию/на сайт объявления.
 */
@Composable
fun MarkerInfoCard(
    marker: VisibleMarker,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = marker.poi.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
            )

            Text(
                text = "%.5f, %.5f".format(marker.poi.latitude, marker.poi.longitude),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Расстояние: ${formatDistance(marker.distanceMeters)}",
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { openNavigationTo(context, marker.poi.latitude, marker.poi.longitude) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Маршрут")
                }

                val phone = marker.poi.phone
                if (phone != null) {
                    Button(
                        onClick = { openPhoneCall(context, phone) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Call,
                            contentDescription = "Позвонить"
                        )
                    }
                }

                val siteUrl = marker.poi.siteUrl
                if (siteUrl != null) {
                    Button(
                        onClick = { openSite(context, siteUrl) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("На сайт")
                    }
                }
            }
        }
    }
}