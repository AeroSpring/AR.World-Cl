package com.aerospring.arworld.feature.furniture.ar

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Camera
import io.github.sceneview.math.Position

/**
 * Проекция мировой точки в экранные координаты (пиксели) — тот же подход, что
 * уже проверен в ArCameraView.kt/ArBcSceneView.kt (Camera.getViewMatrix/
 * getProjectionMatrix + android.opengl.Matrix), вместо встроенного пикинга
 * SceneView, который уже дважды подводил в проекте.
 */
fun projectToScreen(
    camera: Camera,
    worldPosition: Position,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
): Offset? {
    val viewMatrix = FloatArray(16)
    val projMatrix = FloatArray(16)
    camera.getViewMatrix(viewMatrix, 0)
    camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

    val viewProjMatrix = FloatArray(16)
    Matrix.multiplyMM(viewProjMatrix, 0, projMatrix, 0, viewMatrix, 0)

    val worldPoint = floatArrayOf(worldPosition.x, worldPosition.y, worldPosition.z, 1f)
    val clipPoint = FloatArray(4)
    Matrix.multiplyMV(clipPoint, 0, viewProjMatrix, 0, worldPoint, 0)

    if (clipPoint[3] <= 0f) return null // точка позади камеры

    val ndcX = clipPoint[0] / clipPoint[3]
    val ndcY = clipPoint[1] / clipPoint[3]

    val screenX = (ndcX * 0.5f + 0.5f) * viewportWidthPx
    val screenY = (1f - (ndcY * 0.5f + 0.5f)) * viewportHeightPx

    return Offset(screenX, screenY)
}