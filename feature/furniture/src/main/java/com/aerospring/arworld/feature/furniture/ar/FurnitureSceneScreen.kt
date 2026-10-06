package com.aerospring.arworld.feature.furniture.ar

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
//import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.aerospring.arworld.feature.furniture.model.GlbBounds
import com.aerospring.arworld.feature.furniture.model.GlbBoundsReader
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.hypot

private const val TAP_TOLERANCE_DP = 70f

private data class LoadedModel(val instance: ModelInstance, val bounds: GlbBounds?)

/** Реальные габариты (полуразмеры) и смещение, переносящее опорную точку в центр основания
 *  (пол) / в центр в плоскости стены (стена). */
private data class ModelFootprint(
    val halfX: Float,
    val halfY: Float,
    val halfZ: Float,
    val modelOffset: Position,
)

private fun wrap180(a: Float): Float {
    var x = a % 360f
    if (x > 180f) x -= 360f
    if (x < -180f) x += 360f
    return x
}

/** Yaw из Euler-углов (градусы) узла, повёрнутого только вокруг Y.
 *  При |x| > 90 это тот же поворот в другой записи: (x-180, 180-y, z-180). */
private fun yawOfEuler(rx: Float, ry: Float): Float =
    if (kotlin.math.abs(wrap180(rx)) > 90f) wrap180(180f - ry) else wrap180(ry)

/**
 * Ручки к трём узлам одной поставленной модели: внешний (позиция + yaw), средний (наклон) и
 * ModelNode (смещение центрирования). *Sync сверяет фактические значения узла с тем, что должно быть
 * по состоянию, и при расхождении записывает правильные; возвращает описание исправления либо null.
 * *Push "дёргает" isVisible выкл/вкл.
 */
