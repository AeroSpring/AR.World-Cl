package com.aerospring.arworld.feature.arbc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.core.data.repository.AdStatsReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.aerospring.arworld.feature.arbc.data.ArBcShowcaseFetchResult
import com.aerospring.arworld.feature.arbc.data.ArBcShowcaseRepository
import com.aerospring.arworld.feature.arbc.data.ShowcaseClient

/**
 * Витрина активных AR.Визиток — список клиентов из GET /arbc/showcase, показывается
 * внутри раздела рядом со сканером (см. ArBcEntryPoint), не отдельным top-level route.
 * Тап по карточке ведёт СРАЗУ в сцену клиента, минуя QR — тем же путём, что и успешный
 * скан.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArBcShowcaseScreen(
    onClientSelected: (clientId: String) -> Unit,
    onBackClick: () -> Unit
) {
    var clients by remember { mutableStateOf<List<ShowcaseClient>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var testBanner by remember { mutableStateOf<String?>(null) }

    // Обезличенные счётчики для отчётности о рекламе: показ = карточка визитки в Витрине,
    // открытие = переход из Витрины в сцену. Повтор в этой сессии не считается.
    val adStats = remember { AdStatsReporter.forSection(ARBC_AD_SECTION) }
    LaunchedEffect(clients) {
        clients.forEach { adStats.impression(it.clientId, it.ad?.erid) }
    }
    DisposableEffect(Unit) {
        onDispose { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { adStats.flush() } }
    }

    LaunchedEffect(Unit) {
        when (val result = ArBcShowcaseRepository.fetchShowcase()) {
            is ArBcShowcaseFetchResult.Success -> {
                clients = result.clients
                testBanner = result.testBanner
                isLoading = false
            }
            is ArBcShowcaseFetchResult.Error -> {
                errorText = result.message
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Активные AR.Визитки") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                errorText != null -> Text(
                    text = errorText ?: "Ошибка",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )

                clients.isEmpty() -> Text(
                    text = "Пока нет активных AR.Визиток",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(clients) { client ->
                        ShowcaseCard(client = client, onClick = {
                            adStats.cardOpen(client.clientId, client.ad?.erid)
                            onClientSelected(client.clientId)
                        })
                    }
                }
            }

            testBanner?.let { banner ->
                AdTestBanner(text = banner, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun ShowcaseCard(client: ShowcaseClient, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (client.previewUrl != null) {
                AsyncImage(
                    model = ArBcShowcaseRepository.absolutePreviewUrl(client.previewUrl),
                    contentDescription = client.clientName,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Storefront,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }
        }
        Text(
            text = client.clientName,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 1
        )
        // Маркировка рекламы: «Реклама · рекламодатель, ИНН · erid» (можно выделить и скопировать).
        client.ad?.line?.let { adLine ->
            SelectionContainer {
                Text(
                    text = adLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** Раздел AR.Визитки для маркировки рекламы и счётчиков (POST /ads/events). */
internal const val ARBC_AD_SECTION = "arbc"

/** Красная плашка режима test маркировки — чтобы не перепутать с боевым. */
@Composable
internal fun AdTestBanner(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(Color(0xFFDD0000), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}