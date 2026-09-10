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
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.core.data.model.defaultCategories
import com.aerospring.arworld.feature.whereanythingis.ar.ArCameraView
import com.aerospring.arworld.feature.whereanythingis.location.rememberUserLocation
import com.aerospring.arworld.feature.whereanythingis.permissions.REQUIRED_AR_PERMISSIONS
import com.aerospring.arworld.feature.whereanythingis.permissions.rememberArPermissionsGranted
import com.aerospring.arworld.feature.whereanythingis.ui.TopControlPanel
import com.aerospring.arworld.feature.whereanythingis.ui.radiusKmToSliderPosition

/**
 * Экран AR-сервиса "Где что находится".
 * Сейчас: разрешения + камера ARCore + верхняя панель (радиус, категории) + позиция пользователя.
 * Маркеры POI, лучи и карточка "снизу" по клику — на следующих шагах.
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

    // TODO: заменить дефолт 10 км на сохранённое значение из предыдущей сессии пользователя (DataStore) — след. шаги.
    var sliderPosition by remember { mutableStateOf(radiusKmToSliderPosition(10.0)) }
    var selectedCategoryIds by remember { mutableStateOf(defaultCategories.map { it.id }.toSet()) }
    var categoriesExpanded by remember { mutableStateOf(true) }

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
                .padding(innerPadding)
        ) {
            if (permissionsGranted) {
                val userLocation by rememberUserLocation()

                val radiusKm = com.aerospring.arworld.feature.whereanythingis.ui.sliderPositionToRadiusKm(sliderPosition)

                // Калибровка компаса: фиксируем азимут ОДИН раз, в момент старта AR-сессии.
                var arSessionCreated by remember { mutableStateOf(false) }
                var calibratedHeadingDegrees by remember { mutableStateOf<Float?>(null) }
                val liveHeadingDegrees by com.aerospring.arworld.feature.whereanythingis.location.rememberDeviceHeadingDegrees()

                LaunchedEffect(arSessionCreated, liveHeadingDegrees) {
                    if (arSessionCreated && calibratedHeadingDegrees == null) {
                        liveHeadingDegrees?.let { calibratedHeadingDegrees = it }
                    }
                }

                val visibleMarkers = remember(userLocation, radiusKm, selectedCategoryIds, calibratedHeadingDegrees) {
                    val heading = calibratedHeadingDegrees
                    val location = userLocation
                    if (location != null && heading != null) {
                        com.aerospring.arworld.feature.whereanythingis.ar.PoiMarkerCalculator.computeVisibleMarkers(
                            userLat = location.latitude,
                            userLon = location.longitude,
                            pois = com.aerospring.arworld.core.data.repository.SamplePoiRepository.sampleAround(
                                location.latitude, location.longitude
                            ),
                            radiusKm = radiusKm,
                            selectedCategoryIds = selectedCategoryIds,
                            headingDegrees = heading
                        )
                    } else {
                        emptyList()
                    }
                }

                ArCameraView(
                    visibleMarkers = visibleMarkers,
                    onMarkerClick = { /* TODO: карточка с инфо о маркере — следующий шаг */ },
                    onSessionCreated = { arSessionCreated = true },
                    modifier = Modifier.fillMaxSize()
                )

                if (calibratedHeadingDegrees == null) {
                    Text(
                        text = "Калибровка компаса...",
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }

                TopControlPanel(
                    sliderPosition = sliderPosition,
                    onSliderPositionChange = { sliderPosition = it },
                    categories = defaultCategories,
                    selectedCategoryIds = selectedCategoryIds,
                    onCategoryToggle = { id ->
                        selectedCategoryIds = if (id in selectedCategoryIds) {
                            selectedCategoryIds - id
                        } else {
                            selectedCategoryIds + id
                        }
                    },
                    categoriesExpanded = categoriesExpanded,
                    onCategoriesExpandedToggle = { categoriesExpanded = !categoriesExpanded },
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // userLocation пока не используется визуально — понадобится на шаге с маркерами POI.
            } else {
                Column(modifier = Modifier.align(Alignment.Center)) {
                    Text("Для работы сервиса нужны доступ к камере и геолокации.")
                    Button(onClick = { permissionLauncher.launch(REQUIRED_AR_PERMISSIONS) }) {
                        Text("Предоставить доступ")
                    }
                }
            }
        }
    }
}