private class ModelNodeHandles {
    var outerSync: ((Position, Float) -> String?)? = null
    var midSync: ((Float) -> String?)? = null
    var modelSync: ((Position) -> String?)? = null
    var outerPush: (() -> Unit)? = null
    var midPush: (() -> Unit)? = null
    var modelPush: (() -> Unit)? = null
}

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
    var loadingModelId by remember { mutableStateOf<String?>(null) }
    var loadProgressText by remember { mutableStateOf("") }
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
    var showGrid by remember { mutableStateOf(true) }
    var horizontalPlaneCount by remember { mutableStateOf(0) }
    var verticalPlaneCount by remember { mutableStateOf(0) }
    var settledInstanceIds by remember { mutableStateOf(setOf<String>()) }

    val instanceCache = remember { mutableMapOf<String, ModelInstance>() }
    val footprintCache = remember { mutableMapOf<String, ModelFootprint>() }
    val nodeProbes = remember { mutableMapOf<String, () -> String>() }
    val handles = remember { mutableMapOf<String, ModelNodeHandles>() }
    val placedAtMs = remember { mutableMapOf<String, Long>() }
    val syncFixCount = remember { IntArray(1) }
    val syncLastInfo = remember { arrayOfNulls<String>(1) }
    var healCount by remember { mutableStateOf(0) }
    var healLine by remember { mutableStateOf("") }
    var debugLine by remember { mutableStateOf("") }
    val lastFrameTs = remember { LongArray(1) }
    val smoothFps = remember { FloatArray(1) }
    /*
    val isDarkTheme = isSystemInDarkTheme()
    val selectionColor = if (isDarkTheme) Color.Black else Color.White
    val selectionMaterial = remember(materialLoader, isDarkTheme) {
        materialLoader.createUnlitColorInstance(selectionColor.copy(alpha = 0.28f))
    }
    */
    val selectionMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0xFF29A8FF).copy(alpha = 0.40f))
    }

    val tapTolerancePx = with(density) { TAP_TOLERANCE_DP.dp.toPx() }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(3000)
            statusMessage = null
        }
    }

    LaunchedEffect(placedModels.map { it.instanceId }) {
        for (placed in placedModels) {
            if (placed.instanceId !in settledInstanceIds) {
                delay(100)
                settledInstanceIds = settledInstanceIds + placed.instanceId
            }
        }
    }

    // ТИКЕР. Раз в 0,3 с:
    // 1) "подталкивает" только что созданные узлы (первые 4 с): дёргает isVisible выкл/вкл уже ПОСЛЕ
    //    присоединения к сцене (известная особенность SceneView: состояние нового узла не доходит до
    //    Filament, пока свойство не переприсвоено; разовый вызов в apply{} успевает не всегда);
    // 2) переносит статистику покадровой синхронизации в жёлтую строку (чтобы не перерисовывать
    //    экран на каждый кадр).
    LaunchedEffect(Unit) {
        var shownCount = 0
        while (true) {
            delay(300)
            val now = System.currentTimeMillis()
            for (placed in placedModels) {
                val born = placedAtMs[placed.instanceId] ?: continue
                if (now - born < 4_000L) {
                    val h = handles[placed.instanceId] ?: continue
                    h.outerPush?.invoke()
                    h.midPush?.invoke()
                    h.modelPush?.invoke()
                }
            }
            if (syncFixCount[0] != shownCount) {
                shownCount = syncFixCount[0]
                healCount = shownCount
                healLine = syncLastInfo[0] ?: ""
            }
        }
    }

    suspend fun loadFreshInstance(model: FurnitureModel): LoadedModel? {
        val fullUrl = "${ArWorldServerConfig.BASE_URL}${model.url}"
        var localFile: File? = null
        FurnitureGlbDownloader.download(context, fullUrl).collect { state ->
            when (state) {
                is FurnitureDownloadState.Progress -> {
                    loadProgressText = if (state.isMegabytes) {
                        "Скачивание: ${state.percent} МБ"
                    } else {
                        "Скачивание: ${state.percent}%"
                    }
                }
                is FurnitureDownloadState.Done -> localFile = state.localFile
                is FurnitureDownloadState.Error -> {
                    statusMessage = "Не удалось скачать модель: ${state.message}"
                }
            }
        }
        loadProgressText = "Подготовка модели…"

        // Реальные габариты из самого файла (asset.boundingBox их искажает, см. GlbBoundsReader)
        val bounds = localFile?.let { file ->
            withContext(Dispatchers.IO) { GlbBoundsReader.read(file) }
        }

        val instance = try {
            withTimeout(45_000) {
                withContext(modelLoadDispatcher) {
                    suspendCancellableCoroutine<ModelInstance?> { continuation ->
                        modelLoader.loadModelInstanceAsync(fullUrl) { loaded ->
                            if (continuation.isActive) {
                                continuation.resume(loaded)
                            }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            statusMessage = "Таймаут загрузки модели (45с)"
            null
        }
        return if (instance != null) LoadedModel(instance, bounds) else null
    }

    fun floorReferenceY(): Float? {
        val session = currentSession ?: return null
        return session.getAllTrackables(Plane::class.java)
            .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
            .minOfOrNull { it.centerPose.ty() }
    }

    data class SurfaceHit(val position: Position, val wallYawDegrees: Float?, val surfaceType: SurfaceType)

    /**
     * restrictTo — если задан, ищем попадание только по этому типу поверхности
     * (используется при перетаскивании: модель с пола не должна случайно
     * "перескочить" на стену и наоборот).
     *
     * Для пола берём БЛИЖАЙШЕЕ подходящее попадание (frame.hitTest уже
     * сортирует от ближайшего), для стены — наоборот, САМОЕ ДАЛЬНЕЕ из
     * найденных вертикальных: иначе случайная близкая деталь (угол дверного
     * проёма, край мебели) перехватывает размещение раньше настоящей дальней
     * стены, в которую целился пользователь.
     */
    fun surfaceHitAt(xPx: Float, yPx: Float, restrictTo: SurfaceType? = null): SurfaceHit? {
        val frame = currentFrame ?: return null
        val results = frame.hitTest(xPx, yPx)
        val floorY = floorReferenceY()

        if (restrictTo != SurfaceType.WALL) {
            for (result in results) {
                val trackable = result.trackable
                if (trackable is Plane &&
                    trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    trackable.isPoseInPolygon(result.hitPose)
                ) {
                    val pose = result.hitPose
                    if (floorY == null || kotlin.math.abs(pose.ty() - floorY) < 0.15f) {
                        return SurfaceHit(Position(pose.tx(), pose.ty(), pose.tz()), null, SurfaceType.FLOOR)
                    }
                }
            }
            if (restrictTo == SurfaceType.FLOOR) return null
        }

        val farthestWallHit = results
            .filter { result ->
                val trackable = result.trackable
                trackable is Plane && trackable.type == Plane.Type.VERTICAL && trackable.isPoseInPolygon(result.hitPose)
            }
            .maxByOrNull { it.distance }

        if (farthestWallHit != null) {
            val pose = farthestWallHit.hitPose
            val normal = pose.rotateVector(floatArrayOf(0f, 1f, 0f))
            val yaw = Math.toDegrees(atan2(normal[0].toDouble(), normal[2].toDouble())).toFloat()
            return SurfaceHit(Position(pose.tx(), pose.ty(), pose.tz()), yaw, SurfaceType.WALL)
        }
        return null
    }

    fun placePendingModel() {
        // Пока грузится/садится предыдущая модель — новые нажатия игнорируем
        if (loadingModelId != null) return

        val model = pendingModel ?: return
        if (viewportWidthPx == 0 || viewportHeightPx == 0) {
            statusMessage = "Сцена ещё не готова, подожди секунду"
            return
        }

        val surfaceHit = surfaceHitAt(viewportWidthPx / 2f, viewportHeightPx / 2f)
        if (surfaceHit == null) {
            statusMessage = "Не вижу поверхность в этой точке — наведи камеру на пол или стену"
            return
        }

        // Флаг ставим синхронно, ДО launch: иначе два быстрых тапа в одном кадре
        // оба пройдут проверку выше.
        loadingModelId = model.modelId
        loadProgressText = "Подключаюсь…"
        statusMessage = null

        scope.launch {
            try {
                val loaded = loadFreshInstance(model)
                if (loaded == null) {
                    statusMessage = "Не удалось загрузить модель"
                    return@launch
                }
                val instance = loaded.instance
                val bounds = loaded.bounds

                val footprint = if (bounds != null) {
                    ModelFootprint(
                        halfX = bounds.halfX,
                        halfY = bounds.halfY,
                        halfZ = bounds.halfZ,
                        modelOffset = when (surfaceHit.surfaceType) {
                            // Опорная точка -> центр основания: центр по X/Z, низ на полу
                            SurfaceType.FLOOR -> Position(-bounds.centerX, -bounds.minY, -bounds.centerZ)
                            // На стене центрируем только в плоскости стены; глубину не трогаем
                            SurfaceType.WALL -> Position(-bounds.centerX, -bounds.centerY, 0f)
                        },
                    )
                } else {
                    // Не удалось прочитать габариты из файла — прежнее поведение
                    val box = instance.asset.boundingBox
                    ModelFootprint(box.halfExtent[0], box.halfExtent[1], box.halfExtent[2], Position(0f, 0f, 0f))
                }
                val footprintRadius = when (surfaceHit.surfaceType) {
                    SurfaceType.FLOOR -> maxOf(footprint.halfX, footprint.halfZ)
                    SurfaceType.WALL -> maxOf(footprint.halfX, footprint.halfY)
                }

                val newInstanceId = UUID.randomUUID().toString()
                val candidate = PlacedModel(
                    instanceId = newInstanceId,
                    modelId = model.modelId,
                    modelName = model.modelName,
                    modelUrl = model.url,
                    position = surfaceHit.position,
                    surfaceType = surfaceHit.surfaceType,
                    rotationYDegrees = surfaceHit.wallYawDegrees ?: 0f,
                    tiltDegrees = 0f,
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
                    footprintCache[newInstanceId] = footprint
                    placedAtMs[newInstanceId] = System.currentTimeMillis()
                    placedModels = placedModels + candidate
                    val statePos = candidate.position
                    scope.launch {
                        delay(1500)
                        val probe = nodeProbes[newInstanceId]?.invoke() ?: "узла нет"
                        debugLine = "%s fps=%d\nstate=%.2f,%.2f,%.2f\nnode %s".format(
                            candidate.surfaceType.name, smoothFps[0].toInt(),
                            statePos.x, statePos.y, statePos.z, probe,
                        )
                    }
                    statusMessage = null
                    // Держим блокировку, пока модель не "осела" в сцене (~100 мс).
                    // Страховка: не дольше 2 секунд, чтобы карусель не зависла навсегда.
                    withTimeoutOrNull(2_000) {
                        snapshotFlow { newInstanceId in settledInstanceIds }.first { it }
                    }
                }
            } finally {
                loadingModelId = null
            }
        }
    }

    fun findTappedModel(tapOffset: Offset): PlacedModel? {
        val frame = currentFrame ?: return null
        val cameraRight = frame.camera.displayOrientedPose.xAxis
        var closest: PlacedModel? = null
        var closestScore = Float.MAX_VALUE
        for (placed in placedModels) {
            val footprint = footprintCache[placed.instanceId]
            // Опорная точка — центр основания (пол) или центр (стена); для выбора берём центр объёма
            val lift = if (placed.surfaceType == SurfaceType.FLOOR) (footprint?.halfY ?: 0f) else 0f
            val center = Position(placed.position.x, placed.position.y + lift, placed.position.z)
            val centerOnScreen = projectToScreen(frame.camera, center, viewportWidthPx, viewportHeightPx)
                ?: continue

            // Зона выбора = видимый на экране размер модели (но не меньше прежних 70dp)
            val radiusMeters = footprint?.let { (it.halfX + it.halfY + it.halfZ) / 3f } ?: 0f
            val edge = Position(
                center.x + cameraRight[0] * radiusMeters,
                center.y + cameraRight[1] * radiusMeters,
                center.z + cameraRight[2] * radiusMeters,
            )
            val radiusPx = projectToScreen(frame.camera, edge, viewportWidthPx, viewportHeightPx)
                ?.let { (it - centerOnScreen).getDistance() } ?: 0f
            val reach = maxOf(tapTolerancePx, radiusPx)

            val distance = (centerOnScreen - tapOffset).getDistance()
            val score = distance / reach
            if (distance <= reach && score < closestScore) {
                closest = placed
                closestScore = score
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
        val current = placedModels.find { it.instanceId == id } ?: return
        val target = surfaceHitAt(xPx, yPx, restrictTo = current.surfaceType)?.position ?: return

        fun collidesAt(pos: Position): Boolean {
            val candidate = current.copy(position = pos)
            for (placed in placedModels) {
                if (placed.instanceId != id && placed.overlapsWith(candidate)) return true
            }
            return false
        }

        // Модель не "замирает" у препятствия, а едет как можно дальше к пальцу: сначала цель,
        // затем скольжение вдоль одной из осей (пол), затем доли пути от текущего места к цели.
        val from = current.position
        val options = ArrayList<Position>(6)
        options.add(target)
        if (current.surfaceType == SurfaceType.FLOOR) {
            options.add(Position(target.x, target.y, from.z))
            options.add(Position(from.x, target.y, target.z))
        }
        for (k in floatArrayOf(0.75f, 0.5f, 0.25f)) {
            options.add(
                Position(
                    from.x + (target.x - from.x) * k,
                    from.y + (target.y - from.y) * k,
                    from.z + (target.z - from.z) * k,
                ),
            )
        }
        val free = options.firstOrNull { !collidesAt(it) } ?: return
        if (free.x == from.x && free.y == from.y && free.z == from.z) return
        placedModels = placedModels.map { if (it.instanceId == id) it.copy(position = free) else it }
    }

    /** Для FLOOR крутим rotationYDegrees (вертикальная ось, как раньше).
     *  Для WALL крутим tiltDegrees — наклон вокруг нормали стены (как картина). */
    fun rotateSelectedModel(deltaDegrees: Float) {
        val id = selectedInstanceId ?: return
        placedModels = placedModels.map { placed ->
            if (placed.instanceId != id) return@map placed
            when (placed.surfaceType) {
                SurfaceType.FLOOR -> placed.copy(rotationYDegrees = placed.rotationYDegrees + deltaDegrees)
                SurfaceType.WALL -> placed.copy(tiltDegrees = placed.tiltDegrees + deltaDegrees)
            }
        }
    }

    fun deleteModel(instanceId: String) {
        placedModels = placedModels.filterNot { it.instanceId == instanceId }
        instanceCache.remove(instanceId)
        footprintCache.remove(instanceId)
        nodeProbes.remove(instanceId)
        handles.remove(instanceId)
        placedAtMs.remove(instanceId)
        if (selectedInstanceId == instanceId) selectedInstanceId = null
        if (deleteMenuInstanceId == instanceId) deleteMenuInstanceId = null
    }

    // Покадровая синхронизация: узлы принудительно приводятся к состоянию (позиция, yaw, наклон,
    // смещение центрирования), не дожидаясь перекомпозиции Compose, которая иногда запаздывает
    // (видно по жёлтой строке: поворот в состоянии уже есть, а узел ещё нет).
    fun syncNodes() {
        for (placed in placedModels) {
            val id = placed.instanceId
            val h = handles[id] ?: continue
            val footprint = footprintCache[id] ?: continue
            val settled = id in settledInstanceIds
            val yaw = if (settled) placed.rotationYDegrees else 0f
            val tilt = if (settled) placed.tiltDegrees else 0f
            var info: String? = null
            h.outerSync?.invoke(placed.position, yaw)?.let { info = (info ?: "") + it + " "; syncFixCount[0] += 1 }
            h.midSync?.invoke(tilt)?.let { info = (info ?: "") + it + " "; syncFixCount[0] += 1 }
            h.modelSync?.invoke(footprint.modelOffset)?.let { info = (info ?: "") + it + " "; syncFixCount[0] += 1 }
            if (info != null) {
                syncLastInfo[0] = "fix#%d %s: %s".format(syncFixCount[0], placed.modelName, info)
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
            planeRenderer = showGrid,
            sessionConfiguration = { _: Session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            },
            onSessionUpdated = { session: Session, frame: Frame ->
                val ts = frame.timestamp
                if (lastFrameTs[0] != 0L) {
                    val dt = (ts - lastFrameTs[0]) / 1_000_000_000f
                    if (dt > 0f) smoothFps[0] = smoothFps[0] * 0.9f + (1f / dt) * 0.1f
                }
                lastFrameTs[0] = ts
                syncNodes()
                currentSession = session
                currentFrame = frame
                val planes = session.getAllTrackables(Plane::class.java)
                horizontalPlaneCount = planes.count {
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING
                }
                verticalPlaneCount = planes.count {
                    it.type == Plane.Type.VERTICAL && it.trackingState == TrackingState.TRACKING
                }
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
                    val footprint = footprintCache[placed.instanceId]
                    if (instance != null && footprint != null) {
                        val isSettled = placed.instanceId in settledInstanceIds
                        // Родитель — разворот "от стены"/по полу (yaw). Средний узел — только наклон
                        // (tilt) вокруг центра модели. ModelNode внутри смещён так, что опорная точка
                        // = центр основания, поэтому всё крутится вокруг центра модели.
                        Node(
                            position = placed.position,
                            rotation = if (isSettled) Rotation(0f, placed.rotationYDegrees, 0f) else Rotation(0f, 0f, 0f),
                            apply = {
                                val node = this
                                val h = handles.getOrPut(placed.instanceId) { ModelNodeHandles() }
                                h.outerSync = { pos: Position, yaw: Float ->
                                    val p = node.position
                                    val r = node.rotation
                                    val curYaw = yawOfEuler(r.x, r.y)
                                    val flipped = kotlin.math.abs(wrap180(r.x)) > 90f
                                    val ex = if (flipped) wrap180(r.x - 180f) else wrap180(r.x)
                                    val ez = if (flipped) wrap180(r.z - 180f) else wrap180(r.z)
                                    val nearGimbal = kotlin.math.abs(kotlin.math.abs(curYaw) - 90f) < 8f
                                    val bad = kotlin.math.abs(p.x - pos.x) > 0.005f ||
                                            kotlin.math.abs(p.y - pos.y) > 0.005f ||
                                            kotlin.math.abs(p.z - pos.z) > 0.005f ||
                                            kotlin.math.abs(wrap180(curYaw - yaw)) > 0.5f ||
                                            (!nearGimbal && (kotlin.math.abs(ex) > 1f || kotlin.math.abs(ez) > 1f))
                                    if (bad) {
                                        val before = "O(%.2f,%.2f,%.2f ry=%.0f)".format(p.x, p.y, p.z, curYaw)
                                        node.position = Position(pos.x, pos.y, pos.z)
                                        node.rotation = Rotation(0f, yaw, 0f)
                                        before
                                    } else {
                                        null
                                    }
                                }
                                h.outerPush = {
                                    node.isVisible = false
                                    node.isVisible = true
                                }
                                this.isVisible = false
                                this.isVisible = true
                            },
                        ) {
                            Node(
                                rotation = if (isSettled) Rotation(0f, 0f, placed.tiltDegrees) else Rotation(0f, 0f, 0f),
                                apply = {
                                    val node = this
                                    val h = handles.getOrPut(placed.instanceId) { ModelNodeHandles() }
                                    h.midSync = { tilt: Float ->
                                        val p = node.position
                                        val r = node.rotation
                                        val bad = kotlin.math.abs(p.x) > 0.005f ||
                                                kotlin.math.abs(p.y) > 0.005f ||
                                                kotlin.math.abs(p.z) > 0.005f ||
                                                kotlin.math.abs(wrap180(r.x)) > 1f ||
                                                kotlin.math.abs(wrap180(r.y)) > 1f ||
                                                kotlin.math.abs(wrap180(r.z - tilt)) > 0.5f
                                        if (bad) {
                                            val before = "T(rz=%.0f)".format(r.z)
                                            node.position = Position(0f, 0f, 0f)
                                            node.rotation = Rotation(0f, 0f, tilt)
                                            before
                                        } else {
                                            null
                                        }
                                    }
                                    h.midPush = {
                                        node.isVisible = false
                                        node.isVisible = true
                                    }
                                    this.isVisible = false
                                    this.isVisible = true
                                },
                            ) {
                                ModelNode(
                                    modelInstance = instance,
                                    autoAnimate = false,
                                    position = footprint.modelOffset,
                                    apply = {
                                        val modelNode = this
                                        val h = handles.getOrPut(placed.instanceId) { ModelNodeHandles() }
                                        nodeProbes[placed.instanceId] = {
                                            val p = modelNode.worldPosition
                                            val r = modelNode.worldRotation
                                            "pos=%.2f,%.2f,%.2f rot=%.0f,%.0f,%.0f".format(p.x, p.y, p.z, r.x, r.y, r.z)
                                        }
                                        h.modelSync = { offset: Position ->
                                            val p = modelNode.position
                                            val r = modelNode.rotation
                                            val bad = kotlin.math.abs(p.x - offset.x) > 0.005f ||
                                                    kotlin.math.abs(p.y - offset.y) > 0.005f ||
                                                    kotlin.math.abs(p.z - offset.z) > 0.005f ||
                                                    kotlin.math.abs(wrap180(r.x)) > 1f ||
                                                    kotlin.math.abs(wrap180(r.y)) > 1f ||
                                                    kotlin.math.abs(wrap180(r.z)) > 1f
                                            if (bad) {
                                                val before = "M(%.2f,%.2f,%.2f)".format(p.x, p.y, p.z)
                                                modelNode.position = Position(offset.x, offset.y, offset.z)
                                                modelNode.rotation = Rotation(0f, 0f, 0f)
                                                before
                                            } else {
                                                null
                                            }
                                        }
                                        h.modelPush = {
                                            modelNode.isVisible = false
                                            modelNode.isVisible = true
                                        }
                                        this.isVisible = false
                                        this.isVisible = true
                                    },
                                )
                            }
                        }
                        if (placed.instanceId == selectedInstanceId) {
                            // Радиус описывает основание модели + 5 см, чтобы кружок всегда
                            // выступал из-под корпуса (даже у большой кухни)
                            val ringRadius = when (placed.surfaceType) {
                                SurfaceType.FLOOR -> hypot(footprint.halfX, footprint.halfZ)
                                SurfaceType.WALL -> hypot(footprint.halfX, footprint.halfY)
                            } + 0.05f
                            key(placed.position, placed.surfaceType) {
                                CylinderNode(
                                    radius = ringRadius,
                                    height = 0.002f,
                                    materialInstance = selectionMaterial,
                                    position = placed.position,
                                    rotation = if (placed.surfaceType == SurfaceType.WALL)
                                        Rotation(90f, placed.rotationYDegrees, 0f)
                                    else
                                        Rotation(0f, 0f, 0f),
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
            IconButton(onClick = { showGrid = !showGrid }) {
                Icon(
                    if (showGrid) Icons.Default.GridOff else Icons.Default.GridOn,
                    contentDescription = if (showGrid) "Скрыть сетку" else "Показать сетку",
                    tint = Color.White,
                )
            }
        }

        Text(
            "Пол: $horizontalPlaneCount  Стены: $verticalPlaneCount",
            color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )

        Text(
            debugLine,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, start = 190.dp, end = 16.dp),
        )

        Text(
            healLine,
            color = Color.Yellow,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 92.dp, start = 100.dp, end = 16.dp),
        )

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

        val loadingId = loadingModelId
        if (loadingId != null) {
            val loadingName = models.find { it.modelId == loadingId }?.modelName.orEmpty()
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Column {
                        Text("Загружаю «$loadingName»")
                        Text(loadProgressText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        val selectedModelName = placedModels.find { it.instanceId == selectedInstanceId }?.modelName
        if (selectedModelName != null &&
            loadingId == null &&
            currentStatusMessage == null &&
            deleteMenuInstanceId == null
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Выбрано: «$selectedModelName»")
                    Text(
                        "Тяни пальцем — переместить · двумя пальцами — повернуть · долгое нажатие — удалить",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                    isLoading = model.modelId == loadingModelId,
                    isLocked = loadingModelId != null,
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
private fun ModelThumbnail(
    model: FurnitureModel,
    isSelected: Boolean,
    isLoading: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .alpha(if (isLocked && !isLoading) 0.4f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val highlighted = isSelected || isLoading
        val boxModifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let {
                if (highlighted) it.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else it
            }
            .clickable(enabled = !isLocked) { onClick() }

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
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(model.modelName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}