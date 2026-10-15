package com.aerospring.arworld.feature.facades.stand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * «Снять эталон» прямо у фасада.
 *
 * Берём кадр камеры ARCore (тот же, по которому ARCore ищет изображения), вырезаем
 * квадрат-рамку из центра экрана, переводим в оттенки серого (цвет ARCore не использует)
 * и оцениваем физическую ширину: расстояние до фасада × (ширина в пикселях / фокусное расстояние).
 *
 * Расстояние, по убыванию точности:
 *  1. плоскость уже распознанного эталона (луч из центра экрана до неё);
 *  2. вертикальная плоскость ARCore под перекрестием;
 *  3. карта глубины в центре кадра.
 * Предполагается, что телефон смотрит на фасад примерно прямо.
 */

/** Доля ширины экрана, которую занимает рамка съёмки эталона (квадрат по центру). */
internal const val CAPTURE_GUIDE_FRACTION = 0.7f

internal class CapturedReference(
    val name: String,
    val bitmap: Bitmap,
    val widthMeters: Float,
    val distanceMeters: Float,
    val distanceSource: String
)

internal sealed interface CaptureResult {
    class Ok(val reference: CapturedReference) : CaptureResult
    class Failed(val reason: String) : CaptureResult
}

internal fun captureReference(
    frame: Frame,
    viewW: Int,
    viewH: Int,
    trackedImages: List<AugmentedImage>,
    name: String
): CaptureResult {
    if (viewW <= 0 || viewH <= 0) return CaptureResult.Failed("экран ещё не готов")

    // 1) Расстояние до фасада.
    val (distance, source) = distanceToFacade(frame, viewW, viewH, trackedImages)
        ?: return CaptureResult.Failed("нет расстояния до фасада — поводи камерой, чтобы появилась глубина или стена")

    // 2) Рамка на экране → координаты в кадре камеры.
    val side = viewW * CAPTURE_GUIDE_FRACTION
    val cx = viewW / 2f
    val cy = viewH / 2f
    val viewPts = floatArrayOf(
        cx - side / 2, cy - side / 2, // левый верх
        cx + side / 2, cy - side / 2, // правый верх
        cx + side / 2, cy + side / 2, // правый низ
        cx - side / 2, cy + side / 2, // левый низ
        cx - side / 2, cy,            // середина левой стороны
        cx + side / 2, cy             // середина правой стороны
    )
    val norm = FloatArray(viewPts.size)
    try {
        frame.transformCoordinates2d(Coordinates2d.VIEW, viewPts, Coordinates2d.IMAGE_NORMALIZED, norm)
    } catch (e: Exception) {
        return CaptureResult.Failed("не удалось пересчитать рамку (${e.javaClass.simpleName})")
    }

    val image = try {
        frame.acquireCameraImage()
    } catch (e: Exception) {
        return CaptureResult.Failed("кадр камеры недоступен (${e.javaClass.simpleName}), попробуй ещё раз")
    }
    val bitmap: Bitmap
    val widthPx: Float
    try {
        val w = image.width
        val h = image.height
        val px = FloatArray(norm.size) { i -> if (i % 2 == 0) norm[i] * w else norm[i] * h }
        val xs = listOf(px[0], px[2], px[4], px[6])
        val ys = listOf(px[1], px[3], px[5], px[7])
        val left = xs.min().roundToInt().coerceIn(0, w - 1)
        val right = xs.max().roundToInt().coerceIn(left + 1, w)
        val top = ys.min().roundToInt().coerceIn(0, h - 1)
        val bottom = ys.max().roundToInt().coerceIn(top + 1, h)
        widthPx = hypot(px[10] - px[8], px[11] - px[9])

        // Яркость (плоскость Y) → серая картинка.
        val yPlane = image.planes[0]
        val buf = yPlane.buffer.order(ByteOrder.nativeOrder())
        val cw = right - left
        val ch = bottom - top
        val pixels = IntArray(cw * ch)
        for (y in 0 until ch) {
            val rowStart = (top + y) * yPlane.rowStride
            for (x in 0 until cw) {
                val v = buf.get(rowStart + (left + x) * yPlane.pixelStride).toInt() and 0xFF
                pixels[y * cw + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val raw = Bitmap.createBitmap(pixels, cw, ch, Bitmap.Config.ARGB_8888)

        // Поворот, чтобы «вправо на экране» стало «вправо на картинке».
        val angle = Math.toDegrees(atan2((px[11] - px[9]).toDouble(), (px[10] - px[8]).toDouble()))
        val quarter = (Math.round(angle / 90.0) * 90).toFloat()
        bitmap = if (quarter == 0f) raw else Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(-quarter) }, true
        )
    } catch (e: Exception) {
        return CaptureResult.Failed("ошибка обработки кадра (${e.javaClass.simpleName})")
    } finally {
        image.close()
    }

    val fx = try {
        frame.camera.imageIntrinsics.focalLength[0]
    } catch (e: Exception) {
        return CaptureResult.Failed("нет параметров камеры (${e.javaClass.simpleName})")
    }
    val widthMeters = distance * widthPx / fx
    if (widthMeters !in 0.3f..60f) {
        return CaptureResult.Failed("странная ширина %.1f м (расстояние %.1f м, %s) — попробуй ещё раз".format(widthMeters, distance, source))
    }
    return CaptureResult.Ok(CapturedReference(name, bitmap, widthMeters, distance, source))
}

private fun distanceToFacade(
    frame: Frame,
    viewW: Int,
    viewH: Int,
    trackedImages: List<AugmentedImage>
): Pair<Float, String>? {
    val camPose = frame.camera.pose
    val o = camPose.translation
    val d = camPose.zAxis.let { z -> floatArrayOf(-z[0], -z[1], -z[2]) } // камера смотрит вдоль −Z

    // 1) Плоскость распознанного эталона.
    trackedImages.firstOrNull { it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING }?.let { img ->
        val p = img.centerPose.translation
        val n = img.centerPose.yAxis
        val denom = n[0] * d[0] + n[1] * d[1] + n[2] * d[2]
        if (kotlin.math.abs(denom) > 1e-3f) {
            val t = (n[0] * (p[0] - o[0]) + n[1] * (p[1] - o[1]) + n[2] * (p[2] - o[2])) / denom
            if (t > 0.3f) return t to "плоскость эталона «${img.name}»"
        }
    }

    // 2) Вертикальная плоскость ARCore под перекрестием.
    try {
        frame.hitTest(viewW / 2f, viewH / 2f).firstOrNull { r ->
            val t = r.trackable
            t is Plane && t.type == Plane.Type.VERTICAL && t.isPoseInPolygon(r.hitPose)
        }?.let { return it.distance to "стена ARCore" }
    } catch (_: Exception) {
    }

    // 3) Карта глубины в центре (среднее 5×5).
    try {
        frame.acquireDepthImage16Bits().use { img ->
            val plane = img.planes[0]
            val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val cx = img.width / 2
            val cy = img.height / 2
            var sum = 0L
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val x = (cx + dx).coerceIn(0, img.width - 1)
                val y = (cy + dy).coerceIn(0, img.height - 1)
                val mm = buf.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFFFF
                if (mm > 0) { sum += mm; n++ }
            }
            if (n > 0) return (sum / n / 1000f) to "карта глубины"
        }
    } catch (_: Exception) {
    }
    return null
}

