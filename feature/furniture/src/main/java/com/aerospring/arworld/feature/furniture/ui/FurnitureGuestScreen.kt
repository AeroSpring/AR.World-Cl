package com.aerospring.arworld.feature.furniture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.feature.furniture.ar.FurnitureSceneScreen
import com.aerospring.arworld.feature.furniture.data.FurnitureShowcase
import com.aerospring.arworld.feature.furniture.data.FurnitureShowcaseFetchResult
import com.aerospring.arworld.feature.furniture.data.FurnitureShowcaseRepository
import com.aerospring.arworld.feature.furniture.permissions.RequireCameraPermission
import kotlinx.coroutines.launch

/**
 * Гостевой вход в AR.Мебель: тянет публичную витрину и открывает ту же AR-сцену,
 * что у дизайнера, в гостевом режиме. Устроен так же, как FurnitureCatalogScreen
 * (сначала данные, потом сцена под RequireCameraPermission).
 */
@Composable
fun FurnitureGuestScreen(
    onLoginClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    var showcase by remember { mutableStateOf<FurnitureShowcase?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() {
        isLoading = true
        errorMessage = null
        scope.launch {
            when (val result = FurnitureShowcaseRepository.fetchShowcase()) {
                is FurnitureShowcaseFetchResult.Success -> showcase = result.showcase
                is FurnitureShowcaseFetchResult.Error -> errorMessage = result.message
            }
            isLoading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val current = showcase
    if (current != null && current.models.isNotEmpty()) {
        val models = remember(current) { current.asFurnitureModels() }
        RequireCameraPermission {
            FurnitureSceneScreen(
                models = models,
                onLogoutClick = onLoginClick,
                onBackClick = onBackClick,
                guestShowcase = current,
            )
        }
    } else {
        GuestStatus(
            isLoading = isLoading,
            errorMessage = errorMessage,
            onRetry = { load() },
            onLoginClick = onLoginClick,
            onBackClick = onBackClick,
        )
    }
}

/** Загрузка / ошибка / пустая витрина. */
@Composable
private fun GuestStatus(
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onLoginClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(onClick = onBackClick, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
        }
        Column(
            modifier = Modifier.align(Alignment.Center).widthIn(max = 360.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Загружаю витрину…")
                }
                errorMessage != null -> {
                    Text("Не удалось загрузить витрину", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("Повторить") }
                }
                else -> {
                    Text("Витрина пока пуста", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Мебельные компании скоро добавят сюда свои модели. Загляните позже.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onLoginClick) { Text("Вход для дизайнеров") }
        }
    }
}