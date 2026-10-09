package com.aerospring.arworld.feature.furniture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aerospring.arworld.feature.furniture.ar.FurnitureSceneScreen
import com.aerospring.arworld.feature.furniture.data.FurnitureModel
import com.aerospring.arworld.feature.furniture.data.FurnitureModelsFetchResult
import com.aerospring.arworld.feature.furniture.data.FurnitureModelsRepository
import com.aerospring.arworld.feature.furniture.permissions.RequireCameraPermission

/** Тянет каталог (дизайнер — свои модели, руководитель — модели всех своих дизайнеров),
 *  затем передаёт его в AR-сцену. */
@Composable
fun FurnitureCatalogScreen(
    token: String,
    displayName: String,
    isManager: Boolean = false,
    onUnauthorized: () -> Unit,
    onLogoutClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    var models by remember { mutableStateOf<List<FurnitureModel>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token, isManager) {
        when (val result = FurnitureModelsRepository.fetchModels(token, isManager)) {
            is FurnitureModelsFetchResult.Success -> models = result.models
            FurnitureModelsFetchResult.Unauthorized -> onUnauthorized()
            is FurnitureModelsFetchResult.Error -> errorMessage = result.message
        }
    }

    val currentModels = models
    when {
        errorMessage != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
        }
        currentModels == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> RequireCameraPermission {
            FurnitureSceneScreen(
                models = currentModels,
                onLogoutClick = onLogoutClick,
                onBackClick = onBackClick,
            )
        }
    }
}