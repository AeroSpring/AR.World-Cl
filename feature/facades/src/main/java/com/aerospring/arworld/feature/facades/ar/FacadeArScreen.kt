package com.aerospring.arworld.feature.facades.ar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerospring.arworld.core.data.model.AdMarking
import com.aerospring.arworld.feature.facades.data.FacadePackage
import com.aerospring.arworld.feature.facades.data.FacadeAiApi
import com.aerospring.arworld.feature.facades.data.FacadeChatResult
import com.aerospring.arworld.feature.facades.data.FacadeChatTurn
import com.aerospring.arworld.feature.facades.data.FacadeCounter
import com.aerospring.arworld.feature.facades.data.FacadeReference
import com.aerospring.arworld.feature.facades.data.FacadeStats
import com.aerospring.arworld.feature.facades.data.FacadeSceneModel
import com.aerospring.arworld.feature.facades.data.FacadeStore
import com.aerospring.arworld.feature.facades.data.FacadesInstallApi
import com.aerospring.arworld.feature.facades.data.InstallerException
import com.aerospring.arworld.feature.facades.data.InstallerSession
import com.aerospring.arworld.feature.facades.stand.CaptureResult
import com.aerospring.arworld.feature.facades.stand.CapturedReference
import com.aerospring.arworld.feature.facades.stand.captureReference
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.NodeScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "FacadeAr"

/** Если за это время фасад не узнан — подсказка (с правилом безопасности). */
private const val NOT_FOUND_HINT_MS = 15_000L

/** Касание засчитывается модели в этом радиусе от её центра на экране (или в пределах её видимого размера). */
private const val TAP_TOLERANCE_DP = 70f

/** Средняя яркость кадра (0…255), ниже которой фасад узнаётся плохо. */
private const val DARK_LUMA = 45

/** Анимация по умолчанию у модели сцены: {index, mode: loop | once | none}. */
private data class DefaultAnimation(val index: Int, val loop: Boolean)

/** Куда относить счётчик: сцена (s_…) или карточка (a_…), и какой erid был показан. */
private data class StatTarget(val objectId: String, val erid: String?)

/** Открытая карточка: что открыто, где учитывать действия из неё и что ещё умеет модель. */
/** Снимок монтажника, готовый к отправке: картинка, ширина и где его центр в фасаде. */
private class PendingCapture(val ref: CapturedReference, val pose: Pose)

/** Напоминание о безопасности монтажнику — один раз за запуск приложения. */
private object InstallSafety { var shown = false }

private data class OpenCard(val card: FacadeAction.Detail, val target: StatTarget, val others: List<FacadeAction>)

private fun FacadeSceneModel.animation(): DefaultAnimation? {
    // Не задано в студии — играем первую анимацию модели по кругу (если она в файле есть).
    // Выключить — в студии «Как играть: не запускать».
    val a = defaultAnimation ?: return DefaultAnimation(0, loop = true)
    val index = a["index"]?.jsonPrimitive?.intOrNull ?: return null
    return when (a["mode"]?.jsonPrimitive?.contentOrNull ?: "loop") {
        "none" -> null
        "once" -> DefaultAnimation(index, loop = false)
        else -> DefaultAnimation(index, loop = true)
    }
}

/**
 * Поза начала координат сцены относительно центра распознанного эталона.
 *
 * Плоскость эталона в ARCore: X — вправо, Z — вниз по изображению, Y — из фасада к зрителю.
 * Координаты фасада (студия): X — вправо, Y — вверх, Z — от фасада к улице. Переход —
 * поворот на −90° вокруг X. Дальше: центр эталона стоит в фасаде в точке [FacadeReference.pose]
 * (нет позы — эталон сам и есть начало координат).
 *
 * Калибровка монтажника (сдвиг, поворот вокруг вертикали, масштаб всей сцены) — не здесь, а
 * узлом внутри якоря: так её можно менять на месте (шаг 3.4) без пересоздания якоря.
 */
