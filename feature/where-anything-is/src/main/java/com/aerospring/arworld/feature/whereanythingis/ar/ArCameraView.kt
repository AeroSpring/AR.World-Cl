package com.aerospring.arworld.feature.whereanythingis.ar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView

/**
 * Живой фид камеры ARCore с трекингом плоскостей и позиции.
 * На следующих шагах сюда добавим узлы-маркеры (POI) и слушатель кадров
 * для расчёта дистанций/азимутов в реальном времени.
 */
@Composable
fun ArCameraView(
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            ARSceneView(context).apply {
                configureSession { session, config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                }
            }
        }
    )
}