package com.aerospring.arworld.feature.facades.stand

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.VpsAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.recording.ARRecorder
import io.github.sceneview.ar.recording.rememberARRecorder
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "FacadesStand"

/** Каждый какой кадр обновлять строки замеров (как в диагностике Мебели). */
private const val PROBE_EVERY_N_FRAMES = 10

/** Как часто дублировать все строки в logcat (для файла logcat на обмен). */
private const val LOGCAT_EVERY_MS = 2_000L

/** Тестовый куб: сторона и расстояние перед камерой. */
private const val TEST_CUBE_SIDE_M = 0.5f
private const val TEST_CUBE_DISTANCE_M = 3f

/** Копирование записей в «Загрузки» переживает уход со стенда. */
private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())

/**
 * Диагностический стенд AR.Фасадов (шаг 1 плана).
 *
 * Без функций раздела: только замеры, по которым выбирается архитектура.
 * Воспроизведение записи пересоздаёт AR-сессию целиком (так требует ARCore),
 * поэтому сцена обёрнута в key(playbackUri).
 */
@Composable
fun FacadesStandScreen(locationGranted: Boolean, onExit: () -> Unit) {
    // Эталонные снимки фасада грузятся с сервера ДО создания AR-сессии:
    // база изображений ARCore задаётся в конфигурации сессии.
    val appContext = LocalContext.current.applicationContext
    var referencesResult by remember { mutableStateOf<StandReferencesResult?>(null) }
    LaunchedEffect(Unit) {
        referencesResult = withTimeoutOrNull(45_000) { StandReferenceRepository.load(appContext) }
            ?: StandReferencesResult.Failed("нет ответа сервера за 30 с")
    }
    val loaded = referencesResult
    if (loaded == null) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = Color.White)
            Text("Загружаю эталонные снимки фасада…", color = Color.White, modifier = Modifier.padding(top = 16.dp))
        }
        return
    }
    val references = (loaded as? StandReferencesResult.Loaded)?.references ?: emptyList()
    val referencesLoadText = when (loaded) {
        is StandReferencesResult.Loaded ->
            (if (loaded.fromCache) "Эталоны ИЗ КОПИИ на телефоне: ${loaded.references.size} · сервер недоступен: ${loaded.networkError}"
            else "Эталоны с сервера: ${loaded.references.size}") +
                if (loaded.failed.isEmpty()) "" else " · не загрузились: ${loaded.failed.joinToString()}"
        is StandReferencesResult.Failed -> "Эталоны с сервера: ОШИБКА (${loaded.reason})"
    }

    var playbackUri by remember { mutableStateOf<Uri?>(null) }
    val pickRecording = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playbackUri = uri
    }
    key(playbackUri) {
        StandScene(
            locationGranted = locationGranted,
            references = references,
            referencesLoadText = referencesLoadText,
            playbackUri = playbackUri,
            onPickRecording = { pickRecording.launch(arrayOf("video/mp4", "*/*")) },
            onBackToLive = { playbackUri = null },
            onExit = onExit
        )
    }
}

