package com.aerospring.arworld.feature.facades.ar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
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
import io.github.sceneview.rememberOnGestureListener
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

/** Средняя яркость кадра (0…255), ниже которой фасад узнаётся плохо. */
private const val DARK_LUMA = 45

/** Анимация по умолчанию у модели сцены: {index, mode: loop | once | none}. */
private data class DefaultAnimation(val index: Int, val loop: Boolean)

/** Куда относить счётчик: сцена (s_…) или карточка (a_…), и какой erid был показан. */
private data class StatTarget(val objectId: String, val erid: String?)

/** Открытая карточка: что открыто, где учитывать действия из неё и что ещё умеет модель. */
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
 * (нет позы — эталон сам и есть начало координат), а калибровка монтажника сдвигает и
 * поворачивает (вокруг вертикали) всю сцену.
 */
private fun sceneOriginFromImage(ref: FacadeReference?, pkg: FacadePackage): Pose {
    val axes = Pose.makeRotation(-0.70710677f, 0f, 0f, 0.70710677f)
    val p = ref?.pose
    val refInFacade = if (p != null && p.position.size == 3 && p.rotation.size == 4)
        Pose(p.position.toFloatArray(), p.rotation.toFloatArray()) else Pose.IDENTITY
    val cal = pkg.calibration
    val half = Math.toRadians(cal.rotationY.toDouble()) / 2
    val calPose = Pose(
        (cal.position.takeIf { it.size == 3 } ?: listOf(0f, 0f, 0f)).toFloatArray(),
        floatArrayOf(0f, kotlin.math.sin(half).toFloat(), 0f, kotlin.math.cos(half).toFloat())
    )
    return axes.compose(refInFacade.inverse()).compose(calPose)
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
internal fun FacadeArScreen(facadeId: String, store: FacadeStore, onExit: () -> Unit) {
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
    FacadeArContent(pkg, store, refs, onExit)
}

@Composable
private fun FacadeArContent(
    pkg: FacadePackage,
    store: FacadeStore,
    refs: List<Pair<FacadeReference, Bitmap>>,
    onExit: () -> Unit
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val occlusion = remember(materialLoader) { materialLoader.createOcclusionInstance() }

    // ── Сцена по расписанию (проверка раз в минуту) ──
    var scene by remember { mutableStateOf(FacadeSceneChooser.choose(pkg)) }
    LaunchedEffect(pkg.facadeId) {
        while (isActive) {
            delay(60_000)
            val next = FacadeSceneChooser.choose(pkg)
            if (next?.id != scene?.id) scene = next
        }
    }

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
    val modelNodes = remember { HashMap<Node, FacadeSceneModel>() }
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
            modifier = Modifier.fillMaxSize(),
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
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { _, node ->
                    // Касание попадает в узел модели или в его часть — поднимаемся до модели сцены.
                    var n: Node? = node
                    while (n != null) {
                        val m = modelNodes[n]
                        if (m != null) { onModelTap(m); break }
                        n = n.parent
                    }
                }
            ),
            onSessionFailed = { e -> sessionError = "AR не запустилась: ${e.javaClass.simpleName}" },
            onSessionUpdated = { session: Session, frame: Frame ->
                frameCounter[0]++
                // Эталон, который сейчас виден по-настоящему (не «по памяти»): сначала тот, к которому
                // уже привязана сцена, иначе любой.
                val visible = session.getAllTrackables(AugmentedImage::class.java).filter {
                    it.trackingState == TrackingState.TRACKING &&
                        it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                }
                val img = visible.firstOrNull { it.name == anchorRefId } ?: visible.firstOrNull()
                if (img != null) {
                    if (img.name != anchorRefId) {
                        if (anchorRefId != null) stabilizer.reset()   // другой эталон — копим заново
                        anchorRefId = img.name
                    }
                    stabilizer.add(img.centerPose.compose(sceneOriginFromImage(refsById[img.name], pkg)))
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
                    Node(position = correction, scale = Scale(pkg.calibration.scale.takeIf { it > 0f } ?: 1f)) {
                        maskInstance?.let { mask ->
                            key("mask") {
                                ModelNode(
                                    modelInstance = mask,
                                    autoAnimate = false,
                                    apply = {
                                        // Невидимая стена: пишет только глубину и рисуется первой.
                                        setMaterialInstance(occlusion)
                                        renderableNodes.forEach { it.setPriority(0) }
                                        // Стена не должна «перехватывать» касания моделей перед ней.
                                        isTouchable = false
                                        isHittable = false
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
    }
}

@Composable
private fun NodeScope.SceneModelNode(
    m: FacadeSceneModel,
    inst: ModelInstance,
    createdNodes: MutableList<Node>,
    modelNodes: MutableMap<Node, FacadeSceneModel>
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
