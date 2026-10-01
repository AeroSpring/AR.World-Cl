package com.aerospring.arworld.feature.furniture.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import android.view.MotionEvent
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.atan2

// Тот же допуск по экрану, что уже проверен в "Где что находится"/arbc для тапа по модели.
private const val TAP_TOLERANCE_DP = 70f

@Composable
fun FurnitureSceneScreen(
    models: List<FurnitureModel>,
    onLogoutClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoadDispatcher = remember { Dispatchers.IO.limitedParallelism(3) }

    var pendingModel by remember { mutableStateOf<FurnitureModel?>(null) }
    var currentFrame by remember { mutableStateOf<Frame?>(null) }
    var currentSession by remember { mutableStateOf<Session?>(null) }
    var placedModels by remember { mutableStateOf<List<PlacedModel>>(emptyList()) }
    var selectedInstanceId by remember { mutableStateOf<String?>(null) }
    var deleteMenuInstanceId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var viewportWidthPx by remember { mutableStateOf(0) }
    var viewportHeightPx by remember { mutableStateOf(0) }
    var touchDownPosition by remember { mutableStateOf(Offset.Zero) }
    var touchMoved by remember { mutableStateOf(false) }
    var isTwoFingerGesture by remember { mutableStateOf(false) }
    var lastTwoFingerAngle by remember { mutableStateOf(0f) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressTriggered by remember { mutableStateOf(false) }

    // Ключ — instanceId конкретной поставленной модели, НЕ modelUrl. Одна и та же
    // .glb-модель может быть поставлена несколько раз (например, несколько
    // одинаковых стульев вокруг стола) — у каждой копии должен быть свой
    // независимый ModelInstance, иначе Filament путает их (см. баг #5).
    val instanceCache = remember { mutableMapOf<String, ModelInstance>() }
    val isDarkTheme = isSystemInDarkTheme()
    val selectionColor = if (isDarkTheme) Color.Black else Color.White
    val selectionMaterial = remember(materialLoader, isDarkTheme) {
        materialLoader.createUnlitColorInstance(selectionColor.copy(alpha = 0.28f)) // ~72% прозрачности
    }

    val tapTolerancePx = with(density) { TAP_TOLERANCE_DP.dp.toPx() }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(3000)
            statusMessage = null
        }
    }

    // Без кэширования по URL — у каждой поставленной копии модели должен быть
    // СВОЙ ModelInstance (см. комментарий у instanceCache ниже).
    suspend fun loadFreshInstance(model: FurnitureModel): ModelInstance? {
        val fullUrl = "${ArWorldServerConfig.BASE_URL}${model.url}"
        FurnitureGlbDownloader.download(context, fullUrl).collect { state ->
            if (state is FurnitureDownloadState.Error) {
                statusMessage = "Не удалось скачать модель: ${state.message}"
            }
        }

        return try {
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
    }

    fun floorReferenceY(): Float? {
        val session = currentSession ?: return null
        return session.getAllTrackables(Plane::class.java)
            .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
            .minOfOrNull { it.centerPose.ty() }
    }

    // Предпочитаем именно пол: среди попаданий по разным плоскостям берём только
    // те, что близки к самой нижней известной горизонтальной плоскости — иначе
    // столы/стулья создают свой "ложный пол" выше настоящего.
    fun floorHitAt(xPx: Float, yPx: Float): Position? {
        val frame = currentFrame ?: return null
        val floorY = floorReferenceY()
        for (result in frame.hitTest(xPx, yPx)) {
            val trackable = result.trackable
            if (trackable is Plane &&
                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                trackable.isPoseInPolygon(result.hitPose)
            ) {
                val pose = result.hitPose
                if (floorY == null || kotlin.math.abs(pose.ty() - floorY) < 0.15f) {
                    return Position(pose.tx(), pose.ty(), pose.tz())
                }
            }
        }
        return null
    }

    fun placePendingModel() {
        val model = pendingModel ?: return
        if (viewportWidthPx == 0 || viewportHeightPx == 0) {
            statusMessage = "Сцена ещё не готова, подожди секунду"
            return
        }

        val candidatePosition = floorHitAt(viewportWidthPx / 2f, viewportHeightPx / 2f)
        if (candidatePosition == null) {
            statusMessage = "Не вижу пол в этой точке — наведи камеру на свободный участок пола"
            return
        }

        scope.launch {
            val instance = loadFreshInstance(model)
            if (instance == null) {
                statusMessage = "Не удалось загрузить модель"
                return@launch
            }

            val boundingBox = instance.asset.boundingBox
            val footprintRadius = maxOf(boundingBox.halfExtent[0], boundingBox.halfExtent[2])

            val newInstanceId = UUID.randomUUID().toString()
            val candidate = PlacedModel(
                instanceId = newInstanceId,
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
                instanceCache[newInstanceId] = instance
                placedModels = placedModels + candidate
                statusMessage = null
            }
        }
    }

    /** Тот же самый кастомный хит-тест по проекции, что и в ArCameraView/ArBcSceneView —
     *  ищем ближайшую поставленную модель, чья экранная проекция попадает в допуск. */
    fun findTappedModel(tapOffset: Offset): PlacedModel? {
        val frame = currentFrame ?: return null
        var closest: PlacedModel? = null
        var closestDistance = Float.MAX_VALUE
        for (placed in placedModels) {
            val screenPos = projectToScreen(frame.camera, placed.position, viewportWidthPx, viewportHeightPx)
                ?: continue
            val distance = (screenPos - tapOffset).getDistance()
            if (distance <= tapTolerancePx && distance < closestDistance) {
                closest = placed
                closestDistance = distance
            }
        }
        return closest
    }

    fun angleBetweenPointers(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    fun moveSelectedModel(xPx: Float, yPx: Float) {
        val id = selectedInstanceId ?: return
        val newPosition = floorHitAt(xPx, yPx) ?: return
        val current = placedModels.find { it.instanceId == id } ?: return
        val candidate = current.copy(position = newPosition)

        var collides = false
        for (placed in placedModels) {
            if (placed.instanceId != id && placed.overlapsWith(candidate)) {
                collides = true
                break
            }
        }
        if (!collides) {
            placedModels = placedModels.map { if (it.instanceId == id) candidate else it }
        }
    }

    fun rotateSelectedModel(deltaDegrees: Float) {
        val id = selectedInstanceId ?: return
        // Поворот не меняет площадь круга-footprint — проверка пересечения не нужна,
        // см. пометку в PlacedModel.kt.
        placedModels = placedModels.map {
            if (it.instanceId == id) it.copy(rotationYDegrees = it.rotationYDegrees + deltaDegrees) else it
        }
    }

    fun deleteModel(instanceId: String) {
        placedModels = placedModels.filterNot { it.instanceId == instanceId }
        instanceCache.remove(instanceId)
        if (selectedInstanceId == instanceId) selectedInstanceId = null
        if (deleteMenuInstanceId == instanceId) deleteMenuInstanceId = null
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
            onSessionUpdated = { session: Session, frame: Frame ->
                currentSession = session
                currentFrame = frame
            },
            onTouchEvent = { event: MotionEvent, _ ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownPosition = Offset(event.x, event.y)
                        touchMoved = false
                        isTwoFingerGesture = false
                        longPressTriggered = false
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(500)
                            val tapped = findTappedModel(touchDownPosition)
                            if (tapped != null) {
                                selectedInstanceId = tapped.instanceId
                                deleteMenuInstanceId = tapped.instanceId
                                longPressTriggered = true
                            }
                        }
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        longPressJob?.cancel()
                        if (event.pointerCount >= 2) {
                            isTwoFingerGesture = true
                            lastTwoFingerAngle = angleBetweenPointers(event)
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount >= 2 && isTwoFingerGesture) {
                            val currentAngle = angleBetweenPointers(event)
                            val delta = currentAngle - lastTwoFingerAngle
                            lastTwoFingerAngle = currentAngle
                            if (selectedInstanceId != null) {
                                rotateSelectedModel(-delta)
                            }
                        } else if (event.pointerCount == 1) {
                            val current = Offset(event.x, event.y)
                            val distance = (current - touchDownPosition).getDistance()
                            if (distance > tapTolerancePx) {
                                touchMoved = true
                                longPressJob?.cancel()
                                deleteMenuInstanceId = null
                                if (selectedInstanceId != null) {
                                    moveSelectedModel(event.x, event.y)
                                }
                            }
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.pointerCount <= 2) {
                            isTwoFingerGesture = false
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressJob?.cancel()
                        if (!touchMoved && !isTwoFingerGesture && !longPressTriggered && event.actionMasked == MotionEvent.ACTION_UP) {
                            deleteMenuInstanceId = null
                            val tapped = findTappedModel(Offset(event.x, event.y))
                            selectedInstanceId = tapped?.instanceId
                        }
                        isTwoFingerGesture = false
                        touchMoved = false
                        longPressTriggered = false
                    }
                }
                true
            },
        ) {
            placedModels.forEach { placed ->
                key(placed.instanceId) {
                    val instance = instanceCache[placed.instanceId]
                    if (instance != null) {
                        ModelNode(
                            modelInstance = instance,
                            autoAnimate = false,
                            position = placed.position,
                            rotation = Rotation(0f, placed.rotationYDegrees, 0f),
                            apply = {
                                this.isVisible = false
                                this.isVisible = true
                            },
                        )
                        if (placed.instanceId == selectedInstanceId) {
                            // Тонкое кольцо на полу под выделенной моделью — визуальный маркер выделения.
                            CylinderNode(
                                radius = placed.footprintRadius,
                                height = 0.002f,
                                materialInstance = selectionMaterial,
                                position = placed.position,
                                apply = {
                                    this.isVisible = false
                                    this.isVisible = true
                                },
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            IconButton(onClick = onLogoutClick) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Выйти", tint = Color.White)
            }
        }

        val currentStatusMessage = statusMessage
        if (currentStatusMessage != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
            ) {
                Text(currentStatusMessage)
            }
        }

        // Меню "удалить" — простая кнопка по центру экрана при активном long-press.
        // Позиционировать её точно над моделью в экранных координатах можно будет
        // доточить отдельно, если понадобится — для MVP центр экрана читается нормально.
        val menuId = deleteMenuInstanceId
        if (menuId != null) {
            Box(modifier = Modifier.align(Alignment.Center).padding(16.dp)) {
                Button(
                    onClick = { deleteModel(menuId) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить")
                }
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