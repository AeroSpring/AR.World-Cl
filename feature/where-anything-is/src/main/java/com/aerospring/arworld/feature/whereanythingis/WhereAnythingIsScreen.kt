package com.aerospring.arworld.feature.whereanythingis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.aerospring.arworld.feature.whereanythingis.permissions.REQUIRED_AR_PERMISSIONS
import com.aerospring.arworld.feature.whereanythingis.permissions.rememberArPermissionsGranted

/**
 * Экран AR-сервиса "Где что находится".
 * Сейчас: запрос разрешений + заготовка под AR-сцену.
 * Слайдер радиуса, категории, маркеры и лучи — на следующих шагах.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhereAnythingIsScreen(
    onBackClick: () -> Unit
) {
    var permissionsGranted by remember { mutableStateOf(false) }
    val initiallyGranted = rememberArPermissionsGranted()

    LaunchedEffect(initiallyGranted) {
        permissionsGranted = initiallyGranted
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Где что находится") },
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
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            if (permissionsGranted) {
                // Следующий шаг: здесь появится ArSceneComposable с камерой ARCore
                Text("Разрешения получены. AR-сцена появится здесь.")
            } else {
                Column {
                    Text("Для работы сервиса нужны доступ к камере и геолокации.")
                    Button(onClick = { permissionLauncher.launch(REQUIRED_AR_PERMISSIONS) }) {
                        Text("Предоставить доступ")
                    }
                }
            }
        }
    }
}