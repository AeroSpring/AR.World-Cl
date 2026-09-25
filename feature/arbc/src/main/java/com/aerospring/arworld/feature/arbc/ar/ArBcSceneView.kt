package com.aerospring.arworld.feature.arbc.ar

import android.opengl.Matrix
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.core.ui.theme.ArWorldTheme
import com.aerospring.arworld.feature.arbc.data.ArBcScene
import com.aerospring.arworld.feature.arbc.data.Interaction
import com.aerospring.arworld.feature.arbc.data.SceneModel
import com.aerospring.arworld.feature.arbc.model.ArbcDownloadState
import com.aerospring.arworld.feature.arbc.model.ArbcGlbDownloader
import com.aerospring.arworld.feature.arbc.ui.ArbcLoadingBadge
import com.google.ar.core.Config
import com.google.ar.core.Frame
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.sqrt

// Те же проверенные константы и формула компенсации перспективы, что и у плашки
// загрузки POI в ArCameraView.kt ("Где что находится") — размер плашки растёт
// пропорционально расстоянию до неё от точки старта AR-сессии, чтобы не быть
// то огромной вблизи, то незаметной вдали.
private const val BADGE_REFERENCE_DISTANCE_METERS = 15f
private const val BADGE_BASE_SCALE = 2.0f

/** Радиус "прощения" вокруг проекции модели на экране, в dp — тот же проверенный
 *  допуск, что и у POI в ArCameraView.kt. */
private const val TAP_TOLERANCE_DP = 70f

/**
 * AR-сцена одной AR.Визитки.
 *
 * В отличие от "Где что находится", здесь НЕТ hit-test по плоскости и НЕТ AnchorNode:
 * position/rotation/scale каждой модели из scene.json передаются в ModelNode напрямую,
 * как локальные координаты ARSceneView. ARCore и так привязывает локальный (0,0,0)
 * к точке старта сессии — а это и есть наша "точка сканирования", без всякого GPS.
 * Модели поэтому появляются сразу при старте сессии, не дожидаясь обнаружения плоскости.
 *
 * Попадание тапа по модели считается ИСКЛЮЧИТЕЛЬНО собственной математикой проекции
 * (Camera.getViewMatrix/getProjectionMatrix + android.opengl.Matrix) — той же самой,
 * что в ArCameraView.kt. Встроенный picking SceneView (по узлу, через
 * onSingleTapConfirmed) оказался ненадёжен для только что созданных узлов (баг
 * апстрима, см. техреференс проекта) и приводил к периодическим промахам по одной
 * из моделей сцены — на него больше не полагаемся вообще.
 *
 * defaultAnimation.index проигрывается через com.google.android.filament.gltfio.Animator
 * по индексу, с ручным продвижением времени каждый кадр — своего зацикливания в
 * библиотеке нет.
 */
