package com.aerospring.arworld.feature.furniture.ar

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import com.aerospring.arworld.feature.furniture.data.FurnitureModel
import com.aerospring.arworld.feature.furniture.model.FurnitureDownloadState
import com.aerospring.arworld.feature.furniture.model.FurnitureGlbDownloader
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun FurnitureSceneScreen(
    models: List<FurnitureModel>,
    onLogoutClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoadDispatcher = remember { Dispatchers.IO.limitedParallelism(3) }

    var pendingModel by remember { mutableStateOf<FurnitureModel?>(null) }
    var currentFrame by remember { mutableStateOf<Frame?>(null) }
    var placedModels by remember { mutableStateOf<List<PlacedModel>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var viewportWidthPx by remember { mutableStateOf(0) }
    var viewportHeightPx by remember { mutableStateOf(0) }

    // Кэш уже загруженных ModelInstance по modelUrl — чтобы не перекачивать и
    // не переразбирать .glb заново при каждой новой постановке той же модели.
    val instanceCache = remember { mutableMapOf<String, ModelInstance>() }

    suspend fun loadInstance(model: FurnitureModel): ModelInstance? {
        val cached = instanceCache[model.url]
        if (cached != null) {
            return cached
        }

        val fullUrl = "${ArWorldServerConfig.BASE_URL}${model.url}"
        FurnitureGlbDownloader.download(context, fullUrl).collect { state ->
            if (state is FurnitureDownloadState.Error) {
                statusMessage = "Не удалось скачать модель: ${state.message}"
            }
        }

        val loaded = try {
            withTimeout(45_000) {
                withContext(modelLoadDispatcher) {
                    suspendCancellableCoroutine<ModelInstance?> { continuation ->
                        modelLoader.loadModelInstanceAsync(fullUrl) { instance ->
                            if (continuation.isActive) {
                                continuation.resume(instance)
                            }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            statusMessage = "Таймаут загрузки модели (45с)"
            null
        }

        if (loaded != null) {
            instanceCache[model.url] = loaded
        }
        return loaded
    }

    fun placePendingModel() {
        val model = pendingModel
        if (model == null) {
            return
        }
        val frame = currentFrame
        if (frame == null) {
            statusMessage = "AR-сессия ещё не готова, подожди секунду"
            return
        }
        if (viewportWidthPx == 0 || viewportHeightPx == 0) {
            statusMessage = "Сцена ещё не готова, подожди секунду"
            return
        }

        val centerX = viewportWidthPx / 2f
        val centerY = viewportHeightPx / 2f

        val hitResults = frame.hitTest(centerX, centerY)
        var planeHit: com.google.ar.core.HitResult? = null
        for (result in hitResults) {
            val trackable = result.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(result.hitPose)) {
                planeHit = result
                break
            }
        }

        if (planeHit == null) {
            statusMessage = "Не вижу пол в этой точке — наведи камеру на свободный участок пола"
            return
        }

        val pose = planeHit.hitPose
        val candidatePosition = Position(pose.tx(), pose.ty(), pose.tz())

        scope.launch {
            val instance = loadInstance(model)
            if (instance == null) {
                statusMessage = "Не удалось загрузить модель"
                return@launch
            }

            // footprintRadius — половина наибольшего горизонтального размера bbox.
            // См. пометку в PlacedModel.kt про упрощение до круга.
            val boundingBox = instance.asset.boundingBox
            val footprintRadius = maxOf(boundingBox.halfExtent[0], boundingBox.halfExtent[2])

            val candidate = PlacedModel(
                instanceId = UUID.randomUUID().toString(),
                modelId = model.modelId,
                modelName = model.modelName,
                modelUrl = model.url,
                position = candidatePosition,
                rotationYDegrees = 0f,
                footprintRadius = footprintRadius,
            )

            var collides = false
            for (placed in placedModels) {
                if (placed.overlapsWith(candidate)) {
                    collides = true
                    break
                }
            }

            if (collides) {
                statusMessage = "Здесь уже стоит другая модель — наведи камеру левее"
            } else {
                placedModels = placedModels + candidate
                statusMessage = null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    viewportWidthPx = size.width
                    viewportHeightPx = size.height
                },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            planeRenderer = true,
            sessionConfiguration = { _: Session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            },
            onSessionUpdated = { _: Session, frame: Frame ->
                currentFrame = frame
            },
        ) {
            placedModels.forEach { placed ->
                key(placed.instanceId) {
                    val instance = instanceCache[placed.modelUrl]
                    if (instance != null) {
                        ModelNode(
                            modelInstance = instance,
                            position = placed.position,
                            rotation = Rotation(0f, placed.rotationYDegrees, 0f),
                            apply = {
                                // Тот же проверенный workaround "проталкивания" состояния в Filament.
                                this.isVisible = false
                                this.isVisible = true
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            IconButton(onClick = onLogoutClick) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Выйти")
            }
        }

        val currentStatusMessage = statusMessage
        if (currentStatusMessage != null) {
            Snackbar(modifier = Modifier.align(Alignment.Center).padding(16.dp)) {
                Text(currentStatusMessage)
            }
        }

        LazyRow(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(models) { model ->
                ModelThumbnail(
                    model = model,
                    isSelected = model.modelId == pendingModel?.modelId,
                    onClick = {
                        pendingModel = model
                        placePendingModel()
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelThumbnail(model: FurnitureModel, isSelected: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val boxModifier = if (isSelected) {
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .clickable { onClick() }
        } else {
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick() }
        }

        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            if (model.previewUrl != null) {
                AsyncImage(
                    model = "${ArWorldServerConfig.BASE_URL}${model.previewUrl}",
                    contentDescription = model.modelName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Chair, contentDescription = model.modelName, tint = Color.Gray)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(model.modelName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}