package com.aerospring.arworld.feature.furniture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.feature.furniture.data.FurnitureModel
import com.aerospring.arworld.feature.furniture.data.FurnitureModelsFetchResult
import com.aerospring.arworld.feature.furniture.data.FurnitureModelsRepository

/**
 * ВРЕМЕННЫЙ экран — подтверждает, что каталог тянется и 401 обрабатывается
 * верно (возврат на логин). Настоящая горизонтальная карусель миниатюр
 * внизу экрана + сама AR-сцена — следующий шаг.
 */
@Composable
fun FurnitureCatalogScreen(
    token: String,
    displayName: String,
    onUnauthorized: () -> Unit,
    onLogoutClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    var models by remember { mutableStateOf<List<FurnitureModel>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token) {
        when (val result = FurnitureModelsRepository.fetchModels(token)) {
            is FurnitureModelsFetchResult.Success -> models = result.models
            FurnitureModelsFetchResult.Unauthorized -> onUnauthorized()
            is FurnitureModelsFetchResult.Error -> errorMessage = result.message
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text("Привет, $displayName", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onLogoutClick) { Text("Выйти") }
        }
        Spacer(Modifier.height(16.dp))

        when {
            errorMessage != null -> Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
            models == null -> CircularProgressIndicator()
            models!!.isEmpty() -> Text("Моделей пока нет")
            else -> LazyColumn {
                items(models!!) { model ->
                    ListItem(headlineContent = { Text(model.modelName) })
                    HorizontalDivider()
                }
            }
        }
    }
}