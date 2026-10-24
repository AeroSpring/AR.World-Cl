package com.aerospring.arworld.feature.facades.ar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerospring.arworld.core.data.model.AdMarking
import com.aerospring.arworld.feature.facades.data.FacadePackage
import com.aerospring.arworld.feature.facades.data.FacadeReference
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

private fun FacadeSceneModel.animation(): DefaultAnimation? {
    val a = defaultAnimation ?: return null
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
 * Касания моделей и счётчики — шаг 3.3.
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
                modelLoader.loadModelInstance(file.absolutePath)
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
    var trackingNow by remember { mutableStateOf(false) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var dark by remember { mutableStateOf(false) }
    var notFoundLong by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }
    val frameCounter = remember { IntArray(1) }
    val createdNodes = remember { mutableListOf<Node>() }
    val refsById = remember(refs) { refs.associate { it.first.id to it.first } }

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
            onSessionFailed = { e -> sessionError = "AR не запустилась: ${e.javaClass.simpleName}" },
            onSessionUpdated = { _: Session, frame: Frame ->
                frameCounter[0]++
                var seenNow = false
                for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
                    if (img.trackingState != TrackingState.TRACKING) continue
                    if (img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) seenNow = true
                    if (anchor == null && img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                        val ref = refsById[img.name]
                        try {
                            // Якорь на самом изображении: ARCore уточняет его вместе с распознаванием.
                            anchor = img.createAnchor(img.centerPose.compose(sceneOriginFromImage(ref, pkg)))
                            anchorRefId = img.name
                            Log.i(TAG, "Фасад узнан по эталону ${img.name}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Якорь не создан: ${e.javaClass.simpleName}")
                        }
                    }
                }
                if (frameCounter[0] % 15 == 0) trackingNow = seenNow || anchor != null
                if (frameCounter[0] % 45 == 0) frameLuma(frame)?.let { dark = it < DARK_LUMA }
            }
        ) {
            val a = anchor
            val s = scene
            if (a != null && s != null) {
                AnchorNode(anchor = a) {
                    Node(scale = Scale(pkg.calibration.scale.takeIf { it > 0f } ?: 1f)) {
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
                            if (inst != null) key(m.id) { SceneModelNode(m, inst, createdNodes) }
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
                "Фасад пока не узнан. Сначала осмотритесь: бордюры, дорога и автомобили, другие прохожие. " +
                    "Затем, если безопасно, встаньте так, чтобы фасад был виден целиком и прямо."
            anchor == null -> "Наведите камеру на фасад «${pkg.title}»."
            else -> null
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (dark) Bubble("Темновато: фасад узнаётся хуже. Лучше всего — днём.")
            hint?.let { Bubble(it) }
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
    }
}

@Composable
private fun NodeScope.SceneModelNode(m: FacadeSceneModel, inst: ModelInstance, createdNodes: MutableList<Node>) {
    var node by remember(m.id) { mutableStateOf<ModelNode?>(null) }
    ModelNode(
        modelInstance = inst,
        autoAnimate = false,
        position = Position(m.position.getOrElse(0) { 0f }, m.position.getOrElse(1) { 0f }, m.position.getOrElse(2) { 0f }),
        rotation = Rotation(m.rotation.getOrElse(0) { 0f }, m.rotation.getOrElse(1) { 0f }, m.rotation.getOrElse(2) { 0f }),
        scale = Scale(if (m.scale > 0f) m.scale else 1f),
        apply = {
            createdNodes += this
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
