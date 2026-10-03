package com.aerospring.arworld.feature.furniture.ar

import io.github.sceneview.math.Position

/** На каком типе поверхности стоит модель — влияет на то, какая ось доступна
 *  для поворота жестом (вертикальная Y для пола, нормаль стены для стены)
 *  и на ориентацию кольца выделения. */
enum class SurfaceType { FLOOR, WALL }

/**
 * Одна поставленная в сцену модель мебели.
 *
 * rotationYDegrees — для FLOOR это разворот вокруг вертикальной оси, который
 * крутит пользователь жестом; для WALL это ФИКСИРОВАННЫЙ разворот "лицом от
 * стены", вычисленный один раз при постановке по нормали стены — пользователь
 * его не трогает напрямую.
 *
 * tiltDegrees — для WALL это и есть то, что крутит пользователь жестом: наклон
 * вокруг нормали стены (как картина "по диагонали"), не вокруг вертикали.
 * Для FLOOR всегда 0 и не используется.
 *
 * footprintRadius — упрощённая проверка пересечений кругом. Для FLOOR — круг
 * в плоскости пола (X/Z), для WALL — круг в плоскости стены (X/Y).
 */
data class PlacedModel(
    val instanceId: String,
    val modelId: String,
    val modelName: String,
    val modelUrl: String,
    var position: Position,
    val surfaceType: SurfaceType,
    var rotationYDegrees: Float,
    var tiltDegrees: Float,
    val footprintRadius: Float,
)

/** Пересекаются ли две поставленные модели — честное 3D-расстояние между точками. */
fun PlacedModel.overlapsWith(other: PlacedModel): Boolean {
    val dx = position.x - other.position.x
    val dy = position.y - other.position.y
    val dz = position.z - other.position.z
    val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    return distance < (footprintRadius + other.footprintRadius)
}