// ── Хранение снятых эталонов на телефоне ──────────────────────────────────────────────

@Serializable
private data class CapturedManifest(val images: List<CapturedManifestImage> = emptyList())

@Serializable
private data class CapturedManifestImage(
    val name: String,
    val file: String,
    val widthMeters: Float,
    val distanceMeters: Float = 0f,
    val distanceSource: String = ""
)

/**
 * Снятые эталоны живут на телефоне и подгружаются при каждом входе в стенд вместе с серверными.
 * Копия (PNG + строка json) уходит в «Загрузки/AR.World/refs» — чтобы переложить на сервер.
 */
internal object CapturedReferenceStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private const val MANIFEST = "captured.json"

    private fun dir(context: Context) = File(context.filesDir, "facades-stand-captured").apply { mkdirs() }

    private fun readManifest(context: Context): CapturedManifest = try {
        val f = File(dir(context), MANIFEST)
        if (f.exists()) json.decodeFromString(CapturedManifest.serializer(), f.readText(Charsets.UTF_8)) else CapturedManifest()
    } catch (e: Exception) {
        CapturedManifest()
    }

    fun loadAll(context: Context): List<StandReference> {
        val d = dir(context)
        return readManifest(context).images.mapNotNull { img ->
            BitmapFactory.decodeFile(File(d, img.file).absolutePath)?.let { StandReference(img.name, it, img.widthMeters) }
        }
    }

    /** Сохраняет на телефоне и копию в «Загрузки». Возвращает путь копии или null. */
    fun save(context: Context, ref: CapturedReference): String? {
        val d = dir(context)
        val fileName = "${ref.name}.png"
        val bytes = ByteArrayOutputStream().use { out ->
            ref.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        File(d, fileName).writeBytes(bytes)
        val entry = CapturedManifestImage(ref.name, fileName, ref.widthMeters, ref.distanceMeters, ref.distanceSource)
        val manifest = readManifest(context)
        File(d, MANIFEST).writeText(
            json.encodeToString(CapturedManifest.serializer(), manifest.copy(images = manifest.images + entry)),
            Charsets.UTF_8
        )
        val sub = "$STAND_DOWNLOADS_SUBDIR/refs"
        val path = saveBytesToDownloads(context, sub, fileName, "image/png", bytes)
        saveBytesToDownloads(
            context, sub, "${ref.name}.json", "application/json",
            json.encodeToString(CapturedManifestImage.serializer(), entry).toByteArray(Charsets.UTF_8)
        )
        return path
    }

    fun clear(context: Context): Int {
        val d = dir(context)
        val count = readManifest(context).images.size
        d.listFiles()?.forEach { it.delete() }
        return count
    }
}
