package com.aerospring.arworld.feature.facades.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerospring.arworld.feature.facades.data.FacadeCalibration
import com.aerospring.arworld.feature.facades.data.FacadeScene
import com.aerospring.arworld.feature.facades.stand.CAPTURE_GUIDE_FRACTION
import com.google.ar.core.Pose
import java.util.Locale

/** Правило безопасности: перед любой подсказкой переместиться — сначала осмотреться. */
internal const val SAFETY_LOOK_AROUND = "Осмотритесь: бордюры, дорога и автомобили, другие прохожие."

/**
 * Где центр нового снимка стоит в координатах фасада.
 *
 * Сцена держится на якоре [origin] — начале координат фасада (без калибровки). Узнанный эталон
 * [recognized] лежит в плоскости фасада; центр нового снимка — точка, где луч из центра экрана
 * пересекает эту плоскость, с той же ориентацией изображения. Из формулы якоря
 * (начало = центр эталона · оси · поза эталона⁻¹) поза эталона = начало⁻¹ · центр · оси.
 * null — луч не попадает в плоскость (смотрим вдоль фасада).
 */
internal fun newReferencePose(origin: Pose, recognized: Pose, camera: Pose): Pose? {
    val o = camera.translation
    val z = camera.zAxis
    val d = floatArrayOf(-z[0], -z[1], -z[2])           // камера смотрит вдоль −Z
    val p = recognized.translation
    val n = recognized.yAxis                             // нормаль плоскости изображения
    val denom = n[0] * d[0] + n[1] * d[1] + n[2] * d[2]
    if (kotlin.math.abs(denom) < 1e-3f) return null
    val t = (n[0] * (p[0] - o[0]) + n[1] * (p[1] - o[1]) + n[2] * (p[2] - o[2])) / denom
    if (t <= 0.3f) return null
    val hit = floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t)
    val center = Pose(hit, recognized.rotationQuaternion)
    val axes = Pose.makeRotation(-0.70710677f, 0f, 0f, 0.70710677f)
    return origin.inverse().compose(center).compose(axes)
}

/** Освещение по часам у фасада — как подсказка по умолчанию. */
internal fun lightingByHour(hour: Int): String = when (hour) {
    in 7..16 -> "day"
    in 17..20 -> "evening"
    else -> "night"
}

private val LIGHTINGS = listOf("day" to "День", "overcast" to "Пасмурно", "evening" to "Вечер", "night" to "Ночь")

/** Рамка съёмки — тот же квадрат по центру, что вырезается из кадра. */
@Composable
internal fun CaptureGuide() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth(CAPTURE_GUIDE_FRACTION).aspectRatio(1f)
                .border(2.dp, Color(0xCCFFEB3B), RoundedCornerShape(4.dp))
        )
    }
}

/** Первое открытие панели монтажа — напоминание о безопасности. */
@Composable
internal fun InstallSafetyDialog(onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text("Перед работой") },
        text = { Text("$SAFETY_LOOK_AROUND Снимайте и настраивайте стоя на месте, не на ходу. На проезжую часть не выходите.") },
        confirmButton = { Button(onClick = onOk) { Text("Понятно") } }
    )
}

/** Панель «Монтаж» внизу AR-экрана. */
@Composable
internal fun InstallBar(
    recognized: Boolean,
    mode: InstallMode,
    scenes: List<FacadeScene>,
    sceneOverride: String?,
    onMode: (InstallMode) -> Unit,
    onScene: (String?) -> Unit,
    onShoot: () -> Unit
) {
    Column(
        Modifier.widthIn(max = 460.dp).fillMaxWidth().background(Color(0xD9101418), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Монтаж", color = Color(0xFFFFEB3B), fontSize = 13.sp)
        // Выбор сцены: в предпросмотре видны и черновики.
        if (scenes.size > 1 || scenes.any { it.status != "active" }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = sceneOverride == null, onClick = { onScene(null) }, label = { Text("По расписанию") })
                scenes.forEach { s ->
                    FilterChip(
                        selected = sceneOverride == s.id, onClick = { onScene(s.id) },
                        label = { Text(s.title + if (s.status != "active") " (черновик)" else "") }
                    )
                }
            }
        }
        when (mode) {
            InstallMode.NONE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onMode(InstallMode.CAPTURE) }) { Text("Снять эталон") }
                FilledTonalButton(onClick = { onMode(InstallMode.CALIBRATE) }) { Text("Калибровка") }
            }
            InstallMode.CAPTURE -> {
                Text(
                    if (recognized) "Наведите жёлтую рамку на новое место фасада с крупными деталями (вывеска, витраж) и снимите."
                    else "Сначала фасад должен быть узнан по уже известному месту. $SAFETY_LOOK_AROUND Затем, если безопасно, встаньте так, чтобы это место было видно целиком.",
                    color = Color.White, fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onShoot, enabled = recognized) { Text("Снять") }
                    OutlinedButton(onClick = { onMode(InstallMode.NONE) }) { Text("Закрыть", color = Color.White) }
                }
            }
            InstallMode.CALIBRATE -> Unit   // своя панель — CalibrationPanel
        }
    }
}