@Composable
fun ArBcSceneView(
    scene: ArBcScene,
    onInteraction: (Interaction) -> Unit,
    onBackgroundClick: () -> Unit,
    onSessionCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val viewNodeWindowManager = rememberViewNodeManager()

    val modelLoadDispatcher = remember { Dispatchers.IO.limitedParallelism(3) }

    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Позиция + сама модель для каждой модели сцены — пересчитывается на каждой
    // рекомпозиции (дешёвая чистая операция), используется ТОЛЬКО для собственного
    // хит-теста тапа ниже, никак не связано с созданием/пересозданием 3D-узлов.
    val modelHitTestData = remember { mutableMapOf<String, Pair<Position, SceneModel>>() }
    modelHitTestData.clear()
    scene.models.forEach { sceneModel ->
        modelHitTestData[sceneModel.modelId] = Position(
            sceneModel.position.x,
            sceneModel.position.y,
            sceneModel.position.z
        ) to sceneModel
    }

    ARSceneView(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        viewNodeWindowManager = viewNodeWindowManager,
        // Плоскость не используется — модели не "приземляются" на пол, они висят
        // в воздухе в заданных координатах от точки старта сессии.
        planeFindingMode = Config.PlaneFindingMode.DISABLED,
        depthMode = Config.DepthMode.DISABLED,
        onSessionUpdated = { _, frame -> latestFrame = frame },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { motionEvent, _ ->
                val frame = latestFrame
                val size = viewportSize
                val bestModel = if (frame != null && size.width > 0 && size.height > 0) {
                    val viewMatrix = FloatArray(16)
                    val projMatrix = FloatArray(16)
                    frame.camera.getViewMatrix(viewMatrix, 0)
                    frame.camera.getProjectionMatrix(projMatrix, 0, 0.01f, 1000f)
                    val viewProj = FloatArray(16)
                    Matrix.multiplyMM(viewProj, 0, projMatrix, 0, viewMatrix, 0)

                    val tolerancePx = with(density) { TAP_TOLERANCE_DP.dp.toPx() }
                    var closestModel: SceneModel? = null
                    var closestDistSq = Float.MAX_VALUE

                    modelHitTestData.values.forEach { (pos, model) ->
                        val worldVec = floatArrayOf(pos.x, pos.y, pos.z, 1f)
                        val clip = FloatArray(4)
                        Matrix.multiplyMV(clip, 0, viewProj, 0, worldVec, 0)
                        if (clip[3] > 0.0001f) {
                            val ndcX = clip[0] / clip[3]
                            val ndcY = clip[1] / clip[3]
                            val screenX = (ndcX * 0.5f + 0.5f) * size.width
                            val screenY = (1f - (ndcY * 0.5f + 0.5f)) * size.height
                            val dx = screenX - motionEvent.x
                            val dy = screenY - motionEvent.y
                            val distSq = dx * dx + dy * dy
                            if (distSq < closestDistSq) {
                                closestDistSq = distSq
                                closestModel = model
                            }
                        }
                    }

                    if (closestDistSq <= tolerancePx * tolerancePx) closestModel else null
                } else {
                    null
                }

                if (bestModel != null) {
                    bestModel.interactions.firstOrNull()?.let(onInteraction)
                } else {
                    onBackgroundClick()
                }
            }
        ),
        onSessionCreated = { onSessionCreated() }
    ) {
        scene.models.forEach { sceneModel ->
            key(sceneModel.modelId) {
                val downloadState by produceState<ArbcDownloadState>(
                    initialValue = ArbcDownloadState.Progress(0),
                    key1 = sceneModel.modelUrl
                ) {
                    ArbcGlbDownloader.download(context, sceneModel.modelUrl).collect { value = it }
                }

                var modelInstance by remember(sceneModel.modelId) { mutableStateOf<ModelInstance?>(null) }
                var modelLoadError by remember(sceneModel.modelId) { mutableStateOf<String?>(null) }

                LaunchedEffect(sceneModel.modelId) {
                    try {
                        withTimeout(45_000) {
                            withContext(modelLoadDispatcher) {
                                modelLoader.loadModelInstanceAsync(sceneModel.modelUrl) { instance ->
                                    if (instance != null) {
                                        modelInstance = instance
                                    } else {
                                        modelLoadError = "loadModelInstanceAsync вернул null"
                                    }
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        modelLoadError = "Таймаут загрузки (45с) — файл слишком большой или сервер отвечает медленно"
                    } catch (e: Exception) {
                        modelLoadError = e.message ?: "Ошибка загрузки 3D-модели"
                    }
                }

                val currentModelInstance = modelInstance
                if (currentModelInstance != null) {
                    var placedNode by remember(sceneModel.modelId) { mutableStateOf<ModelNode?>(null) }

                    ModelNode(
                        modelInstance = currentModelInstance,
                        scaleToUnits = sceneModel.scale,
                        position = Position(
                            sceneModel.position.x,
                            sceneModel.position.y,
                            sceneModel.position.z
                        ),
                        rotation = Rotation(
                            x = sceneModel.rotation.x,
                            y = sceneModel.rotation.y,
                            z = sceneModel.rotation.z
                        ),
                        apply = {
                            // Проталкивание состояния узла в Filament (баг апстрима, см.
                            // техреференс) — по-прежнему нужно для корректного РЕНДЕРА
                            // только что созданного узла. К тапам это больше не имеет
                            // отношения: попадание считается отдельной математикой выше,
                            // а не через сам узел.
                            this.isVisible = false
                            this.isVisible = true
                            placedNode = this
                        }
                    )

                    val defaultAnimation = sceneModel.defaultAnimation
                    LaunchedEffect(placedNode, defaultAnimation) {
                        val node = placedNode ?: return@LaunchedEffect
                        val anim = defaultAnimation ?: return@LaunchedEffect
                        val animator = node.animator

                        if (anim.index < 0 || anim.index >= animator.animationCount) {
                            // Индекс из scene.json не существует в этой конкретной .glb —
                            // не падаем, просто не анимируем (модель в бинд-позе).
                            return@LaunchedEffect
                        }

                        val durationSeconds = animator.getAnimationDuration(anim.index)
                        val startNanos = withFrameNanos { it }

                        while (isActive) {
                            val elapsedSeconds = withFrameNanos { frameNanos ->
                                (frameNanos - startNanos) / 1_000_000_000f
                            }
                            val time = when {
                                durationSeconds <= 0f -> 0f
                                anim.loop -> elapsedSeconds % durationSeconds
                                else -> elapsedSeconds.coerceAtMost(durationSeconds)
                            }
                            animator.applyAnimation(anim.index, time)
                            animator.updateBoneMatrices()
                            if (!anim.loop && elapsedSeconds >= durationSeconds) break
                        }
                    }
                } else {
                    val statusText = modelLoadError?.let { "Ошибка: $it" }
                        ?: when (val state = downloadState) {
                            is ArbcDownloadState.Progress -> if (state.isMegabytes) {
                                "Загрузка ${state.percent} МБ"
                            } else {
                                "Загрузка ${state.percent}%"
                            }
                            is ArbcDownloadState.Done -> "Обработка..."
                            is ArbcDownloadState.Error -> "Ошибка: ${state.message}"
                        }

                    // Та же формула компенсации перспективы, что и у POI-плашек: масштаб
                    // растёт пропорционально дистанции от точки старта сессии до плашки.
                    val badgeDistance3d = sqrt(
                        sceneModel.position.x * sceneModel.position.x +
                                sceneModel.position.y * sceneModel.position.y +
                                sceneModel.position.z * sceneModel.position.z
                    )
                    val badgeScale = BADGE_BASE_SCALE * (badgeDistance3d / BADGE_REFERENCE_DISTANCE_METERS)

                    ViewNode(
                        windowManager = viewNodeWindowManager,
                        unlit = true,
                        position = Position(
                            sceneModel.position.x,
                            sceneModel.position.y,
                            sceneModel.position.z
                        ),
                        scale = Scale(badgeScale, badgeScale, badgeScale)
                    ) {
                        ArWorldTheme(darkTheme = true) {
                            ArbcLoadingBadge(
                                statusText = statusText,
                                onClick = {
                                    sceneModel.interactions.firstOrNull()?.let(onInteraction)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}