package com.aerospring.arworld.feature.whereanythingis.ar

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aerospring.arworld.core.ui.theme.ArWorldTheme
import com.aerospring.arworld.feature.whereanythingis.model.GlbDownloadState
import com.aerospring.arworld.feature.whereanythingis.model.GlbDownloader
import com.aerospring.arworld.feature.whereanythingis.ui.MarkerLoadingBadge
import com.aerospring.arworld.feature.whereanythingis.ui.categoryColor
import com.aerospring.arworld.feature.whereanythingis.ar.VisibleMarker
import com.google.ar.core.Config
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.TubeNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.atan2
import kotlin.math.sqrt

private const val MARKER_HEIGHT_METERS = 4f
private const val RAY_RADIUS_METERS = 0.02f
private const val BADGE_VERTICAL_OFFSET_METERS = 0.6f

/** Опорный размер маркера в метрах — все .glb вписываются в эту высоту (до применения visible.scale). */
private const val MARKER_BASE_SIZE_METERS = 3.0f

// Референсная дистанция и границы компенсации перспективы плашки загрузки.
// Компенсация не идеальна (статична относительно точки старта сессии, а не текущей позиции
// камеры), поэтому её диапазон сознательно ограничен (coerceIn), чтобы дальние точки при
// большом радиусе не превращались в гигантские полотна.
private const val BADGE_REFERENCE_DISTANCE_METERS = 15f
private const val BADGE_BASE_SCALE = 2.0f

/**
 * Живой фид камеры ARCore + реальные анимированные .glb модели (ModelNode) на месте POI,
 * с плашкой прогресса загрузки (ViewNode) пока модель ещё не готова, и лучом до земли.
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
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val viewNodeWindowManager = rememberViewNodeManager()

    // Не глобальная "очищай и заполняй заново каждый кадр" карта (это и было источником бага
    // "кликается только одна модель") — вместо этого каждый маркер регистрирует себя сам через
    // DisposableEffect, привязанный к жизненному циклу СВОЕГО узла: добавляется при создании,
    // убирается только когда сам маркер реально покидает сцену (не на каждый кадр).
    val nodeToMarker = remember { mutableMapOf<Node, VisibleMarker>() }

    // Небольшой пул вместо одного потока: Filament не потокобезопасен (нужна сериализация),
    // но одна "тяжёлая" модель не должна блокировать очередь остальных.
    val modelLoadDispatcher = remember { Dispatchers.IO.limitedParallelism(3) }

    ARSceneView(
        modifier = modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        viewNodeWindowManager = viewNodeWindowManager,
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
        depthMode = Config.DepthMode.AUTOMATIC,
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { _, node ->
                // .glb модели часто состоят из нескольких дочерних узлов — тап может попасть
                // в конкретную деталь геометрии, а не в корневой ModelNode, где мы регистрировали
                // маркер. Поднимаемся по родителям, пока не найдём зарегистрированный узел.
                var current: Node? = node
                var marker: VisibleMarker? = null
                while (current != null) {
                    marker = nodeToMarker[current]
                    if (marker != null) break
                    current = current.parent
                }
                if (marker != null) onMarkerClick(marker) else onBackgroundClick()
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

                // Собственный трекер прогресса скачивания (для плашки), независимый от того,
                // как модель грузит сам SceneView ниже (см. modelLoader.loadModelInstanceAsync).
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

                var markerNode by remember(visible.poi.id) { mutableStateOf<Node?>(null) }
                DisposableEffect(markerNode) {
                    val node = markerNode
                    if (node != null) nodeToMarker[node] = visible
                    onDispose { node?.let { nodeToMarker.remove(it) } }
                }

                val currentModelInstance = modelInstance
                if (currentModelInstance != null) {
                    // Размер модели зависит ТОЛЬКО от visible.scale (реальная дистанция / 1000 км,
                    // не зависит от текущего радиуса) — стабилен при движении слайдера.
                    ModelNode(
                        modelInstance = currentModelInstance,
                        scaleToUnits = MARKER_BASE_SIZE_METERS * visible.scale,
                        position = Position(visible.arXMeters, MARKER_HEIGHT_METERS, visible.arZMeters),
                        rotation = Rotation(x = visible.poi.rotationXDegrees.toFloat()),
                        apply = { markerNode = this }
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

                    // Полная 3D-дистанция ДО ПЛАШКИ (с учётом высоты подвеса), а не только
                    // горизонтальная — на близких точках высота даёт заметную ошибку без этого.
                    val badgeHeight = MARKER_HEIGHT_METERS + BADGE_VERTICAL_OFFSET_METERS
                    val badgeDistance3d = sqrt(
                        visible.arXMeters * visible.arXMeters +
                                badgeHeight * badgeHeight +
                                visible.arZMeters * visible.arZMeters
                    )
                    // Ограничение диапазона больше не нужно: позиция в сцене стабильна (не зависит
                    // от слайдера радиуса), значит и компенсация может работать без искусственных
                    // рамок — именно это и даёт истинно одинаковый видимый размер.
                    val compensation = badgeDistance3d / BADGE_REFERENCE_DISTANCE_METERS
                    val badgeScale = BADGE_BASE_SCALE * compensation

                    ViewNode(
                        windowManager = viewNodeWindowManager,
                        unlit = true,
                        position = Position(visible.arXMeters, badgeHeight, visible.arZMeters),
                        rotation = Rotation(y = badgeYawDegrees),
                        scale = Scale(badgeScale, badgeScale, badgeScale)
                    ) {
                        // ViewNode рендерит контент в отдельном окне — тема туда не долетает сама
                        // по себе, оборачиваем явно. Всегда тёмная — читается поверх любого видео.
                        ArWorldTheme(darkTheme = true) {
                            MarkerLoadingBadge(statusText = statusText)
                        }
                    }
                }

                // Луч от маркера вниз до поверхности, окрашен по категории — виден независимо
                // от того, готова модель или ещё показывается плашка загрузки.
                TubeNode(
                    points = listOf(
                        Position(visible.arXMeters, MARKER_HEIGHT_METERS, visible.arZMeters),
                        Position(visible.arXMeters, 0f, visible.arZMeters)
                    ),
                    radius = RAY_RADIUS_METERS,
                    materialInstance = rayMaterial
                )
            }
        }
    }
}