internal enum class InstallMode { NONE, CAPTURE, CALIBRATE }

/**
 * Доводка всей сцены: сдвиг в координатах фасада (вправо, вверх, к улице), поворот вокруг
 * вертикали, масштаб. Модели двигаются сразу; «Сохранить» отправляет на сервер.
 */
@Composable
internal fun CalibrationPanel(
    calibration: FacadeCalibration,
    saving: Boolean,
    message: String?,
    onChange: (FacadeCalibration) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var step by remember { mutableStateOf(0.05f) }
    val p = calibration.position.let { if (it.size == 3) it else listOf(0f, 0f, 0f) }
    fun move(dx: Float, dy: Float, dz: Float) =
        onChange(calibration.copy(position = listOf(p[0] + dx, p[1] + dy, p[2] + dz)))

    Column(
        Modifier.widthIn(max = 460.dp).fillMaxWidth().background(Color(0xD9101418), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Калибровка сцены", color = Color(0xFFFFEB3B), fontSize = 13.sp)
        Text(
            String.format(Locale.forLanguageTag("ru"), "Сдвиг %.2f / %.2f / %.2f м · поворот %.1f° · масштаб %.2f",
                p[0], p[1], p[2], calibration.rotationY, calibration.scale),
            color = Color.White, fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Шаг:", color = Color.White, fontSize = 12.sp)
            listOf(0.01f to "1 см", 0.05f to "5 см", 0.10f to "10 см").forEach { (v, t) ->
                FilterChip(selected = step == v, onClick = { step = v }, label = { Text(t) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Small("← Влево") { move(-step, 0f, 0f) }
            Small("Вправо →") { move(step, 0f, 0f) }
            Small("↑ Вверх") { move(0f, step, 0f) }
            Small("↓ Вниз") { move(0f, -step, 0f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Small("К стене") { move(0f, 0f, -step) }
            Small("От стены") { move(0f, 0f, step) }
            Small("⟲ 1°") { onChange(calibration.copy(rotationY = calibration.rotationY + 1f)) }
            Small("⟳ 1°") { onChange(calibration.copy(rotationY = calibration.rotationY - 1f)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Small("Меньше 1%") { onChange(calibration.copy(scale = (calibration.scale * 0.99f).coerceAtLeast(0.51f))) }
            Small("Больше 1%") { onChange(calibration.copy(scale = (calibration.scale * 1.01f).coerceAtMost(1.99f))) }
        }
        message?.let { Text(it, color = Color.White, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !saving) { Text(if (saving) "Сохраняем…" else "Сохранить") }
            OutlinedButton(onClick = onCancel, enabled = !saving) { Text("Отменить", color = Color.White) }
        }
    }
}

@Composable
private fun Small(text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, fontSize = 12.sp)
    }
}

/** Снимок готов: показать, выбрать освещение, отправить. */
@Composable
internal fun CaptureConfirmDialog(
    bitmap: Bitmap,
    widthM: Float,
    distanceM: Float,
    defaultLighting: String,
    sending: Boolean,
    error: String?,
    onSend: (lighting: String) -> Unit,
    onCancel: () -> Unit
) {
    var lighting by remember { mutableStateOf(defaultLighting) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    AlertDialog(
        onDismissRequest = { if (!sending) onCancel() },
        title = { Text("Новый эталон") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(image, contentDescription = "Снимок", modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                Text(String.format(Locale.forLanguageTag("ru"), "Ширина места на фасаде: %.2f м · снято с %.1f м", widthM, distanceM))
                Text("Освещение сейчас:")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LIGHTINGS.forEach { (v, t) -> FilterChip(selected = lighting == v, onClick = { lighting = v }, label = { Text(t) }) }
                }
                error?.let { Text(it, color = Color(0xFFD32F2F)) }
            }
        },
        confirmButton = { Button(onClick = { onSend(lighting) }, enabled = !sending) { Text(if (sending) "Отправляем…" else "Отправить") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !sending) { Text("Отмена") } }
    )
}