@Composable
private fun StandScene(
    locationGranted: Boolean,
    references: List<StandReference>,
    referencesLoadText: String,
    playbackUri: Uri?,
    onPickRecording: () -> Unit,
    onBackToLive: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val isPlayback = playbackUri != null

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val cameraStream = rememberARCameraStream(materialLoader)
    val recorder = rememberARRecorder()
    val cubeMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFFF9800))
    }
    // Полупрозрачная «пластина» поверх распознанного фрагмента фасада: видно, насколько точно
    // ARCore совместил эталон с реальным зданием.
    val plateMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0xFF00E5FF).copy(alpha = 0.35f))
    }
    // Ожидаемый размер каждой пластины (ширина из манифеста, высота по пропорциям снимка).
    val plateSizes = remember(references) {
        references.associate { r ->
            r.name to Size(r.widthMeters, 0.02f, r.widthMeters * r.bitmap.height / r.bitmap.width)
        }
    }

    // ── Что умеет устройство (заполняется один раз при создании сессии) ──
    var depthSupportText by remember { mutableStateOf("Глубина: проверяю…") }
    var semanticSupportText by remember { mutableStateOf("Семантика: проверяю…") }
    var geoSupportText by remember { mutableStateOf("Geospatial: проверяю…") }
    var imagesDbText by remember { mutableStateOf(referencesLoadText) }
    var sessionError by remember { mutableStateOf<String?>(null) }

    // ── Живые замеры ──
    var trackingLine by remember { mutableStateOf("Трекинг: запуск…") }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var depthLine by remember { mutableStateOf("") }
    var semanticLine by remember { mutableStateOf("") }
    var geoLine by remember { mutableStateOf("") }
    var imagesLine by remember { mutableStateOf("") }
    var vpsLine by remember { mutableStateOf(if (isPlayback) "VPS: не проверяется в записи" else "VPS: жду координаты") }

    // ── Переключатели ──
    var depthOcclusionOn by remember { mutableStateOf(false) }
    var personOcclusionOn by remember { mutableStateOf(false) }
    var cubePosition by remember { mutableStateOf<Position?>(null) }

    // ── Служебное, без перерисовки ──
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }
    val frameCounter = remember { IntArray(1) }
    val lastFrameTs = remember { LongArray(1) }
    val smoothFps = remember { FloatArray(1) }
    val lastLogcatMs = remember { LongArray(1) }
    val vpsRequested = remember { BooleanArray(1) }
    val lastCameraPose = remember { AtomicReference<Pose?>(null) }
    val cubeNodeHolder = remember { arrayOfNulls<Node>(1) }
    val sessionStartMs = remember { System.currentTimeMillis() }

    // Распознанные фрагменты фасада (ARCore отдаёт один и тот же трекабл, equals по нативному объекту).
    val trackedImages = remember { mutableStateListOf<AugmentedImage>() }
    val plateNodes = remember { mutableMapOf<String, Node>() }

    val journal = remember { mutableStateListOf<String>() }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000)
            message = null
        }
    }

    // Обход известной особенности SceneView: новый узел «досылается» в Filament
    // только после переприсваивания isVisible (см. learnings проекта).
    LaunchedEffect(cubePosition) {
        if (cubePosition != null) {
            delay(100)
            cubeNodeHolder[0]?.let { it.isVisible = false; it.isVisible = true }
        }
    }

    LaunchedEffect(trackedImages.size) {
        if (trackedImages.isNotEmpty()) {
            delay(100)
            plateNodes.values.forEach { it.isVisible = false; it.isVisible = true }
        }
    }

    SideEffect {
        cameraStream.isDepthOcclusionEnabled = depthOcclusionOn
        cameraStream.isPersonOcclusionEnabled = personOcclusionOn
    }

    fun allLines(): List<String> = listOfNotNull(
        if (isPlayback) "РЕЖИМ: воспроизведение записи" else null,
        trackingLine,
        trackingFailure?.let { "Причина: $it" },
        imagesDbText, imagesLine.ifEmpty { if (references.isEmpty()) null else "Фасад: ищу…" },
        depthSupportText, depthLine.ifEmpty { null },
        semanticSupportText, semanticLine.ifEmpty { null },
        geoSupportText, geoLine.ifEmpty { null }, vpsLine,
        "Перекрытие: глубина ${if (depthOcclusionOn) "ВКЛ" else "выкл"} · люди ${if (personOcclusionOn) "ВКЛ" else "выкл"}",
        sessionError
    )

    fun stopRecordingAndExport() {
        val file = recorder.stop() ?: run {
            message = "Запись не остановилась: ${recorder.errorMessage ?: "?"}"
            return
        }
        message = "Сохраняю запись…"
        // Своя область корутин, не привязанная к экрану: копирование доедет до конца,
        // даже если пользователь сразу вышел со стенда.
        exportScope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    ARRecorder.exportToDownloads(context, file, subdirectory = STAND_DOWNLOADS_SUBDIR)
                        ?.also { file.delete() }
                } catch (e: Exception) {
                    null
                }
            }
            message = if (uri != null) "Запись: Загрузки/$STAND_DOWNLOADS_SUBDIR/${file.name}"
            else "Не удалось скопировать запись в Загрузки (осталась в папке приложения)"
            Log.i(TAG, "Запись сохранена: ${file.name}, uri=$uri")
        }
    }

    BackHandler {
        if (recorder.state == ARRecorder.State.RECORDING) stopRecordingAndExport()
        onExit()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewW = it.width; viewH = it.height },
            engine = engine,
            materialLoader = materialLoader,
            playbackDatasetUri = playbackUri,
            planeRenderer = false,
            // Глубина и семантика: SceneView сам выключит их, если устройство не умеет.
            depthMode = Config.DepthMode.AUTOMATIC,
            semanticMode = Config.SemanticMode.ENABLED,
            cameraStream = cameraStream,
            sessionConfiguration = { session: Session, config ->
                depthSupportText = try {
                    val auto = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    val raw = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                    "Глубина: " + (if (auto) "поддерживается" else "НЕ поддерживается") +
                        " (raw: " + (if (raw) "да" else "нет") + ")"
                } catch (e: Exception) {
                    "Глубина: ошибка проверки (${e.javaClass.simpleName})"
                }
                semanticSupportText = try {
                    "Семантика: " + if (session.isSemanticModeSupported(Config.SemanticMode.ENABLED))
                        "поддерживается" else "НЕ поддерживается"
                } catch (e: Exception) {
                    "Семантика: ошибка проверки (${e.javaClass.simpleName})"
                }
                // Geospatial SceneView НЕ проверяет сам: неподдерживаемый режим или нет
                // точной геолокации — и configure() бросит исключение. Поэтому включаем
                // только после явной проверки.
                val geoSupported = try {
                    session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
                } catch (e: Exception) {
                    false
                }
                val geoOn = geoSupported && locationGranted && !isPlayback
                config.geospatialMode = if (geoOn) Config.GeospatialMode.ENABLED else Config.GeospatialMode.DISABLED
                geoSupportText = "Geospatial: " + when {
                    !geoSupported -> "НЕ поддерживается устройством"
                    !locationGranted -> "нет разрешения на точную геолокацию"
                    isPlayback -> "выключен в режиме записи"
                    else -> "включён"
                }
                if (references.isNotEmpty()) {
                    val db = AugmentedImageDatabase(session)
                    val added = mutableListOf<String>()
                    val rejected = mutableListOf<String>()
                    references.forEach { r ->
                        try {
                            db.addImage(r.name, r.bitmap, r.widthMeters)
                            added += r.name
                        } catch (e: Exception) {
                            rejected += "${r.name} (${e.javaClass.simpleName})"
                        }
                    }
                    config.augmentedImageDatabase = db
                    imagesDbText = "Эталоны в базе ARCore: ${added.joinToString().ifEmpty { "нет" }}" +
                        if (rejected.isEmpty()) "" else " · отклонены: ${rejected.joinToString()}"
                }
                Log.i(TAG, "Сессия: $depthSupportText | $semanticSupportText | $geoSupportText | $imagesDbText")
            },
            onSessionFailed = { e ->
                sessionError = "Ошибка сессии: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Ошибка сессии", e)
            },
            onTrackingFailureChanged = { reason -> trackingFailure = reason },
            onSessionUpdated = { session: Session, frame: Frame ->
                recorder.recordFrame(session)

                val ts = frame.timestamp
                if (lastFrameTs[0] != 0L) {
                    val dt = (ts - lastFrameTs[0]) / 1_000_000_000f
                    if (dt > 0f) smoothFps[0] = smoothFps[0] * 0.9f + (1f / dt) * 0.1f
                }
                lastFrameTs[0] = ts

                val camera = frame.camera
                if (camera.trackingState == TrackingState.TRACKING) {
                    lastCameraPose.set(camera.pose)
                }

                // Распознавание эталонов фасада.
                for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
                    if (img.trackingState == TrackingState.TRACKING && trackedImages.none { it == img }) {
                        trackedImages.add(img)
                        val sec = (System.currentTimeMillis() - sessionStartMs) / 1000f
                        val dist = distanceMeters(camera.pose, img.centerPose)
                        val line = stamp("HH:mm:ss") + " | ПЕРВОЕ РАСПОЗНАВАНИЕ «${img.name}»: " +
                            "%.1f с после старта, %.1f м, %s".format(sec, dist, img.trackingMethod)
                        journal.add(line)
                        message = "Фасад распознан: «${img.name}» с %.1f м".format(dist)
                        Log.i(TAG, line)
                    }
                }

                frameCounter[0]++
                if (frameCounter[0] % PROBE_EVERY_N_FRAMES == 0) {
                    imagesLine = if (trackedImages.isEmpty()) "" else "Фасад: " + trackedImages.joinToString(" · ") { img ->
                        val method = when (img.trackingMethod) {
                            AugmentedImage.TrackingMethod.FULL_TRACKING -> "ВИЖУ"
                            AugmentedImage.TrackingMethod.LAST_KNOWN_POSE -> "помню"
                            else -> "нет"
                        }
                        "«${img.name}» $method %.1f м (%.1f×%.1f м)".format(
                            distanceMeters(camera.pose, img.centerPose), img.extentX, img.extentZ
                        )
                    }
                    trackingLine = "Трекинг: ${camera.trackingState} · %.0f fps".format(smoothFps[0])
                    depthLine = probeDepthAtCenter(frame, viewW, viewH)
                    semanticLine = probeSemantics(frame)
                    val (geoText, fix) = probeEarth(session)
                    geoLine = geoText
                    if (fix != null && !vpsRequested[0] && !isPlayback) {
                        vpsRequested[0] = true
                        vpsLine = "VPS: проверяю…"
                        try {
                            session.checkVpsAvailabilityAsync(fix.latitude, fix.longitude) { result ->
                                vpsLine = "VPS в этой точке: " + when (result) {
                                    VpsAvailability.AVAILABLE -> "ЕСТЬ"
                                    VpsAvailability.UNAVAILABLE -> "НЕТ"
                                    else -> "ошибка ($result)"
                                }
                                Log.i(TAG, vpsLine)
                            }
                        } catch (e: Exception) {
                            vpsLine = "VPS: ошибка запроса (${e.javaClass.simpleName})"
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastLogcatMs[0] >= LOGCAT_EVERY_MS) {
                    lastLogcatMs[0] = now
                    Log.i(TAG, allLines().joinToString(" | "))
                }
            },
            content = {
                trackedImages.forEach { img ->
                    val plateSize = plateSizes[img.name] ?: Size(img.extentX, 0.02f, img.extentZ)
                    AugmentedImageNode(augmentedImage = img) {
                        CubeNode(
                            size = plateSize,
                            materialInstance = plateMaterial,
                            apply = { plateNodes[img.name] = this }
                        )
                    }
                }
                cubePosition?.let { pos ->
                    key(pos) {
                        CubeNode(
                            size = Size(TEST_CUBE_SIDE_M, TEST_CUBE_SIDE_M, TEST_CUBE_SIDE_M),
                            materialInstance = cubeMaterial,
                            position = pos,
                            apply = { cubeNodeHolder[0] = this }
                        )
                    }
                }
            }
        )

        // Перекрестие в центре: всё, что «в центре кадра», меряется здесь.
        Canvas(modifier = Modifier.size(28.dp).align(Alignment.Center)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.width / 2f
            drawLine(Color.White, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 3f)
            drawLine(Color.White, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 3f)
        }

        // Верх: назад + панель замеров.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (recorder.state == ARRecorder.State.RECORDING) stopRecordingAndExport()
                    onExit()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Text("AR.Фасады · стенд", color = Color.White, fontSize = 16.sp)
            }
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                allLines().forEach { Text(it, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp) }
                if (recorder.state != ARRecorder.State.IDLE) {
                    Text(
                        "Запись: ${recorder.state}" + (recorder.errorMessage?.let { " · $it" } ?: ""),
                        color = if (recorder.state == ARRecorder.State.RECORDING) Color(0xFFFF5252) else Color.Yellow,
                        fontSize = 11.sp
                    )
                }
                if (journal.isNotEmpty()) {
                    Text("Замеров в журнале: ${journal.size}", color = Color(0xFF80D8FF), fontSize = 11.sp)
                }
            }
        }

        // Низ: сообщение + кнопки.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            message?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(onClick = {
                    val pose = lastCameraPose.get()
                    if (pose == null) {
                        message = "Нет трекинга — поводи камерой"
                    } else {
                        val p = pose.compose(Pose.makeTranslation(0f, 0f, -TEST_CUBE_DISTANCE_M))
                        cubePosition = Position(p.tx(), p.ty(), p.tz())
                        message = "Куб 50 см поставлен в 3 м перед камерой"
                    }
                }) { Text("Куб 3 м") }

                FilledTonalButton(onClick = { depthOcclusionOn = !depthOcclusionOn }) {
                    Text("Глубина: " + if (depthOcclusionOn) "ВКЛ" else "выкл")
                }

                FilledTonalButton(onClick = { personOcclusionOn = !personOcclusionOn }) {
                    Text("Люди: " + if (personOcclusionOn) "ВКЛ" else "выкл")
                }

                FilledTonalButton(onClick = {
                    val line = stamp("HH:mm:ss") + " | " + allLines().joinToString(" | ")
                    journal.add(line)
                    Log.i(TAG, "ЗАМЕР: $line")
                    message = "Замер №${journal.size} записан"
                }) { Text("Замер") }

                FilledTonalButton(onClick = {
                    if (journal.isEmpty()) {
                        message = "Журнал пуст — сначала «Замер»"
                    } else {
                        val name = "stand_${stamp("yyyyMMdd_HHmmss")}.txt"
                        val text = buildString {
                            appendLine("AR.Фасады · стенд · ${stamp("yyyy-MM-dd HH:mm")}")
                            appendLine(if (isPlayback) "Источник: запись" else "Источник: живая камера")
                            journal.forEach { appendLine(it) }
                        }
                        val path = saveTextToDownloads(context, name, text)
                        message = path?.let { "Журнал: $it" } ?: "Не удалось сохранить журнал"
                    }
                }) { Text("Сохранить журнал") }

                if (!isPlayback) {
                    FilledTonalButton(onClick = {
                        if (recorder.state == ARRecorder.State.RECORDING) {
                            stopRecordingAndExport()
                        } else {
                            val dir = context.getExternalFilesDir("facades-recordings")
                            val file = File(dir, "facade_${stamp("yyyyMMdd_HHmmss")}.mp4")
                            val ok = recorder.start(file, recordingRotation = portraitRecordingRotation(context))
                            message = if (ok) "Идёт запись сессии" else "Запись не началась: ${recorder.errorMessage ?: "?"}"
                        }
                    }) {
                        Text(if (recorder.state == ARRecorder.State.RECORDING) "■ Стоп записи" else "● Запись")
                    }
                    FilledTonalButton(onClick = {
                        if (recorder.state == ARRecorder.State.RECORDING) {
                            message = "Сначала останови запись"
                        } else {
                            onPickRecording()
                        }
                    }) { Text("▶ Воспроизвести") }
                } else {
                    FilledTonalButton(onClick = onBackToLive) { Text("Живая камера") }
                }
            }
        }
    }

    // Если пользователь ушёл со стенда во время записи — запись закроет rememberARRecorder,
    // но в «Загрузки» она не скопируется; сообщим в logcat, где её искать.
    DisposableEffect(Unit) {
        onDispose {
            recorder.recordingFile?.let { Log.i(TAG, "Последний файл записи: ${it.absolutePath}") }
        }
    }
}

