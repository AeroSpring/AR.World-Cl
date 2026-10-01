package com.aerospring.arworld.feature.furniture.ar

import io.github.sceneview.math.Position

/**
 * Одна поставленная в сцену модель мебели.
 * footprintRadius — упрощённая проверка пересечений: КРУГ вместо точного
 * прямоугольника bounding box'а по осям X/Z. Это заведомо консервативнее
 * (иногда не даст поставить вплотную то, что на самом деле не пересеклось бы) —
 * сознательный компромисс для MVP, чтобы не городить полноценную проверку
 * пересечения повёрнутых прямоугольников (OBB) сразу. Если на практике это
 * будет слишком мешать (мебель часто прямоугольная, не квадратная) — заменим
 * на честный OBB-тест отдельным шагом.
 */
data class PlacedModel(
    val instanceId: String, // уникален на инстанс в сцене, НЕ совпадает с modelId каталога (моделей одного вида может быть несколько)
    val modelId: String,
    val modelName: String,
    val modelUrl: String,
    var position: Position,
    var rotationYDegrees: Float,
    val footprintRadius: Float,
)

/** Пересекаются ли две поставленные модели (по кругам в плоскости XZ). */
fun PlacedModel.overlapsWith(other: PlacedModel): Boolean {
    val dx = position.x - other.position.x
    val dz = position.z - other.position.z
    val distance = kotlin.math.sqrt(dx * dx + dz * dz)
    return distance < (footprintRadius + other.footprintRadius)
}