private fun sceneOriginFromImage(ref: FacadeReference?): Pose {
    val axes = Pose.makeRotation(-0.70710677f, 0f, 0f, 0.70710677f)
    val p = ref?.pose
    val refInFacade = if (p != null && p.position.size == 3 && p.rotation.size == 4)
        Pose(p.position.toFloatArray(), p.rotation.toFloatArray()) else Pose.IDENTITY
    return axes.compose(refInFacade.inverse())
}

/**
 * AR-просмотр фасада (шаг 3.2). Всё — из копии на телефоне, сеть не нужна.
 *
 *  1. Сцена выбирается по расписанию (FacadeSceneChooser), каждую минуту проверяется заново.
 *  2. Модели сцены и маска готовятся заранее, с «Подготовка сцены N%».
 *  3. Эталоны фасада — в базу ARCore; первый узнанный эталон ставит якорь, к нему крепится сцена.
 *  4. Маска — невидимая: закрывает части сцены, которые оказались бы «за стеной».
 *  5. Строка «Реклама · … · erid» пока сцена на экране; в тестовом режиме — красная плашка.
 *
 * Шаг 3.3: касание модели → её действия (сайт, звонок, письмо, ИИ-помощник, карточка «подробно»);
 * офлайн-счётчики (FacadeStats) — показ сцены, касания, действия, вопросы ИИ.
 */
@Composable
internal fun FacadeArScreen(
    facadeId: String,
    store: FacadeStore,
    /** Не null — предпросмотр монтажника: панель «Монтаж» (снять эталон, калибровка, выбор сцены). */
    installer: InstallerSession? = null,
    onExit: () -> Unit
) {
    BackHandler(onBack = onExit)

    val pkg = remember(facadeId) { store.cachedPackage(facadeId) }
    if (pkg == null || !store.isComplete(pkg)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Фасад не скачан на телефон полностью. Вернитесь к списку и скачайте его.", color = Color.White)
            TextButton(onClick = onExit) { Text("Назад") }
        }
        return
    }

    // Эталоны читаются с телефона до создания AR-сессии: база изображений задаётся в её настройках.
    var references by remember(facadeId) { mutableStateOf<List<Pair<FacadeReference, Bitmap>>?>(null) }
    LaunchedEffect(facadeId) {
        references = withContext(Dispatchers.IO) {
            pkg.references.mapNotNull { r ->
                BitmapFactory.decodeFile(store.localFile(pkg.facadeId, r.url).absolutePath)?.let { r to it }
            }
        }
    }
    val refs = references
    if (refs == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    FacadeArContent(pkg, store, refs, installer, onExit)
}