/** Расстояние от камеры до центра распознанного изображения, метры. */
private fun distanceMeters(a: Pose, b: Pose): Float {
    val dx = a.tx() - b.tx()
    val dy = a.ty() - b.ty()
    val dz = a.tz() - b.tz()
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * Поворот для записи сессии, чтобы MP4 в обычном плеере был вертикальным, как держали телефон.
 * Датчик камеры «лежит» (обычно 90°), экран повёрнут на свой угол; разница — нужный поворот.
 * ARRecorder принимает константу Surface.ROTATION_* и сам переводит её в градусы.
 * На воспроизведение в стенде не влияет: это только пометка поворота для плееров.
 */
private fun portraitRecordingRotation(context: android.content.Context): Int {
    val sensorDegrees = try {
        val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        manager.cameraIdList.firstNotNullOfOrNull { id ->
            val ch = manager.getCameraCharacteristics(id)
            if (ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            ) ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) else null
        } ?: 90
    } catch (e: Exception) {
        90
    }
    @Suppress("DEPRECATION")
    val displayRotation = try {
        (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
    } catch (e: Exception) {
        android.view.Surface.ROTATION_0
    }
    val displayDegrees = when (displayRotation) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }
    return when ((sensorDegrees - displayDegrees + 360) % 360) {
        90 -> android.view.Surface.ROTATION_90
        180 -> android.view.Surface.ROTATION_180
        270 -> android.view.Surface.ROTATION_270
        else -> android.view.Surface.ROTATION_0
    }
}
