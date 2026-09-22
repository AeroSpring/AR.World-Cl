package com.aerospring.arworld.feature.arbc

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.feature.arbc.ar.ArBcSceneView
import com.aerospring.arworld.feature.arbc.data.ArBcRepository
import com.aerospring.arworld.feature.arbc.data.ArBcScene
import com.aerospring.arworld.feature.arbc.data.ArBcSceneFetchResult
import com.aerospring.arworld.feature.arbc.data.Interaction
import com.aerospring.arworld.feature.arbc.permissions.REQUIRED_ARBC_PERMISSIONS
import com.aerospring.arworld.feature.arbc.permissions.rememberArbcPermissionsGranted

/** Маршрут этой фичи объявлен здесь же, внутри модуля — а не в общем
 *  ArWorldDestinations, чтобы feature:arbc ничего не менял в шаред-коде,
 *  общем для всех разделов приложения. */
const val AR_BUSINESS_CARDS_ROUTE = "ar_business_cards"

/**
 * Экран одной AR.Визитки. clientId сейчас передаётся напрямую (тестовый заход —
 * "89ztMvCZ" — для смоук-теста на реальной модели). Реальный запуск по QR/deeplink
 * из /v/{clientId} — отдельный шаг, не блокирует эту проверку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArBcScreen(
    clientId: String,
    onBackClick: () -> Unit
) {
    var permissionsGranted by remember { mutableStateOf(false) }
    val initiallyGranted = rememberArbcPermissionsGranted()

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
                title = { Text("AR.Визитка") },
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
            if (permissionsGranted) {
                var fetchResult by remember { mutableStateOf<ArBcSceneFetchResult?>(null) }
                LaunchedEffect(clientId) {
                    fetchResult = ArBcRepository.fetchScene(clientId)
                }

                when (val result = fetchResult) {
                    null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    is ArBcSceneFetchResult.Error -> Text(
                        text = "Не удалось загрузить AR.Визитку: ${result.message}",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )

                    is ArBcSceneFetchResult.Success -> {
                        val scene: ArBcScene = result.scene
                        ArBcSceneView(
                            scene = scene,
                            onInteraction = { interaction ->
                                // TODO(шаг 4): реальная обработка openUrl/activateAI —
                                // пока просто просмотр в логах для смоук-теста.
                                when (interaction) {
                                    is Interaction.OpenUrl ->
                                        android.util.Log.d("ArBcScreen", "openUrl: ${interaction.url}")
                                    is Interaction.ActivateAI ->
                                        android.util.Log.d("ArBcScreen", "activateAI")
                                    Interaction.Unknown ->
                                        android.util.Log.d("ArBcScreen", "unknown interaction")
                                }
                            },
                            onBackgroundClick = { },
                            onSessionCreated = { },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.align(Alignment.Center)) {
                    Text("Для работы AR.Визитки нужен доступ к камере.")
                    Button(onClick = { permissionLauncher.launch(REQUIRED_ARBC_PERMISSIONS) }) {
                        Text("Предоставить доступ")
                    }
                }
            }
        }
    }
}