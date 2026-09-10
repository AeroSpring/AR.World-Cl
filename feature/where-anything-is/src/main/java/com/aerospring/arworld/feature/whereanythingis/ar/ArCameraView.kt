package com.aerospring.arworld.feature.whereanythingis.ar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.math.Position
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.TubeNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

private const val MARKER_HEIGHT_METERS = 4f
private const val MARKER_RADIUS_METERS = 0.3f
private const val RAY_RADIUS_METERS = 0.02f

/**
 * Живой фид камеры ARCore (актуальный Compose-native SceneView 4.x) + маркеры POI
 * (пока — простые цилиндры-заглушки, позже заменим на анимированные .glb) с лучом
 * до земли, окрашенным в цвет категории.
 */
@Composable
fun ArCameraView(
    visibleMarkers: List<VisibleMarker>,
    onMarkerClick: (VisibleMarker) -> Unit,
    onSessionCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Связь узел -> маркер, наполняется при композиции узлов ниже,
    // используется в onSingleTapConfirmed для определения, по какому POI кликнули.
    val nodeToMarker = remember { mutableMapOf<Node, VisibleMarker>() }

    ARSceneView(
        modifier = modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
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
                val markerMaterial = remember(visible.poi.id) {
                    materialLoader.createUnlitColorInstance(color)
                }
                val rayMaterial = remember(visible.poi.id) {
                    materialLoader.createUnlitColorInstance(color)
                }

                CylinderNode(
                    radius = MARKER_RADIUS_METERS * visible.scale,
                    height = MARKER_RADIUS_METERS * 2 * visible.scale,
                    position = Position(visible.arXMeters, MARKER_HEIGHT_METERS, visible.arZMeters),
                    materialInstance = markerMaterial,
                    apply = { nodeToMarker[this] = visible }
                )

                // Тонкий, но видимый (не 1px) луч от маркера вниз до поверхности —
                // помогает найти маркер на местности независимо от масштаба.
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