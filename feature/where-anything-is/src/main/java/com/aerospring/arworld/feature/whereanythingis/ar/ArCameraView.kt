package com.aerospring.arworld.feature.whereanythingis.ar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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

private const val MARKER_HEIGHT_METERS = 4f
private const val MARKER_RADIUS_METERS = 0.3f
private const val RAY_RADIUS_METERS = 0.02f
private const val BADGE_VERTICAL_OFFSET_METERS = 0.6f

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
                val color = visible.poi.category.toMarkerColor()
                val rayMaterial = remember(visible.poi.id) {
                    materialLoader.createUnlitColorInstance(color)
                }

                // Собственное отслеживание прогресса (для честной плашки "Загрузка N%") —
                // параллельно тому, как сам SceneView качает и кэширует модель ниже.
                val downloadState by produceState<GlbDownloadState>(
                    initialValue = GlbDownloadState.Progress(0),
                    key1 = visible.poi.modelUrl
                ) {
                    GlbDownloader.download(context, visible.poi.modelUrl).collect { value = it }
                }

                val modelInstance = rememberModelInstance(modelLoader, visible.poi.modelUrl)

                if (modelInstance != null) {
                    ModelNode(
                        modelInstance = modelInstance,
                        scale = Scale(visible.scale, visible.scale, visible.scale),
                        position = Position(visible.arXMeters, MARKER_HEIGHT_METERS, visible.arZMeters),
                        apply = { nodeToMarker[this] = visible }
                    )
                } else {
                    // Пока модель не готова — временная заглушка-цилиндр на её будущем месте.
                    val markerMaterial = remember(visible.poi.id) {
                        materialLoader.createUnlitColorInstance(color)
                    }
                    CylinderNode(
                        radius = MARKER_RADIUS_METERS * visible.scale,
                        height = MARKER_RADIUS_METERS * 2 * visible.scale,
                        position = Position(visible.arXMeters, MARKER_HEIGHT_METERS, visible.arZMeters),
                        materialInstance = markerMaterial,
                        apply = { nodeToMarker[this] = visible }
                    )

                    val percent = (downloadState as? GlbDownloadState.Progress)?.percent ?: 0
                    ViewNode(
                        windowManager = viewNodeWindowManager,
                        unlit = true,
                        position = Position(
                            visible.arXMeters,
                            MARKER_HEIGHT_METERS + BADGE_VERTICAL_OFFSET_METERS,
                            visible.arZMeters
                        )
                    ) {
                        MarkerLoadingBadge(title = visible.poi.title, percent = percent)
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

/** Временная заглушка цвета по id категории — на следующем шаге свяжем с Category.colorHex. */
private fun String.toMarkerColor(): Color = when (this) {
    "auto" -> Color(0xFFFF6B6B)
    "it" -> Color(0xFF4D96FF)
    "beauty" -> Color(0xFFFF6FB5)
    "med" -> Color(0xFF3ED598)
    else -> Color(0xFFAAAAAA)
}