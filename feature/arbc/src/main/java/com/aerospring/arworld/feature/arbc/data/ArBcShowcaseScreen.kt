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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(Unit) {
        when (val result = ArBcShowcaseRepository.fetchShowcase()) {
            is ArBcShowcaseFetchResult.Success -> {
                clients = result.clients
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
                        ShowcaseCard(client = client, onClick = { onClientSelected(client.clientId) })
                    }
                }
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
    }
}