package com.aerospring.arworld.feature.guide.ar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerospring.arworld.core.ui.component.SectionHelpDialog
import com.aerospring.arworld.core.ui.component.SectionHelpPrefs
import com.aerospring.arworld.feature.guide.GUIDE_HELP_KEY
import com.aerospring.arworld.feature.guide.guideHelpItems
import com.aerospring.arworld.feature.guide.data.GuideAnchor
import com.aerospring.arworld.feature.guide.data.GuideApi
import com.aerospring.arworld.feature.guide.data.GuideBundle
import com.aerospring.arworld.feature.guide.data.GuidePlace
import com.aerospring.arworld.feature.guide.data.GuideRoute
import com.aerospring.arworld.feature.guide.data.GuideStore
import com.aerospring.arworld.feature.guide.data.findRoute
import com.aerospring.arworld.feature.guide.data.nearestNode
import com.aerospring.arworld.feature.guide.data.searchPlaces
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

private const val TAG = "GuideAr"

/** Шаг «дорожки» маршрута на полу, м. */
private const val CRUMB_STEP_M = 0.35f

/** Не больше стольких квадратиков на маршрут (длинный маршрут — реже). */
private const val CRUMB_MAX = 400

/** Ближе этого к входу (по плану, на том же этаже) — «Вы пришли». */
private const val ARRIVE_RADIUS_M = 1.5f

/** Камера телефона обычно на 1,2–1,5 м выше пола: по ней определяем этаж. */
private const val CAMERA_ABOVE_FLOOR_MIN_M = 0.6f

/** Не узнана картинка за это время — подсказка, как навести камеру. */
private const val NOT_FOUND_HINT_MS = 12_000L

/** Камера не ведёт положение дольше этого — считаем, что телефон «потерял место». */
private const val LOST_AFTER_MS = 1_500L

/** Состояние «где я»: всё хорошо / потерял место / нашёлся сам, но листом не подтверждён. */
private const val PLACE_OK = 0
private const val PLACE_LOST = 1
private const val PLACE_UNSURE = 2

/**
 * AR-путеводитель по зданию. Всё — из копии на телефоне, сеть нужна только для статистики.
 *
 *  1. Опорные картинки здания — в базу ARCore. Узнан лист → по его месту на плане (x, y, высота,
 *     куда смотрит) вычисляется, где в мире стоит всё здание; на это место ставится якорь.
 *  2. Положение «успокаивается» (PoseStabilizer, как в Фасадах); увидели другой лист — якорь
 *     переставляется по нему: так снимается накопившаяся ошибка трекинга.
 *  3. «Куда идём?» → поиск места → кратчайший маршрут от ближайшей к телефону точки графа
 *     до входа в место; на полу — дорожка из квадратиков, у входа — зелёный столбик.
 *  4. Подошли ближе 1,5 м — «Вы пришли».
 *  5. Телефон потерял, где он (однотонный блестящий пол, эскалатор, быстрые движения): дорожку
 *     прячем, звучит короткий сигнал и просьба навести камеру на ближайшую опору. Нашёлся сам —
 *     дорожка возвращается, но первая же увиденная опора снова ставит здание на место.
 */
@Composable
internal fun GuideArScreen(bundle: GuideBundle, store: GuideStore, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    var loaded by remember(bundle.slug, bundle.bundleVersion) { mutableStateOf<GuideRefs?>(null) }
    LaunchedEffect(bundle.slug, bundle.bundleVersion) {
        loaded = withContext(Dispatchers.IO) {
            val groupByHash = HashMap<String, String>()
            val groupOf = HashMap<String, String>()
            val refs = bundle.anchors.filter { it.imageUrl.isNotBlank() }.mapNotNull { a ->
                val f = store.localFile(bundle.slug, a.imageUrl)
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@mapNotNull null
                // Одинаковые картинки (заставки всех терминалов) — одна запись в базе ARCore, «группа».
                val hash = try { md5(f.readBytes()) } catch (e: Exception) { a.id }
                groupOf[a.id] = groupByHash.getOrPut(hash) { "g${groupByHash.size}" }
                a to bmp
            }
            GuideRefs(refs, groupOf)
        }
    }
    val r = loaded
    when {
        r == null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        r.refs.isEmpty() -> Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("У этого здания ещё нет опорных картинок — путеводителю не за что «зацепиться».", color = Color.White)
            TextButton(onClick = onExit) { Text("Назад") }
        }
        else -> GuideArContent(bundle, r.refs, r.groupOf, onExit)
    }
}

