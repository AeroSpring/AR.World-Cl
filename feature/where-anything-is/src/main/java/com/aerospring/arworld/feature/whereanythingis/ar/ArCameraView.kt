package com.aerospring.arworld.feature.whereanythingis.ar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.math.sqrt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.aerospring.arworld.feature.whereanythingis.model.GlbDownloadState
import com.aerospring.arworld.feature.whereanythingis.model.GlbDownloader
import com.aerospring.arworld.feature.whereanythingis.ui.MarkerLoadingBadge
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.TubeNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.math.Rotation
import com.aerospring.arworld.feature.whereanythingis.ui.categoryColor
import com.aerospring.arworld.core.ui.theme.ArWorldTheme

private const val MARKER_HEIGHT_METERS = 4f
private const val MARKER_RADIUS_METERS = 0.3f
private const val RAY_RADIUS_METERS = 0.02f
private const val BADGE_VERTICAL_OFFSET_METERS = 0.6f
/** Опорный "нормальный" размер маркера в метрах — все .glb вписываются в эту высоту, независимо от их родного масштаба в файле. */
private const val MARKER_BASE_SIZE_METERS = 3.0f

/**
 * Живой фид камеры ARCore + реальные анимированные .glb модели (ModelNode) на месте POI,
 * с плашкой прогресса загрузки (ViewNode) пока модель ещё не готова, и лучом до земли.
 */
@Composable
fun ArCameraView(
    visibleMarkers: List<VisibleMarker>,
    onMarkerClick: (VisibleMarker) -> Unit,
    onSessionCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val viewNodeWindowManager = rememberViewNodeManager()

    val nodeToMarker = remember { mutableMapOf<Node, VisibleMarker>() }

    // Все загрузки .glb идут через ОДИН поток: Filament не потокобезопасен, а параллельные
    // вызовы modelLoader из разных потоков пула Dispatchers.IO вызывали гонки — отсюда
    // терялись модели и случались крэши при частом переключении категорий...
    // Небольшой пул вместо одного потока — избегаем гонок в Filament (не потокобезопасен),
    // но не даём одной "тяжёлой" модели блокировать очередь остальных.
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
                nodeToMarker[node]?.let(onMarkerClick)
            }
        ),
        onSessionCreated = { onSessionCreated() }
    ) {
        nodeToMarker.clear()

        visibleMarkers.forEach { visible ->
            key(visible.poi.id) {
                val color = categoryColor(visible.poi.category)
                val rayMaterial = remember(visible.poi.id) {
                    materialLoader.createUnlitColorInstance(color)
                }

                // Единственная загрузка: наш GlbDownloader качает файл на диск с прогрессом.
                val downloadState by produceState<GlbDownloadState>(
                    initialValue = GlbDownloadState.Progress(0),
                    key1 = visible.poi.modelUrl
                ) {
                    GlbDownloader.download(context, visible.poi.modelUrl).collect { value = it }
                }
                // Явная загрузка ModelInstance напрямую по https-URL (документированный,
                // проверенный путь) — с колбэком, чтобы видеть реальный успех/ошибку,
                // а не тихое зависание, как было у rememberModelInstance для этого случая.
                var modelInstance by remember(visible.poi.id) {
                    mutableStateOf<io.github.sceneview.model.ModelInstance?>(null)
                }
                var modelLoadError by remember(visible.poi.id) { mutableStateOf<String?>(null) }

                androidx.compose.runtime.LaunchedEffect(visible.poi.id) {
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
                        modelLoadError = "Таймаут загрузки модели (45с) — файл слишком большой или сервер отвечает медленно"
                    } catch (e: Exception) {
                        modelLoadError = e.message ?: "Ошибка загрузки 3D-модели"
                    }
                }

                val currentModelInstance = modelInstance
                if (currentModelInstance != null) {
                    ModelNode(
                        modelInstance = currentModelInstance,
                        scaleToUnits = MARKER_BASE_SIZE_METERS * visible.scale,
                        position = Position(visible.arXMeters, MARKER_HEIGHT_METERS, visible.arZMeters),
                        apply = { nodeToMarker[this] = visible }
                    )
                } else {
                    val statusText = modelLoadError?.let { "Ошибка модели: $it" }
                        ?: when (val state = downloadState) {
                            is GlbDownloadState.Progress -> if (state.isMegabytes) {
                                "Загрузка: ${state.percent} МБ"
                            } else {
                                "Загрузка ${state.percent}%"
                            }
                            is GlbDownloadState.Done -> "Обработка модели..."
                            is GlbDownloadState.Error -> "Ошибка: ${state.message}"
                        }
                    // Разворот плашки "лицом" в сторону начала координат AR-сцены (примерно
                    // туда, где стоял пользователь при калибровке) — статический расчёт вместо
                    // billboard-обёртки, которой нет в этой версии библиотеки для произвольного контента.
                    val badgeYawDegrees = Math.toDegrees(
                        kotlin.math.atan2(-visible.arXMeters.toDouble(), -visible.arZMeters.toDouble())
                    ).toFloat()

                    // Компенсация перспективы: плашка — обычный объект AR-сцены, значит
                    // дальний маркер выглядел бы мельче. Увеличиваем её физически пропорционально
                    // AR-дистанции, чтобы видимый размер на экране был одинаковым для всех.
                    val arDistance = sqrt(visible.arXMeters * visible.arXMeters + visible.arZMeters * visible.arZMeters)
                    val badgeScale = 0.9f * (arDistance / AR_MIN_DISPLAY_DISTANCE_METERS)

                    ViewNode(
                        windowManager = viewNodeWindowManager,
                        unlit = true,
                        position = Position(
                            visible.arXMeters,
                            MARKER_HEIGHT_METERS + BADGE_VERTICAL_OFFSET_METERS,
                            visible.arZMeters
                        ),
                        rotation = Rotation(y = badgeYawDegrees),
                        scale = io.github.sceneview.math.Scale(badgeScale, badgeScale, badgeScale)
                    ) {
                        // ViewNode рендерит контент в отдельном окне — наша тема туда не долетает
                        // сама по себе, оборачиваем явно. Оверлей поверх камеры всегда тёмный —
                        // так он лучше читается на живом видео независимо от системной темы устройства.
                        ArWorldTheme(darkTheme = true) {
                            MarkerLoadingBadge(statusText = statusText)
                        }
                    }
                }

                // Луч от маркера вниз до поверхности — виден независимо от готовности модели.
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
