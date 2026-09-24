package com.aerospring.arworld.feature.whereanythingis.ar

import android.opengl.Matrix
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.core.ui.theme.ArWorldTheme
import com.aerospring.arworld.feature.whereanythingis.model.GlbDownloadState
import com.aerospring.arworld.feature.whereanythingis.model.GlbDownloader
import com.aerospring.arworld.feature.whereanythingis.ui.MarkerLoadingBadge
import com.aerospring.arworld.feature.whereanythingis.ui.categoryColor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.TubeNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.atan2
import kotlin.math.sqrt

private const val MARKER_HEIGHT_METERS = 4f
private const val RAY_RADIUS_METERS = 0.02f
private const val BADGE_VERTICAL_OFFSET_METERS = 0.6f
private const val MARKER_BASE_SIZE_METERS = 3.0f

private const val BADGE_REFERENCE_DISTANCE_METERS = 15f
private const val BADGE_BASE_SCALE = 2.0f

/** Радиус "прощения" вокруг проекции маркера на экране, в dp — тап в пределах этой зоны засчитывается. */
private const val TAP_TOLERANCE_DP = 70f

/**
 * Живой фид камеры ARCore + реальные анимированные .glb модели (ModelNode) на месте POI,
 * с плашкой прогресса загрузки (ViewNode) пока модель ещё не готова, и лучом до земли.
 *
 * Попадание тапа по модели считается ИСКЛЮЧИТЕЛЬНО через собственную математику проекции
 * (официальные ARCore Camera.getViewMatrix/getProjectionMatrix + android.opengl.Matrix) —
 * встроенный picking библиотеки SceneView оказался ненадёжен для только что созданных узлов
 * (подтверждённый баг апстрима), поэтому мы не полагаемся на него вообще.
 */
@Composable
fun ArCameraView(
    visibleMarkers: List<VisibleMarker>,
    onMarkerClick: (VisibleMarker) -> Unit,
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

    // Мировая позиция + сам маркер для каждой видимой точки — пересчитывается заново на каждой
    // рекомпозиции (дешёвая чистая операция), используется ТОЛЬКО для нашего собственного
    // хит-теста тапа, никак не связано с созданием/пересозданием 3D-узлов.
    val markerHitTestData = remember { mutableMapOf<String, Pair<Position, VisibleMarker>>() }
    markerHitTestData.clear()
    visibleMarkers.forEach { visible ->
        val h = MARKER_HEIGHT_METERS + visible.poi.modelExceeding.toFloat()
        markerHitTestData[visible.poi.id] = Position(visible.arXMeters, h, visible.arZMeters) to visible
    }

    ARSceneView(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        viewNodeWindowManager = viewNodeWindowManager,
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
        depthMode = Config.DepthMode.AUTOMATIC,
        onSessionUpdated = { _, frame -> latestFrame = frame },
        onGestureListener = io.github.sceneview.rememberOnGestureListener(
            onSingleTapConfirmed = { motionEvent, _ ->
                val frame = latestFrame
                val size = viewportSize
                val bestMarker = if (frame != null && size.width > 0 && size.height > 0) {
                    val viewMatrix = FloatArray(16)
                    val projMatrix = FloatArray(16)
                    frame.camera.getViewMatrix(viewMatrix, 0)
                    frame.camera.getProjectionMatrix(projMatrix, 0, 0.01f, 1000f)
                    val viewProj = FloatArray(16)
                    Matrix.multiplyMM(viewProj, 0, projMatrix, 0, viewMatrix, 0)

                    val tolerancePx = with(density) { TAP_TOLERANCE_DP.dp.toPx() }
                    var closestMarker: VisibleMarker? = null
                    var closestDistSq = Float.MAX_VALUE

                    markerHitTestData.values.forEach { (pos, marker) ->
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
                                closestMarker = marker
                            }
                        }
                    }

                    if (closestDistSq <= tolerancePx * tolerancePx) closestMarker else null
                } else {
                    null
                }

                if (bestMarker != null) onMarkerClick(bestMarker) else onBackgroundClick()
            }
        ),
        onSessionCreated = { onSessionCreated() }
    ) {
        visibleMarkers.forEach { visible ->
            key(visible.poi.id) {
                val color = categoryColor(visible.poi.category)
                val rayMaterial = remember(visible.poi.id) {
                    materialLoader.createUnlitColorInstance(color)
                }

                val downloadState by produceState<GlbDownloadState>(
                    initialValue = GlbDownloadState.Progress(0),
                    key1 = visible.poi.modelUrl
                ) {
                    GlbDownloader.download(context, visible.poi.modelUrl).collect { value = it }
                }

                var modelInstance by remember(visible.poi.id) { mutableStateOf<ModelInstance?>(null) }
                var modelLoadError by remember(visible.poi.id) { mutableStateOf<String?>(null) }

                LaunchedEffect(visible.poi.id) {
                    try {
                        withTimeout(45_000) {
                            withContext(modelLoadDispatcher) {
                                modelLoader.loadModelInstanceAsync(visible.poi.modelUrl) { instance ->
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

                val effectiveHeight = MARKER_HEIGHT_METERS + visible.poi.modelExceeding.toFloat()

                val currentModelInstance = modelInstance
                if (currentModelInstance != null) {
                    ModelNode(
                        modelInstance = currentModelInstance,
                        scaleToUnits = MARKER_BASE_SIZE_METERS * visible.scale,
                        position = Position(visible.arXMeters, effectiveHeight, visible.arZMeters),
                        rotation = Rotation(x = visible.poi.rotationXDegrees.toFloat())
                    )
                } else {
                    val statusText = modelLoadError?.let { "Ошибка: $it" }
                        ?: when (val state = downloadState) {
                            is GlbDownloadState.Progress -> if (state.isMegabytes) {
                                "${state.percent} МБ"
                            } else {
                                "${state.percent}%"
                            }
                            is GlbDownloadState.Done -> "Обработка..."
                            is GlbDownloadState.Error -> "Ошибка: ${state.message}"
                        }

                    val badgeYawDegrees = Math.toDegrees(
                        atan2(-visible.arXMeters.toDouble(), -visible.arZMeters.toDouble())
                    ).toFloat()

                    val badgeHeight = effectiveHeight + BADGE_VERTICAL_OFFSET_METERS
                    val badgeDistance3d = sqrt(
                        visible.arXMeters * visible.arXMeters +
                                badgeHeight * badgeHeight +
                                visible.arZMeters * visible.arZMeters
                    )
                    val compensation = badgeDistance3d / BADGE_REFERENCE_DISTANCE_METERS
                    val badgeScale = BADGE_BASE_SCALE * compensation

                    ViewNode(
                        windowManager = viewNodeWindowManager,
                        unlit = true,
                        position = Position(visible.arXMeters, badgeHeight, visible.arZMeters),
                        rotation = Rotation(y = badgeYawDegrees),
                        scale = io.github.sceneview.math.Scale(badgeScale, badgeScale, badgeScale)
                    ) {
                        ArWorldTheme(darkTheme = true) {
                            MarkerLoadingBadge(statusText = statusText)
                        }
                    }
                }

                TubeNode(
                    points = listOf(
                        Position(visible.arXMeters, effectiveHeight, visible.arZMeters),
                        Position(visible.arXMeters, 0f, visible.arZMeters)
                    ),
                    radius = RAY_RADIUS_METERS,
                    materialInstance = rayMaterial
                )
            }
        }
    }
}