/** Опоры с картинками и группы одинаковых картинок: id опоры → имя группы (оно же имя в базе ARCore). */
private class GuideRefs(val refs: List<Pair<GuideAnchor, Bitmap>>, val groupOf: Map<String, String>)

private fun md5(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

@Composable
private fun GuideArContent(bundle: GuideBundle, refs: List<Pair<GuideAnchor, Bitmap>>, groupOf: Map<String, String>, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val routeMaterial = remember(materialLoader) { materialLoader.createColorInstance(Color(0xFFFF7A1A)) }
    val targetMaterial = remember(materialLoader) { materialLoader.createColorInstance(Color(0xFF2E9E4F)) }

    val imageInBuilding = remember(refs) { refs.associate { (a, _) -> a.id to GuideGeometry.imageInBuilding(bundle, a) } }
    // Группа одинаковых картинок → опоры с ней (у заставок терминалов — несколько, на разных этажах).
    val members = remember(refs) { refs.map { it.first }.groupBy { groupOf.getValue(it.id) } }
    // Для ARCore и подсказок — по одной картинке на группу.
    val groupRefs = remember(refs) { refs.distinctBy { groupOf.getValue(it.first.id) } }
    // Человек сам выбрал, у какой из одинаковых опор стоит (пока здание ещё не найдено).
    val groupChoice = remember { HashMap<String, String>() }
    var askGroup by remember { mutableStateOf<String?>(null) }

    // ── Где здание в мире ──
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var anchorRefId by remember { mutableStateOf<String?>(null) }
    val stabilizer = remember { PoseStabilizer() }
    val stabRef = remember { arrayOfNulls<String>(1) }
    val frameCounter = remember { IntArray(1) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var notFoundLong by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }

    // ── Потерял место? ──  placeState — для экрана, placeFlag/notTrackingSince — для обработки кадров.
    var placeState by remember { mutableIntStateOf(PLACE_OK) }
    val placeFlag = remember { IntArray(1) }
    val notTrackingSince = remember { LongArray(1) }

    // Где телефон в здании: (x по плану, высота, y по плану) — обновляется несколько раз в секунду.
    var camInBuilding by remember { mutableStateOf<FloatArray?>(null) }
    // Куда смотрит телефон на плане: (x, y), единичный вектор.
    var camForward by remember { mutableStateOf<FloatArray?>(null) }

    // ── Куда идём ──
    var searchOpen by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<GuidePlace?>(null) }
    var accessible by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<GuideRoute?>(null) }
    var routeVersion by remember { mutableIntStateOf(0) }
    var routeMessage by remember { mutableStateOf<String?>(null) }
    var arrived by remember { mutableStateOf(false) }

    var showHelp by remember { mutableStateOf(!SectionHelpPrefs.wasShown(context, GUIDE_HELP_KEY)) }

    fun floorIdAt(height: Float): String? =
        bundle.floors.sortedBy { it.elevationM }.lastOrNull { it.elevationM <= height - CAMERA_ABOVE_FLOOR_MIN_M }?.id
            ?: bundle.floors.minByOrNull { it.elevationM }?.id

    /** Точка графа, с которой начинается маршрут: ближайшая к телефону на его этаже. */
    fun startNodeId(): String? {
        val cam = camInBuilding ?: return null
        val floorId = floorIdAt(cam[1]) ?: return null
        return nearestNode(bundle, floorId, cam[0], cam[2])?.id
    }

    fun buildRoute(place: GuidePlace) {
        routeMessage = null
        arrived = false
        val goal = place.entranceNodeId
        if (camInBuilding == null || goal == null) {
            routeMessage = "Сначала наведите камеру на лист-опору."
            return
        }
        val start = startNodeId()
        if (start == null) {
            routeMessage = "На этом этаже нет точек маршрута."
            return
        }
        val r = findRoute(bundle, start, goal, accessible)
        route = r
        routeVersion++
        if (r == null) {
            routeMessage = if (accessible) "Без лестниц и эскалаторов сюда не пройти." else "Маршрут не найден."
        } else {
            scope.launch { GuideApi.event(bundle.slug, "route", fromAnchorId = anchorRefId, toPlaceId = place.id) }
        }
    }

    /** «Ближайший выход», «Туалет»: из нескольких мест — то, до которого короче идти. */
    fun goNearest(places: List<GuidePlace>) {
        val start = startNodeId()
        val best = if (start == null) null else places.mapNotNull { p ->
            p.entranceNodeId?.let { g -> findRoute(bundle, start, g, accessible)?.let { p to it } }
        }.minByOrNull { it.second.lengthM }
        val p = best?.first ?: places.firstOrNull() ?: return
        destination = p
        buildRoute(p)
    }

    // Подсказка, если долго не узнаётся.
    LaunchedEffect(Unit) {
        while (isActive && anchor == null) {
            delay(1_000)
            notFoundLong = anchor == null && System.currentTimeMillis() - startedAt > NOT_FOUND_HINT_MS
        }
        notFoundLong = false
    }

    // Пришли?
    LaunchedEffect(camInBuilding, destination, route) {
        val cam = camInBuilding ?: return@LaunchedEffect
        val place = destination ?: return@LaunchedEffect
        if (arrived || route == null) return@LaunchedEffect
        val target = place.entranceNodeId?.let { bundle.node(it) } ?: return@LaunchedEffect
        if (floorIdAt(cam[1]) == target.floorId && hypot(cam[0] - target.x, cam[2] - target.y) < ARRIVE_RADIUS_M) {
            arrived = true
            scope.launch { GuideApi.event(bundle.slug, "arrived", fromAnchorId = anchorRefId, toPlaceId = place.id) }
        }
    }

    // Дорожка маршрута: точки через каждые 35 см вдоль проходов (на лестнице — поднимаются вместе с ней).
    val crumbs = remember(route, routeVersion) {
        val r = route ?: return@remember emptyList<Position>()
        val pts = r.nodeIds.mapNotNull { id -> bundle.node(id)?.let { n -> floatArrayOf(n.x, bundle.elevation(n.floorId) + n.heightM + 0.02f, n.y) } }
        val length = pts.zipWithNext().sumOf { (a, b) -> dist3(a, b).toDouble() }.toFloat()
        val step = maxOf(CRUMB_STEP_M, length / CRUMB_MAX)
        val out = ArrayList<Position>()
        var carry = 0f
        for ((a, b) in pts.zipWithNext()) {
            val d = dist3(a, b)
            if (d <= 1e-4f) continue
            var t = carry
            while (t < d) {
                val k = t / d
                out += Position(a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k)
                t += step
            }
            carry = t - d
        }
        out
    }
    val targetNode = destination?.entranceNodeId?.let { bundle.node(it) }
    // Куда повернуться, чтобы идти по дорожке: угол в градусах, + — направо.
    val turnDeg = remember(camInBuilding, camForward, crumbs) {
        val cam = camInBuilding ?: return@remember null
        val fwd = camForward ?: return@remember null
        turnToRoute(crumbs, cam, fwd)
    }

    // Узлы, созданные для текущего якоря и маршрута: SceneView «досылает» новый узел в Filament
    // после переприсваивания isVisible (особенность, найденная в Фасадах и Мебели).
    val createdNodes = remember(anchor, routeVersion) { mutableListOf<Node>() }
    LaunchedEffect(anchor, routeVersion) {
        delay(120)
        createdNodes.toList().forEach { it.isVisible = false; it.isVisible = true }
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
                groupRefs.forEach { (a, bmp) ->
                    try {
                        db.addImage(groupOf.getValue(a.id), bmp, a.widthM)
                    } catch (e: Exception) {
                        Log.w(TAG, "Опора ${a.id} не принята ARCore: ${e.javaClass.simpleName}")
                    }
                }
                config.augmentedImageDatabase = db
            },
            onSessionFailed = { e -> sessionError = "AR не запустилась: ${e.javaClass.simpleName}" },
            onSessionUpdated = { session: Session, frame: Frame ->
                frameCounter[0]++
                val now = System.currentTimeMillis()
                val camTracking = frame.camera.trackingState == TrackingState.TRACKING
                if (camTracking) notTrackingSince[0] = 0L else if (notTrackingSince[0] == 0L) notTrackingSince[0] = now
                anchor?.let { cur ->
                    val next = when {
                        cur.trackingState == TrackingState.STOPPED -> PLACE_LOST
                        !camTracking && now - notTrackingSince[0] > LOST_AFTER_MS -> PLACE_LOST
                        camTracking && placeFlag[0] == PLACE_LOST -> PLACE_UNSURE
                        else -> placeFlag[0]
                    }
                    if (next != placeFlag[0]) {
                        if (next == PLACE_LOST) {
                            GuideSound.lost()
                            stabilizer.reset(); stabRef[0] = null   // старые замеры листа больше не годятся
                            Log.i(TAG, "Место потеряно")
                        }
                        placeFlag[0] = next
                        placeState = next
                    }
                }
                fun placed(n: Anchor, id: String) {
                    anchor = n
                    anchorRefId = id
                    if (placeFlag[0] != PLACE_OK) {
                        placeFlag[0] = PLACE_OK
                        placeState = PLACE_OK
                        GuideSound.found()
                    }
                }
                val visible = session.getAllTrackables(AugmentedImage::class.java).filter {
                    it.trackingState == TrackingState.TRACKING &&
                        it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                }
                // Сначала — лист, по которому уже стоит здание; нет его в кадре — любой другой.
                val img = visible.firstOrNull { it.name == anchorRefId?.let { id -> groupOf[id] } } ?: visible.firstOrNull()
                // Какая именно опора: картинка одна на несколько мест (терминалы) — та, что ближе к телефону;
                // где телефон, ещё неизвестно — спрашиваем человека.
                val a = img?.let { im ->
                    val list = members[im.name].orEmpty()
                    val cam = camInBuilding
                    when {
                        list.size <= 1 -> list.firstOrNull()
                        cam != null -> list.minByOrNull { m ->
                            val p = imageInBuilding.getValue(m.id)
                            dist3(floatArrayOf(p.tx(), p.ty(), p.tz()), cam)
                        }
                        else -> groupChoice[im.name]?.let { id -> list.firstOrNull { it.id == id } }
                            ?: run { if (askGroup == null) askGroup = im.name; null }
                    }
                }
                val ib = a?.let { imageInBuilding[it.id] }
                if (img != null && a != null && ib != null) {
                    if (stabRef[0] != a.id) { stabilizer.reset(); stabRef[0] = a.id }
                    stabilizer.add(GuideGeometry.buildingInWorld(img.centerPose, ib))
                    val cam = frame.camera.pose
                    val c = img.centerPose
                    val imgDist = sqrt(
                        (cam.tx() - c.tx()).let { it * it } + (cam.ty() - c.ty()).let { it * it } + (cam.tz() - c.tz()).let { it * it }
                    )
                    val current = anchor
                    if (current == null || a.id != anchorRefId || placeFlag[0] != PLACE_OK) {
                        // Первый лист, другой лист или место было потеряно: ставим здание по нему заново
                        // (снимает накопленную ошибку).
                        stabilizer.firstPose(imgDist)?.let { p -> replaceAnchor(session, p, current, a.id, imgDist, ::placed) }
                    } else if (frameCounter[0] % 30 == 0 && current.trackingState == TrackingState.TRACKING) {
                        stabilizer.correction(current.pose, imgDist)?.let { p -> replaceAnchor(session, p, current, a.id, imgDist, ::placed) }
                    }
                }
                val current = anchor
                if (frameCounter[0] % 10 == 0 && current != null && camTracking && current.trackingState == TrackingState.TRACKING) {
                    val inv = current.pose.inverse()
                    // Поза «как на экране»: её +Y — верх экрана в любой ориентации телефона.
                    val camPose = frame.camera.displayOrientedPose
                    camInBuilding = inv.transformPoint(camPose.translation)
                    // Куда смотрит телефон: камера глядит по −Z. Телефон опущен к полу — берём «верх» телефона (+Y).
                    val back = inv.rotateVector(camPose.zAxis)
                    var fx = -back[0]; var fy = -back[2]
                    if (hypot(fx, fy) < 0.35f) {
                        val up = inv.rotateVector(camPose.yAxis)
                        fx = up[0]; fy = up[2]
                    }
                    val len = hypot(fx, fy)
                    if (len > 1e-3f) camForward = floatArrayOf(fx / len, fy / len)
                }
            }
        ) {
            val a = anchor
            // key(a): лист по пути переставил здание (новый якорь) — дорожку и столбик создаём заново
            // под новым якорем. Без этого они оставались детьми старого, уже отпущенного якоря и пропадали.
            // Место потеряно — дорожку не рисуем: она может стоять не там и увести не туда.
            if (a != null && placeState != PLACE_LOST) key(a) {
                AnchorNode(anchor = a) {
                    crumbs.forEachIndexed { i, p ->
                        key(routeVersion, i) {
                            CubeNode(
                                size = Size(0.1f, 0.01f, 0.1f),
                                materialInstance = routeMaterial,
                                position = p,
                                apply = { createdNodes += this }
                            )
                        }
                    }
                    val t = targetNode
                    if (t != null && route != null) {
                        val floorY = bundle.elevation(t.floorId)
                        key(routeVersion, "target") {
                            CubeNode(
                                size = Size(0.07f, 1.6f, 0.07f),
                                materialInstance = targetMaterial,
                                position = Position(t.x, floorY + 0.8f, t.y),
                                apply = { createdNodes += this }
                            )
                        }
                        key(routeVersion, "targetBase") {
                            CubeNode(
                                size = Size(0.5f, 0.02f, 0.5f),
                                materialInstance = targetMaterial,
                                position = Position(t.x, floorY + 0.01f, t.y),
                                apply = { createdNodes += this }
                            )
                        }
                    }
                }
            }
        }

        // ── Поверх камеры ──
        Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
            Text(bundle.name, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { showHelp = true }) { Icon(Icons.Outlined.Info, "Справка", tint = Color.White) }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).widthIn(max = 460.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            sessionError?.let { Bubble(it) }
            if (anchor == null && sessionError == null) {
                Bubble(
                    if (notFoundLong) "Лист пока не узнан. Подойдите на 1–2 метра и наведите камеру прямо на лист, чтобы он был виден целиком."
                    else "Наведите камеру на лист-опору — например, у входа."
                )
                StartHints(groupRefs, onlyStart = true)
            }
            if (anchor != null && placeState == PLACE_LOST) {
                Bubble(
                    "Телефон потерял, где вы. Наведите камеру на ближайший лист-опору — дорожка вернётся.",
                    warning = true
                )
                val floorNow = camInBuilding?.let { floorIdAt(it[1]) }
                StartHints(groupRefs.filter { (a, _) -> members[groupOf.getValue(a.id)].orEmpty().any { it.floorId == floorNow } }.ifEmpty { groupRefs }, onlyStart = false)
            } else if (anchor != null && placeState == PLACE_UNSURE) {
                Bubble("Если дорожка сместилась — наведите камеру на ближайший лист-опору.")
            }
            val place = destination
            if (anchor != null && place == null) {
                Button(onClick = { searchOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Куда идём?") }
            }
            if (anchor != null && place != null) {
                RoutePanel(
                    bundle = bundle,
                    place = place,
                    route = route,
                    message = routeMessage,
                    arrived = arrived,
                    currentFloorId = camInBuilding?.let { floorIdAt(it[1]) },
                    turnDeg = if (placeState == PLACE_LOST) null else turnDeg,
                    onRebuild = { buildRoute(place) },
                    onChange = { searchOpen = true },
                    onClear = { destination = null; route = null; routeVersion++; routeMessage = null; arrived = false }
                )
            }
        }

        if (searchOpen) {
            SearchDialog(
                bundle = bundle,
                accessible = accessible,
                onAccessible = { accessible = it },
                onPick = { p, query ->
                    searchOpen = false
                    destination = p
                    if (query.isNotBlank()) scope.launch { GuideApi.event(bundle.slug, "search", query = query, found = true) }
                    buildRoute(p)
                },
                onNearest = { list -> searchOpen = false; goNearest(list) },
                onNothingFound = { query -> scope.launch { GuideApi.event(bundle.slug, "search", query = query, found = false) } },
                onDismiss = { searchOpen = false }
            )
        }
        val g = askGroup
        if (g != null && anchor == null) {
            WhereAmIDialog(
                bundle = bundle,
                options = members[g].orEmpty(),
                onPick = { a -> groupChoice[g] = a.id; askGroup = null },
                onDismiss = { askGroup = null }
            )
        }
        if (showHelp) {
            SectionHelpDialog(title = "Путеводитель по ТЦ", items = guideHelpItems, onDismiss = {
                SectionHelpPrefs.markShown(context, GUIDE_HELP_KEY)
                showHelp = false
            })
        }
    }
}