@Composable
private fun FacadeArContent(
    pkg: FacadePackage,
    store: FacadeStore,
    refs: List<Pair<FacadeReference, Bitmap>>,
    installer: InstallerSession?,
    onExit: () -> Unit
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val occlusion = remember(materialLoader) { materialLoader.createOcclusionInstance() }

    // ── Сцена по расписанию (проверка раз в минуту) ──
    var scheduled by remember { mutableStateOf(FacadeSceneChooser.choose(pkg)) }
    LaunchedEffect(pkg.facadeId) {
        while (isActive) {
            delay(60_000)
            val next = FacadeSceneChooser.choose(pkg)
            if (next?.id != scheduled?.id) scheduled = next
        }
    }
    // Монтажник может выбрать любую сцену, в том числе черновик (в предпросмотре они есть в копии).
    var sceneOverride by remember { mutableStateOf<String?>(null) }
    // derivedStateOf: обработчики касаний и кадров читают сцену «вживую», а не на момент композиции.
    val scene by remember { derivedStateOf { sceneOverride?.let { id -> pkg.scenes.firstOrNull { it.id == id } } ?: scheduled } }

    // Калибровка всей сцены: из копии; монтажник меняет её на месте и сохраняет на сервер.
    var calibration by remember { mutableStateOf(pkg.calibration) }
    var savedCalibration by remember { mutableStateOf(pkg.calibration) }

    // ── Подготовка моделей сцены и маски (из файлов на телефоне) ──
    val instances = remember { mutableStateMapOf<String, ModelInstance>() }
    var maskInstance by remember { mutableStateOf<ModelInstance?>(null) }
    var preparePercent by remember { mutableIntStateOf(0) }
    var prepareError by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf(false) }
    LaunchedEffect(scene?.id) {
        prepared = false
        instances.clear()
        preparePercent = 0
        val s = scene
        val files = buildList {
            pkg.mask?.let { add("mask" to store.localFile(pkg.facadeId, it.url)) }
            s?.models?.forEach { m -> add(m.id to store.localFile(pkg.facadeId, m.url)) }
        }
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1L)
        var done = 0L
        for ((id, file) in files) {
            val inst = try {
                // Только «file://…»: голый путь SceneView ищет среди assets приложения (проверено на телефоне).
                modelLoader.loadModelInstance(Uri.fromFile(file).toString())
            } catch (e: Exception) {
                Log.e(TAG, "Модель $id не загрузилась", e)
                null
            }
            if (inst == null) prepareError = "Одна из моделей не открылась: ${file.name}"
            else if (id == "mask") maskInstance = inst else instances[id] = inst
            done += file.length()
            preparePercent = ((done * 100) / total).toInt()
        }
        prepared = true
    }

    // ── Распознавание ──
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var anchorRefId by remember { mutableStateOf<String?>(null) }
    // Поправка положения сцены внутри якоря (см. PoseStabilizer): якорь не пересоздаётся,
    // чтобы не пересоздавать модели, — сдвигается только узел сцены.
    var correction by remember { mutableStateOf(Position(0f, 0f, 0f)) }
    val stabilizer = remember { PoseStabilizer() }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var dark by remember { mutableStateOf(false) }
    var notFoundLong by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }
    val frameCounter = remember { IntArray(1) }
    val createdNodes = remember { mutableListOf<Node>() }
    val refsById = remember(refs) { refs.associate { it.first.id to it.first } }
    val hintReference = remember(refs) { pickHintReference(refs, FacadeSceneChooser.now(pkg).hour) }

    // ── Касания, действия, счётчики (шаг 3.3) ──
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelNodes = remember { HashMap<ModelNode, FacadeSceneModel>() }
    val lastFrame = remember { arrayOfNulls<Frame>(1) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = LocalDensity.current
    val tapTolerancePx = with(density) { TAP_TOLERANCE_DP.dp.toPx() }
    val touchDown = remember { FloatArray(2) }   // x, y
    val touchDownAt = remember { LongArray(1) }

    // ── Монтаж (шаг 3.4): только в предпросмотре монтажника ──
    val visibleImages = remember { arrayOfNulls<List<AugmentedImage>>(1) }
    var installMode by remember { mutableStateOf(InstallMode.NONE) }
    var safetyDialog by remember { mutableStateOf(installer != null && !InstallSafety.shown) }
    var pending by remember { mutableStateOf<PendingCapture?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    var calSaving by remember { mutableStateOf(false) }
    var calMessage by remember { mutableStateOf<String?>(null) }

    fun shoot() {
        installMessage = null
        val frame = lastFrame[0]
        val images = visibleImages[0].orEmpty()
        val img = images.firstOrNull { it.name == anchorRefId } ?: images.firstOrNull()
        val a = anchor
        if (frame == null || img == null || a == null) {
            installMessage = "Узнанное место сейчас не в кадре. $SAFETY_LOOK_AROUND Затем, если безопасно, поверните камеру так, чтобы оно попало в кадр вместе с новым местом."
            return
        }
        when (val r = captureReference(frame, viewport.width, viewport.height, images, "capture")) {
            is CaptureResult.Failed -> installMessage = "Не снялось: ${r.reason}"
            is CaptureResult.Ok -> {
                // Начало фасада — по узнанному эталону В ЭТОМ ЖЕ кадре, а не по якорю: якорь мог
                // «уплыть» (на мониторе — точно), а взаимное положение двух мест в одном кадре точное.
                val origin = img.centerPose.compose(sceneOriginFromImage(refsById[img.name]))
                val pose = newReferencePose(origin, img.centerPose, frame.camera.pose)
                if (pose == null) installMessage = "Камера смотрит слишком вдоль фасада: снимайте место примерно прямо."
                else { sendError = null; pending = PendingCapture(r.reference, pose) }
            }
        }
    }

    fun send(lighting: String) {
        val p = pending ?: return
        val s = installer ?: return
        sending = true
        sendError = null
        scope.launch {
            try {
                FacadesInstallApi.uploadReference(
                    s, pkg.facadeId, p.ref.bitmap, p.ref.widthMeters, lighting,
                    p.pose.translation, p.pose.rotationQuaternion, p.ref.distanceMeters
                )
                pending = null
                installMessage = "Эталон отправлен. У прохожих он заработает после обновления фасада, у вас — после нового «Предпросмотра»."
            } catch (e: InstallerException) {
                sendError = e.message + " — снимок не потерян, можно отправить ещё раз."
            } catch (e: Exception) {
                sendError = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                sending = false
            }
        }
    }

    fun saveCalibration() {
        val s = installer ?: return
        val c = calibration
        calSaving = true
        calMessage = null
        scope.launch {
            try {
                FacadesInstallApi.saveCalibration(s, pkg.facadeId, c)
                savedCalibration = c
                calMessage = "Сохранено. Прохожие увидят после обновления фасада."
            } catch (e: InstallerException) {
                calMessage = "Не сохранено: ${e.message}"
            } catch (e: Exception) {
                calMessage = "Не сохранено: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                calSaving = false
            }
        }
    }
    var sheet by remember { mutableStateOf<List<FacadeAction>?>(null) }
    var sheetTarget by remember { mutableStateOf<StatTarget?>(null) }
    var openCard by remember { mutableStateOf<OpenCard?>(null) }
    var chatOpen by remember { mutableStateOf(false) }
    var chatTarget by remember { mutableStateOf<StatTarget?>(null) }
    val chatItems = remember { mutableStateListOf<ChatItem>() }   // история — только на телефоне
    var chatSending by remember { mutableStateOf(false) }

    fun count(target: StatTarget, c: FacadeCounter) {
        scope.launch { FacadeStats.count(context, pkg.facadeId, target.objectId, target.erid, c) }
    }

    fun perform(action: FacadeAction, target: StatTarget, modelActions: List<FacadeAction>) {
        sheet = null
        when (action) {
            is FacadeAction.Detail -> {
                val cardTarget = StatTarget(action.assetId, action.erid ?: scene?.erid)
                count(cardTarget, FacadeCounter.CARD_OPEN)
                openCard = OpenCard(action, cardTarget, modelActions.filter { it !is FacadeAction.Detail })
            }
            is FacadeAction.Ai -> { chatTarget = target; chatOpen = true }
            else -> if (launchExternal(context, action)) action.counter()?.let { count(target, it) }
        }
    }

    fun onModelTap(m: FacadeSceneModel) {
        val s = scene ?: return
        val target = StatTarget(s.id, s.erid)
        count(target, FacadeCounter.MODEL_TAP)
        val actions = parseActions(m.interactions, pkg.ai.enabled)
        when (actions.size) {
            0 -> Unit
            1 -> perform(actions[0], target, actions)
            else -> { sheetTarget = target; sheet = actions }
        }
    }

    /**
     * Какая модель под пальцем. Встроенный «пикинг» SceneView в проекте подводил (AR.Визитки,
     * «Где что находится»), поэтому — свой: центр модели проецируется на экран матрицами камеры
     * ARCore, засчитывается ближайшая в пределах 70dp или видимого размера модели.
     */
    fun findTappedModel(tap: Offset): FacadeSceneModel? {
        val frame = lastFrame[0] ?: return null
        if (viewport.width == 0 || viewport.height == 0) return null
        val view = FloatArray(16)
        val proj = FloatArray(16)
        val vp = FloatArray(16)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(proj, 0, 0.05f, 500f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        fun project(x: Float, y: Float, z: Float): Offset? {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, vp, 0, floatArrayOf(x, y, z, 1f), 0)
            if (out[3] <= 0f) return null   // позади камеры
            return Offset((out[0] / out[3] * 0.5f + 0.5f) * viewport.width, (0.5f - out[1] / out[3] * 0.5f) * viewport.height)
        }
        val right = frame.camera.displayOrientedPose.xAxis
        var best: FacadeSceneModel? = null
        var bestScore = Float.MAX_VALUE
        for ((node, m) in modelNodes) {
            if (!node.isVisible) continue
            val p = node.worldPosition
            val center = project(p.x, p.y, p.z) ?: continue
            // Видимый размер модели: половина её габарита (с учётом масштаба сцены) по горизонтали экрана.
            val sc = node.worldScale
            val he = node.halfExtent
            val r = (maxOf(he.x, he.y, he.z) + kotlin.math.sqrt(node.center.let { it.x * it.x + it.y * it.y + it.z * it.z })) * maxOf(sc.x, sc.y, sc.z)
            val radiusPx = project(p.x + right[0] * r, p.y + right[1] * r, p.z + right[2] * r)?.let { (it - center).getDistance() } ?: 0f
            val reach = maxOf(tapTolerancePx, radiusPx)
            val d = (center - tap).getDistance()
            if (d <= reach && d / reach < bestScore) { best = m; bestScore = d / reach }
        }
        return best
    }

    fun sendToAi(text: String) {
        val target = chatTarget ?: scene?.let { StatTarget(it.id, it.erid) } ?: return
        val history = chatItems.filter { !it.isError }.map { FacadeChatTurn(it.role, it.content) }
        chatItems += ChatItem("user", text)
        chatSending = true
        count(target, FacadeCounter.AI_QUESTION)
        scope.launch {
            when (val r = FacadeAiApi.chat(pkg.facadeId, text, history)) {
                is FacadeChatResult.Ok -> chatItems += ChatItem("assistant", r.reply, r.action?.toFacadeAction())
                is FacadeChatResult.Error -> chatItems += ChatItem("assistant", r.message, isError = true)
            }
            chatSending = false
        }
    }

    // Показ сцены: фасад узнан и хотя бы одна модель сцены готова. Повтор в той же сессии не считается.
    LaunchedEffect(anchor != null, scene?.id, instances.isNotEmpty()) {
        val s = scene
        if (anchor != null && s != null && instances.isNotEmpty()) FacadeStats.impression(context, pkg.facadeId, s.id, s.erid)
    }

    LaunchedEffect(Unit) {
        while (isActive && anchor == null) {
            delay(1_000)
            notFoundLong = anchor == null && System.currentTimeMillis() - startedAt > NOT_FOUND_HINT_MS
        }
        notFoundLong = false
    }
    // Особенность SceneView: новый узел «досылается» в Filament после переприсваивания isVisible.
    LaunchedEffect(anchor, instances.size, maskInstance) {
        delay(120)
        createdNodes.forEach { it.isVisible = false; it.isVisible = true }
    }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            planeRenderer = false,
            planeFindingMode = Config.PlaneFindingMode.DISABLED,
            depthMode = Config.DepthMode.DISABLED,
            sessionConfiguration = { session: Session, config ->
                val db = AugmentedImageDatabase(session)
                refs.forEach { (r, bmp) ->
                    try {
                        db.addImage(r.id, bmp, r.widthM)
                    } catch (e: Exception) {
                        Log.w(TAG, "Эталон ${r.id} не принят ARCore: ${e.javaClass.simpleName}")
                    }
                }
                config.augmentedImageDatabase = db
            },
            onTouchEvent = { e: MotionEvent, _ ->
                // Короткое касание без сдвига — «тап» по модели; остальное SceneView обрабатывает как обычно.
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { touchDown[0] = e.x; touchDown[1] = e.y; touchDownAt[0] = e.eventTime }
                    MotionEvent.ACTION_UP -> {
                        val moved = kotlin.math.hypot(e.x - touchDown[0], e.y - touchDown[1])
                        if (moved < tapTolerancePx / 3f && e.eventTime - touchDownAt[0] < 500) {
                            findTappedModel(Offset(e.x, e.y))?.let { onModelTap(it) }
                        }
                    }
                }
                false
            },
            onSessionFailed = { e -> sessionError = "AR не запустилась: ${e.javaClass.simpleName}" },
            onSessionUpdated = { session: Session, frame: Frame ->
                frameCounter[0]++
                lastFrame[0] = frame
                // Эталон, который сейчас виден по-настоящему (не «по памяти»): сначала тот, к которому
                // уже привязана сцена, иначе любой.
                val visible = session.getAllTrackables(AugmentedImage::class.java).filter {
                    it.trackingState == TrackingState.TRACKING &&
                        it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                }
                visibleImages[0] = visible
                val img = visible.firstOrNull { it.name == anchorRefId } ?: visible.firstOrNull()
                if (img != null) {
                    if (img.name != anchorRefId) {
                        if (anchorRefId != null) stabilizer.reset()   // другой эталон — копим заново
                        anchorRefId = img.name
                    }
                    stabilizer.add(img.centerPose.compose(sceneOriginFromImage(refsById[img.name])))
                    val current = anchor
                    val cam = frame.camera.pose
                    val imgDist = kotlin.math.sqrt(
                        (cam.tx() - img.centerPose.tx()).let { it * it } + (cam.ty() - img.centerPose.ty()).let { it * it } +
                            (cam.tz() - img.centerPose.tz()).let { it * it }
                    )
                    if (current == null) {
                        // Первый якорь — по среднему из нескольких согласных кадров, а не по одному.
                        stabilizer.firstPose(imgDist)?.let { p ->
                            try {
                                anchor = session.createAnchor(p)
                                Log.i(TAG, "Фасад узнан по эталону ${img.name}: %.1f м, разброс %.2f м".format(imgDist, stabilizer.lastSpread()))
                            } catch (e: Exception) {
                                Log.w(TAG, "Якорь не создан: ${e.javaClass.simpleName}")
                            }
                        }
                    } else if (frameCounter[0] % 30 == 0 && current.trackingState == TrackingState.TRACKING) {
                        val c = correction
                        val effective = current.pose.compose(Pose.makeTranslation(c.x, c.y, c.z))
                        stabilizer.correction(effective, imgDist)?.let { target ->
                            val local = current.pose.inverse().compose(target)
                            Log.i(TAG, "Поправка сцены: %.2f м (до эталона %.1f м)".format(
                                kotlin.math.sqrt((local.tx() - c.x).let { it * it } + (local.ty() - c.y).let { it * it } + (local.tz() - c.z).let { it * it }),
                                imgDist))
                            correction = Position(local.tx(), local.ty(), local.tz())
                        }
                    }
                }
                if (frameCounter[0] % 45 == 0) frameLuma(frame)?.let { dark = it < DARK_LUMA }
            }
        ) {
            val a = anchor
            val s = scene
            if (a != null && s != null) {
                AnchorNode(anchor = a) {
                    Node(position = correction) {
                    val cal = calibration
                    val cp = cal.position.takeIf { it.size == 3 } ?: listOf(0f, 0f, 0f)
                    Node(
                        position = Position(cp[0], cp[1], cp[2]),
                        rotation = Rotation(0f, cal.rotationY, 0f),
                        scale = Scale(cal.scale.takeIf { it > 0f } ?: 1f)
                    ) {
                        maskInstance?.let { mask ->
                            key("mask") {
                                ModelNode(
                                    modelInstance = mask,
                                    autoAnimate = false,
                                    apply = {
                                        // Невидимая стена: пишет только глубину и рисуется первой.
                                        setMaterialInstance(occlusion)
                                        renderableNodes.forEach { it.setPriority(0) }
                                        createdNodes += this
                                    }
                                )
                            }
                        }
                        s.models.forEach { m ->
                            val inst = instances[m.id]
                            if (inst != null) key(m.id) { SceneModelNode(m, inst, createdNodes, modelNodes) }
                        }
                    }
                    }
                }
            }
        }

        if (installer != null && installMode == InstallMode.CAPTURE) CaptureGuide()

        // ── Поверх камеры ──
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
                Text(pkg.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            pkg.ad.testBanner?.let {
                Text(
                    it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xCCD32F2F)).padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        val hint: String? = when {
            sessionError != null -> sessionError
            scene == null -> "Сейчас на этом фасаде нечего показать."
            anchor == null && notFoundLong ->
                // Правило безопасности: прежде чем советовать переместиться — попросить осмотреться.
                "Здание пока не узнано. Сначала осмотритесь: бордюры, дорога и автомобили, другие прохожие. " +
                    "Затем, если безопасно, встаньте так, чтобы место с картинки было видно целиком и прямо."
            else -> null
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (dark) Bubble("Темновато: фасад узнаётся хуже. Лучше всего — днём.")
            hint?.let { Bubble(it) }
            // Пока фасад не узнан — показываем, КУДА навести камеру: прохожий не знает ни названия,
            // ни где именно «фасад». Снимок эталона — та самая часть здания.
            if (anchor == null && sessionError == null && scene != null) {
                hintReference?.let { ReferenceHint(it) }
            }
            if (!prepared && scene != null) {
                Column(
                    Modifier.widthIn(max = 420.dp).fillMaxWidth().background(Color(0xB3000000), RoundedCornerShape(12.dp)).padding(12.dp)
                ) {
                    Text("Подготовка сцены: $preparePercent%", color = Color.White, fontSize = 14.sp)
                    LinearProgressIndicator(progress = { preparePercent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            prepareError?.let { Bubble(it) }
            if (anchor != null) scene?.ad?.let { AdLine(it) }
            if (installer != null) {
                installMessage?.let { Bubble(it) }
                if (installMode == InstallMode.CALIBRATE) {
                    CalibrationPanel(
                        calibration = calibration,
                        saving = calSaving,
                        message = calMessage,
                        onChange = { calibration = it; calMessage = null },
                        onSave = ::saveCalibration,
                        onCancel = { calibration = savedCalibration; calMessage = null; installMode = InstallMode.NONE }
                    )
                } else {
                    InstallBar(
                        recognized = anchor != null,
                        mode = installMode,
                        scenes = pkg.scenes,
                        sceneOverride = sceneOverride,
                        onMode = { installMode = it; installMessage = null },
                        onScene = { sceneOverride = it },
                        onShoot = ::shoot
                    )
                }
            }
        }

        sheet?.let { actions ->
            ActionSheet(
                title = null, actions = actions,
                onPick = { a -> sheetTarget?.let { perform(a, it, actions) } },
                onDismiss = { sheet = null }
            )
        }
        openCard?.let { oc ->
            FacadeCardDialog(
                card = oc.card,
                file = store.localFile(pkg.facadeId, oc.card.assetUrl),
                engine = engine,
                modelLoader = modelLoader,
                ad = oc.card.ad ?: scene?.ad,
                otherActions = oc.others,
                // Действия из карточки учитываются на карточке (её erid).
                onAction = { a ->
                    if (a is FacadeAction.Ai) { openCard = null; chatTarget = oc.target; chatOpen = true }
                    else if (launchExternal(context, a)) a.counter()?.let { count(oc.target, it) }
                },
                onDismiss = { openCard = null }
            )
        }
        if (chatOpen) {
            FacadeAiChatDialog(
                title = "Помощник · ${pkg.title}",
                items = chatItems,
                sending = chatSending,
                onSend = ::sendToAi,
                onAction = { a ->
                    val t = chatTarget ?: scene?.let { StatTarget(it.id, it.erid) }
                    if (launchExternal(context, a) && t != null) a.counter()?.let { count(t, it) }
                },
                onDismiss = { chatOpen = false }
            )
        }
        if (safetyDialog) InstallSafetyDialog(onOk = { InstallSafety.shown = true; safetyDialog = false })
        pending?.let { p ->
            CaptureConfirmDialog(
                bitmap = p.ref.bitmap,
                widthM = p.ref.widthMeters,
                distanceM = p.ref.distanceMeters,
                defaultLighting = lightingByHour(FacadeSceneChooser.now(pkg).hour),
                sending = sending,
                error = sendError,
                onSend = ::send,
                onCancel = { pending = null; sendError = null }
            )
        }
    }
}

@Composable
private fun NodeScope.SceneModelNode(
    m: FacadeSceneModel,
    inst: ModelInstance,
    createdNodes: MutableList<Node>,
    modelNodes: MutableMap<ModelNode, FacadeSceneModel>
) {
    var node by remember(m.id) { mutableStateOf<ModelNode?>(null) }
    ModelNode(
        modelInstance = inst,
        autoAnimate = false,
        position = Position(m.position.getOrElse(0) { 0f }, m.position.getOrElse(1) { 0f }, m.position.getOrElse(2) { 0f }),
        rotation = Rotation(m.rotation.getOrElse(0) { 0f }, m.rotation.getOrElse(1) { 0f }, m.rotation.getOrElse(2) { 0f }),
        scale = Scale(if (m.scale > 0f) m.scale else 1f),
        apply = {
            createdNodes += this
            modelNodes[this] = m
            node = this
        }
    )
    // Модель ушла со сцены (сменилась сцена по расписанию) — касания по ней больше не ловим.
    DisposableEffect(node) {
        val n = node
        onDispose { n?.let { modelNodes.remove(it) } }
    }
    // Анимация по индексу с ручным временем (как в AR.Визитках): своего зацикливания у SceneView нет.
    val anim = remember(m.id) { m.animation() }
    LaunchedEffect(node, anim) {
        val n = node ?: return@LaunchedEffect
        val a = anim ?: return@LaunchedEffect
        val animator = n.animator
        if (a.index < 0 || a.index >= animator.animationCount) return@LaunchedEffect
        val duration = animator.getAnimationDuration(a.index)
        val start = withFrameNanos { it }
        while (isActive) {
            val elapsed = withFrameNanos { (it - start) / 1_000_000_000f }
            val t = when {
                duration <= 0f -> 0f
                a.loop -> elapsed % duration
                else -> elapsed.coerceAtMost(duration)
            }
            animator.applyAnimation(a.index, t)
            animator.updateBoneMatrices()
            if (!a.loop && elapsed >= duration) break
        }
    }
}

/** Эталон для подсказки: снятый при похожем освещении (по часу у фасада), иначе первый. */
private fun pickHintReference(refs: List<Pair<FacadeReference, Bitmap>>, hour: Int): Bitmap? {
    val want = when (hour) {
        in 7..16 -> setOf("day", "overcast")
        in 17..20 -> setOf("evening")
        else -> setOf("night", "evening")
    }
    return (refs.firstOrNull { it.first.lighting in want } ?: refs.firstOrNull { it.first.lighting == "any" } ?: refs.firstOrNull())?.second
}

@Composable
private fun ReferenceHint(bitmap: Bitmap) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Row(
        Modifier.widthIn(max = 420.dp).background(Color(0xB3000000), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = image, contentDescription = "Куда навести камеру",
            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop
        )
        Text("Наведите камеру на это место здания", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Bubble(text: String) {
    Text(
        text, color = Color.White, fontSize = 14.sp,
        modifier = Modifier.widthIn(max = 420.dp).background(Color(0xB3000000), RoundedCornerShape(12.dp)).padding(12.dp)
    )
}

/** Строка маркировки рекламы; касание — наименование, ИНН и erid с копированием. */
@Composable
private fun AdLine(ad: AdMarking) {
    val line = ad.line ?: return
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Text(
        line, color = Color.White, fontSize = 11.sp,
        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).clickable { open = true }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
    if (open) {
        val details = listOfNotNull(
            ad.advertiserName?.let { "Рекламодатель: $it" },
            ad.advertiserInn?.let { "ИНН: $it" },
            "erid: ${ad.erid ?: "—"}"
        ).joinToString("\n")
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(ad.label) },
            text = { SelectionContainer { Text(details) } },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } },
            dismissButton = {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("erid", ad.erid ?: details))
                    open = false
                }) { Text("Копировать erid") }
            }
        )
    }
}

/** Средняя яркость кадра камеры по сетке точек (0…255); null — кадр недоступен. */
private fun frameLuma(frame: Frame): Int? = try {
    frame.acquireCameraImage().use { image ->
        val plane = image.planes[0]
        val buf = plane.buffer
        val w = image.width
        val h = image.height
        var sum = 0L
        var n = 0
        for (gy in 1..12) for (gx in 1..12) {
            val x = w * gx / 13
            val y = h * gy / 13
            sum += buf.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
            n++
        }
        (sum / n).toInt()
    }
} catch (e: Exception) {
    null
}
