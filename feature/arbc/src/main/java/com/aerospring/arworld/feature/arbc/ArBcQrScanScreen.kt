package com.aerospring.arworld.feature.arbc

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сканирование QR-кода AR.Визитки ИЗНУТРИ этого раздела — намеренно НЕ через системный
 * Android App Links (иначе приложение перехватывало бы вообще любой QR с похожим URL,
 * даже сторонний). Пользователь сам жмёт "открыть визитку" в разделе, наводит камеру —
 * только тогда распознаём код и переходим к сцене.
 *
 * QR кодирует полную ссылку https://autoknowledge.tech/v/{clientId} (та же, что открывает
 * /v/{clientId} в браузере для пользователей без приложения) — extractClientId() достаёт
 * clientId из её хвоста и проверяет, что это действительно наш домен/формат, а не случайный
 * посторонний QR-код.
 *
 * ВТОРОЙ ПУТЬ (добавлено): пользователю могли переслать QR как картинку (скриншот,
 * сообщение в мессенджере) — отсканировать такую картинку камерой "по экрану" неудобно
 * и не всегда получается. Кнопка "выбрать из галереи" в TopAppBar открывает системный
 * Photo Picker (ActivityResultContracts.PickVisualMedia) — он НЕ требует разрешения на
 * доступ к галерее (даёт доступ только к выбранному файлу), после чего тот же самый
 * ML Kit разово сканирует QR со статичного изображения (InputImage.fromFilePath) и
 * дальше идёт по той же validate-и-навигация логике (extractClientId), что и живая камера.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArBcQrScanScreen(
    onScanned: (clientId: String) -> Unit,
    onShowcaseClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hintText by remember { mutableStateOf("Наведите камеру на QR-код AR.Визитки") }
    // Обычный (не Compose-state) флаг — сканер может вызвать колбэк много раз подряд,
    // пока идёт переход на следующий экран; защищаемся от повторного onScanned().
    // Общий и для камеры, и для галереи — оба пути ведут к одному и тому же переходу.
    val alreadyScanned = remember { AtomicBoolean(false) }

    // Отдельный клиент ML Kit для разового скана статичной картинки — намеренно не
    // переиспользует тот, что живёт внутри buildScannerPreviewView (там свой, привязанный
    // к жизненному циклу камеры); так изменение гарантированно не трогает рабочий live-скан.
    val staticImageScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // пользователь отменил выбор
        if (alreadyScanned.get()) return@rememberLauncherForActivityResult

        hintText = "Ищем QR-код на изображении…"
        try {
            val image = InputImage.fromFilePath(context, uri)
            staticImageScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val clientId = barcodes.firstNotNullOfOrNull { it.rawValue?.let(::extractClientId) }
                    if (clientId != null && alreadyScanned.compareAndSet(false, true)) {
                        onScanned(clientId)
                    } else if (clientId == null) {
                        hintText = "На изображении нет QR-кода AR.Визитки — выберите другое"
                    }
                }
                .addOnFailureListener {
                    hintText = "Не удалось прочитать изображение — попробуйте другое"
                }
        } catch (e: Exception) {
            hintText = "Не удалось открыть изображение — попробуйте другое"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сканировать AR.Визитку") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShowcaseClick) {
                        Icon(
                            imageVector = Icons.Filled.Storefront,
                            contentDescription = "Витрина активных AR.Визиток"
                        )
                    }
                    IconButton(onClick = {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Photo,
                            contentDescription = "Выбрать QR-код из галереи"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    buildScannerPreviewView(ctx, lifecycleOwner) { rawValue ->
                        if (alreadyScanned.get()) return@buildScannerPreviewView
                        val clientId = extractClientId(rawValue)
                        if (clientId != null) {
                            if (alreadyScanned.compareAndSet(false, true)) {
                                onScanned(clientId)
                            }
                        } else {
                            hintText = "Это не QR-код AR.Визитки — наведите на правильный"
                        }
                    }
                }
            )

            Text(
                text = hintText,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(12.dp)
            )
        }
    }
}

/** Собирает камеру + ML Kit; создаётся один раз в AndroidView-factory, не в теле Composable,
 *  чтобы не пересобирать камеру на каждой рекомпозиции. КРИТИЧНО: controller.bindToLifecycle()
 *  обязателен — без него LifecycleCameraController создан, но никогда не запускает камеру,
 *  и PreviewView остаётся чёрным (именно это и было причиной предыдущего бага). */
private fun buildScannerPreviewView(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    onBarcodeDetected: (rawValue: String) -> Unit
): PreviewView {
    val previewView = PreviewView(context)

    val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    val controller = LifecycleCameraController(context)
    controller.setImageAnalysisAnalyzer(
        ContextCompat.getMainExecutor(context),
        MlKitAnalyzer(
            listOf(barcodeScanner),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            ContextCompat.getMainExecutor(context)
        ) { result ->
            val barcodes = result?.getValue(barcodeScanner) ?: return@MlKitAnalyzer
            for (barcode in barcodes) {
                barcode.rawValue?.let(onBarcodeDetected)
            }
        }
    )

    controller.bindToLifecycle(lifecycleOwner)
    previewView.controller = controller
    return previewView
}

/** Возвращает clientId только для наших ссылок вида https://autoknowledge.tech/v/{clientId};
 *  для любого другого QR (посторонний сайт, товар, что угодно) возвращает null — сканер
 *  тогда просто просит навести на правильный код, вместо того чтобы на что попало реагировать. */
private fun extractClientId(rawValue: String): String? {
    val uri = Uri.parse(rawValue) ?: return null
    if (uri.host != "autoknowledge.tech") return null
    val segments = uri.pathSegments ?: return null
    if (segments.size != 2 || segments[0] != "v") return null
    return segments[1].takeIf { it.isNotBlank() }
}