/** Новый якорь здания вместо старого (старый отпускаем, чтобы ARCore его не вёл). */
private fun replaceAnchor(session: Session, pose: Pose, old: Anchor?, refId: String, dist: Float, set: (Anchor, String) -> Unit) {
    try {
        val n = session.createAnchor(pose)
        old?.detach()
        set(n, refId)
        Log.i(TAG, "Здание поставлено по опоре $refId (до листа %.1f м)".format(dist))
    } catch (e: Exception) {
        Log.w(TAG, "Якорь не создан: ${e.javaClass.simpleName}")
    }
}

private fun dist3(a: FloatArray, b: FloatArray): Float {
    val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Картинки стартовых опор — чтобы человек знал, что искать глазами. */
@Composable
private fun StartHints(refs: List<Pair<GuideAnchor, Bitmap>>, onlyStart: Boolean) {
    val shown = (if (onlyStart) refs.filter { it.first.role == "start" }.ifEmpty { refs } else refs).take(3)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { (a, bmp) ->
            val image = remember(bmp) { bmp.asImageBitmap() }
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.background(Color(0xB3000000), RoundedCornerShape(10.dp)).padding(6.dp)) {
                Image(image, contentDescription = a.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)))
                Text(a.name.ifBlank { a.id }, color = Color.White, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.widthIn(max = 80.dp).padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun RoutePanel(
    bundle: GuideBundle,
    place: GuidePlace,
    route: GuideRoute?,
    message: String?,
    arrived: Boolean,
    currentFloorId: String?,
    turnDeg: Float?,
    onRebuild: () -> Unit,
    onChange: () -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Color(0xD9000000), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("→ ${place.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        // Главное — куда сейчас повернуться: стрелка показывает на начало дорожки относительно телефона.
        if (!arrived && message == null && route != null && turnDeg != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(
                    Icons.Filled.ArrowUpward, contentDescription = null, tint = Color(0xFFFF9A3C),
                    modifier = Modifier.size(44.dp).rotate(turnDeg)
                )
                Text(turnText(turnDeg), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.padding(start = 10.dp))
            }
        }
        val line = when {
            arrived -> "Вы пришли! Вход — у зелёного столбика."
            message != null -> message
            route == null -> "Строим маршрут…"
            else -> {
                val floors = if (place.floorId != currentFloorId && currentFloorId != null)
                    " ${place.name} — ${floorWhere(bundle, place.floorId)}: дорожка ведёт к ${verticalWord(bundle, route)}."
                else ""
                "%.0f м по оранжевой дорожке.%s".format(route.lengthM, floors)
            }
        }
        Text(line, color = if (arrived) Color(0xFF9BE7B0) else Color(0xFFE0E0E0), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        if (place.hours.isNotBlank()) Text("Часы работы: ${place.hours}", color = Color(0xFFCFD8DC), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            TextButton(onClick = onRebuild) { Text("Перестроить", color = Color.White) }
            TextButton(onClick = onChange) { Text("Другое место", color = Color.White) }
            TextButton(onClick = onClear) { Text("Сбросить", color = Color.White) }
        }
    }
}

private fun turnText(deg: Float): String = when {
    abs(deg) <= 30f -> "Идите прямо — оранжевая дорожка на полу перед вами"
    abs(deg) >= 135f -> "Развернитесь — дорожка у вас за спиной"
    deg > 0 -> "Повернитесь направо — дорожка там, на полу"
    else -> "Повернитесь налево — дорожка там, на полу"
}

/** «на 3 этаже»; у этажа со своим названием (например, «Цокольный») — «этаж «Цокольный»». */
private fun floorWhere(b: GuideBundle, floorId: String): String {
    val f = b.floor(floorId) ?: return "на другом этаже"
    return if (f.name.isBlank() || f.name == "${f.level} этаж") "на ${f.level} этаже" else "этаж «${f.name}»"
}

/**
 * Угол от направления телефона до дорожки: берём ближайшую к телефону точку дорожки на его высоте
 * и смотрим на точку в ~3 м дальше по маршруту. Плюс — направо, минус — налево (план: x вправо, y вниз).
 */
private fun turnToRoute(crumbs: List<Position>, cam: FloatArray, fwd: FloatArray): Float? {
    if (crumbs.isEmpty()) return null
    var best = -1
    var bestD = Float.MAX_VALUE
    crumbs.forEachIndexed { i, p ->
        if (abs(p.y - (cam[1] - 1.3f)) > 2.5f) return@forEachIndexed   // другой этаж
        val d = hypot(p.x - cam[0], p.z - cam[2])
        if (d < bestD) { bestD = d; best = i }
    }
    if (best < 0) return null
    var j = best
    var acc = 0f
    while (j + 1 < crumbs.size && acc < 3f) {
        acc += hypot(crumbs[j + 1].x - crumbs[j].x, crumbs[j + 1].z - crumbs[j].z)
        j++
    }
    val tx = crumbs[j].x - cam[0]
    val ty = crumbs[j].z - cam[2]
    if (hypot(tx, ty) < 0.3f) return 0f
    val cross = fwd[0] * ty - fwd[1] * tx
    val dot = fwd[0] * tx + fwd[1] * ty
    return Math.toDegrees(atan2(cross, dot).toDouble()).toFloat()
}

/** «лестнице» / «эскалатору» / «лифту» — по первому переходу между этажами в маршруте. */
private fun verticalWord(b: GuideBundle, r: GuideRoute): String {
    for ((x, y) in r.nodeIds.zipWithNext()) {
        val nx = b.node(x) ?: continue
        val ny = b.node(y) ?: continue
        if (nx.floorId != ny.floorId) {
            val e = b.edges.firstOrNull { (it.from == x && it.to == y) || (it.from == y && it.to == x) }
            return when (e?.kind) {
                "lift" -> "лифту"
                "escalator_up", "escalator_down" -> "эскалатору"
                else -> "лестнице"
            }
        }
    }
    return "переходу"
}

/** Места-«сервисы» для быстрых кнопок: категория или название. */
private fun isExit(p: GuidePlace) = p.category.trim().lowercase() == "выход" || p.name.trim().lowercase().startsWith("выход")
private fun isToilet(p: GuidePlace) = p.category.lowercase().contains("туалет") || p.name.lowercase().contains("туалет")

@Composable
private fun SearchDialog(
    bundle: GuideBundle,
    accessible: Boolean,
    onAccessible: (Boolean) -> Unit,
    onPick: (GuidePlace, String) -> Unit,
    onNearest: (List<GuidePlace>) -> Unit,
    onNothingFound: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val all = remember(bundle) { searchPlaces(bundle, "") }
    val exits = remember(all) { all.filter(::isExit) }
    val toilets = remember(all) { all.filter(::isToilet) }
    // Категории магазинов — самые частые первыми; выходы и туалеты — отдельными кнопками.
    val categories = remember(all) {
        all.filterNot { isExit(it) || isToilet(it) }.map { it.category.trim() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }
    }
    val results = remember(query, category) {
        val found = searchPlaces(bundle, query).filterNot { isExit(it) || isToilet(it) }
        // Набран текст — ищем сквозь все категории; пусто — показываем выбранную категорию.
        if (query.isBlank() && category != null) found.filter { it.category.trim() == category } else found
    }
    AlertDialog(
        onDismissRequest = {
            if (query.isNotBlank() && results.isEmpty()) onNothingFound(query.trim())
            onDismiss()
        },
        title = { Text("Куда идём?") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Магазин или что ищете: кофе, подарок…") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (exits.isNotEmpty() || toilets.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (exits.isNotEmpty()) OutlinedButton(onClick = { onNearest(exits) }) { Text("🚪 Ближайший выход", fontSize = 13.sp) }
                        if (toilets.isNotEmpty()) OutlinedButton(onClick = { onNearest(toilets) }) { Text("🚻 Туалет", fontSize = 13.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accessible, onCheckedChange = onAccessible)
                    Text("С коляской — без лестниц и эскалаторов", fontSize = 13.sp)
                }
                if (query.isBlank() && categories.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Все") })
                        categories.forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = if (category == c) null else c },
                                label = { Text(c.replaceFirstChar { it.uppercase() }) })
                        }
                    }
                }
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (results.isEmpty()) Text("Ничего не нашлось. Попробуйте другое слово.", fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                    // Без поиска и без выбранной категории — список по категориям с заголовками.
                    val grouped = query.isBlank() && category == null && categories.size > 1
                    val ordered = if (grouped) results.sortedWith(compareBy({ it.category.trim().lowercase() }, { it.name.lowercase() })) else results
                    var lastCat: String? = null
                    ordered.forEach { p ->
                        val cat = p.category.trim()
                        if (grouped && cat != lastCat) {
                            lastCat = cat
                            Text(cat.ifEmpty { "Другое" }.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                color = Color(0xFF8AB4FF), modifier = Modifier.padding(top = 10.dp))
                        }
                        Column(Modifier.fillMaxWidth().clickable { onPick(p, query.trim()) }.padding(vertical = 10.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(listOf(if (grouped) "" else p.category, bundle.floorName(p.floorId)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                if (query.isNotBlank() && results.isEmpty()) onNothingFound(query.trim())
                onDismiss()
            }) { Text("Закрыть") }
        }
    )
}

/** Одинаковые опоры в нескольких местах (заставки терминалов): человек выбирает, у какой стоит. */
@Composable
private fun WhereAmIDialog(bundle: GuideBundle, options: List<GuideAnchor>, onPick: (GuideAnchor) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Где вы стоите?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Такой экран стоит в нескольких местах. Выберите, у какого вы сейчас:", fontSize = 14.sp)
                options.sortedWith(compareBy({ bundle.floor(it.floorId)?.level ?: 0 }, { it.name })).forEach { a ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 12.dp)) {
                        Text(a.name.ifBlank { a.id }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(bundle.floorName(a.floorId), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun Bubble(text: String, warning: Boolean = false) {
    Text(
        text, color = if (warning) Color.Black else Color.White, fontSize = 14.sp,
        fontWeight = if (warning) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.background(if (warning) Color(0xF2FFC107) else Color(0xB3000000), RoundedCornerShape(12.dp)).padding(12.dp)
    )
}
