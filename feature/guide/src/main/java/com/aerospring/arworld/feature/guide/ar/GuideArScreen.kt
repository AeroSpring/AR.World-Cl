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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
 */
@Composable
internal fun GuideArScreen(bundle: GuideBundle, store: GuideStore, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    var refs by remember(bundle.slug, bundle.bundleVersion) { mutableStateOf<List<Pair<GuideAnchor, Bitmap>>?>(null) }
    LaunchedEffect(bundle.slug, bundle.bundleVersion) {
        refs = withContext(Dispatchers.IO) {
            bundle.anchors.filter { it.imageUrl.isNotBlank() }.mapNotNull { a ->
                BitmapFactory.decodeFile(store.localFile(bundle.slug, a.imageUrl).absolutePath)?.let { a to it }
            }
        }
    }
    val r = refs
    when {
        r == null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        r.isEmpty() -> Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("У этого здания ещё нет опорных картинок — путеводителю не за что «зацепиться».", color = Color.White)
            TextButton(onClick = onExit) { Text("Назад") }
        }
        else -> GuideArContent(bundle, r, onExit)
    }
}

@Composable
private fun GuideArContent(bundle: GuideBundle, refs: List<Pair<GuideAnchor, Bitmap>>, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val routeMaterial = remember(materialLoader) { materialLoader.createColorInstance(Color(0xFFFF7A1A)) }
    val targetMaterial = remember(materialLoader) { materialLoader.createColorInstance(Color(0xFF2E9E4F)) }

    val anchorsById = remember(refs) { refs.associate { it.first.id to it.first } }
    val imageInBuilding = remember(refs) { refs.associate { (a, _) -> a.id to GuideGeometry.imageInBuilding(bundle, a) } }

    // ── Где здание в мире ──
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var anchorRefId by remember { mutableStateOf<String?>(null) }
    val stabilizer = remember { PoseStabilizer() }
    val stabRef = remember { arrayOfNulls<String>(1) }
    val frameCounter = remember { IntArray(1) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var notFoundLong by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }

    // Где телефон в здании: (x по плану, высота, y по плану) — обновляется несколько раз в секунду.
    var camInBuilding by remember { mutableStateOf<FloatArray?>(null) }

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

    fun buildRoute(place: GuidePlace) {
        routeMessage = null
        arrived = false
        val cam = camInBuilding
        val goal = place.entranceNodeId
        if (cam == null || goal == null) {
            routeMessage = "Сначала наведите камеру на лист-опору."
            return
        }
        val floorId = floorIdAt(cam[1]) ?: return
        val start = nearestNode(bundle, floorId, cam[0], cam[2])
        if (start == null) {
            routeMessage = "На этом этаже нет точек маршрута."
            return
        }
        val r = findRoute(bundle, start.id, goal, accessible)
        route = r
        routeVersion++
        if (r == null) {
            routeMessage = if (accessible) "Без лестниц и эскалаторов сюда не пройти." else "Маршрут не найден."
        } else {
            scope.launch { GuideApi.event(bundle.slug, "route", fromAnchorId = anchorRefId, toPlaceId = place.id) }
        }
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
        val pts = r.nodeIds.mapNotNull { id -> bundle.node(id)?.let { n -> floatArrayOf(n.x, bundle.elevation(n.floorId) + 0.02f, n.y) } }
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
                refs.forEach { (a, bmp) ->
                    try {
                        db.addImage(a.id, bmp, a.widthM)
                    } catch (e: Exception) {
                        Log.w(TAG, "Опора ${a.id} не принята ARCore: ${e.javaClass.simpleName}")
                    }
                }
                config.augmentedImageDatabase = db
            },
            onSessionFailed = { e -> sessionError = "AR не запустилась: ${e.javaClass.simpleName}" },
            onSessionUpdated = { session: Session, frame: Frame ->
                frameCounter[0]++
                val visible = session.getAllTrackables(AugmentedImage::class.java).filter {
                    it.trackingState == TrackingState.TRACKING &&
                        it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
                }
                // Сначала — лист, по которому уже стоит здание; нет его в кадре — любой другой.
                val img = visible.firstOrNull { it.name == anchorRefId } ?: visible.firstOrNull()
                val a = img?.let { anchorsById[it.name] }
                val ib = img?.let { imageInBuilding[it.name] }
                if (img != null && a != null && ib != null) {
                    if (stabRef[0] != img.name) { stabilizer.reset(); stabRef[0] = img.name }
                    stabilizer.add(GuideGeometry.buildingInWorld(img.centerPose, ib))
                    val cam = frame.camera.pose
                    val c = img.centerPose
                    val imgDist = sqrt(
                        (cam.tx() - c.tx()).let { it * it } + (cam.ty() - c.ty()).let { it * it } + (cam.tz() - c.tz()).let { it * it }
                    )
                    val current = anchor
                    if (current == null || img.name != anchorRefId) {
                        // Первый лист — или увидели другой лист: ставим здание по нему (снимает накопленную ошибку).
                        stabilizer.firstPose(imgDist)?.let { p -> replaceAnchor(session, p, current, img.name, imgDist) { n, id -> anchor = n; anchorRefId = id } }
                    } else if (frameCounter[0] % 30 == 0 && current.trackingState == TrackingState.TRACKING) {
                        stabilizer.correction(current.pose, imgDist)?.let { p -> replaceAnchor(session, p, current, img.name, imgDist) { n, id -> anchor = n; anchorRefId = id } }
                    }
                }
                val current = anchor
                if (frameCounter[0] % 10 == 0 && current != null && frame.camera.trackingState == TrackingState.TRACKING) {
                    camInBuilding = current.pose.inverse().transformPoint(frame.camera.pose.translation)
                }
            }
        ) {
            val a = anchor
            if (a != null) {
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
                StartHints(refs)
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
                onNothingFound = { query -> scope.launch { GuideApi.event(bundle.slug, "search", query = query, found = false) } },
                onDismiss = { searchOpen = false }
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
private fun StartHints(refs: List<Pair<GuideAnchor, Bitmap>>) {
    val shown = refs.filter { it.first.role == "start" }.ifEmpty { refs }.take(3)
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
    onRebuild: () -> Unit,
    onChange: () -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Color(0xD9000000), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("→ ${place.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        val line = when {
            arrived -> "Вы пришли! Вход — у зелёного столбика."
            message != null -> message
            route == null -> "Строим маршрут…"
            else -> {
                val floors = if (place.floorId != currentFloorId && currentFloorId != null)
                    " Место на «${bundle.floorName(place.floorId)}» — идите по оранжевой дорожке к ${verticalWord(bundle, route)}."
                else ""
                "%.0f м по оранжевой дорожке.%s".format(route.lengthM, floors)
            }
        }
        Text(line, color = if (arrived) Color(0xFF9BE7B0) else Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (place.hours.isNotBlank()) Text("Часы работы: ${place.hours}", color = Color(0xFFCFD8DC), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            TextButton(onClick = onRebuild) { Text("Перестроить", color = Color.White) }
            TextButton(onClick = onChange) { Text("Другое место", color = Color.White) }
            TextButton(onClick = onClear) { Text("Сбросить", color = Color.White) }
        }
    }
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

@Composable
private fun SearchDialog(
    bundle: GuideBundle,
    accessible: Boolean,
    onAccessible: (Boolean) -> Unit,
    onPick: (GuidePlace, String) -> Unit,
    onNothingFound: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { searchPlaces(bundle, query) }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accessible, onCheckedChange = onAccessible)
                    Text("С коляской — без лестниц и эскалаторов", fontSize = 13.sp)
                }
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (results.isEmpty()) Text("Ничего не нашлось. Попробуйте другое слово.", fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                    results.forEach { p ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(p, query.trim()) }.padding(vertical = 10.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(listOf(p.category, bundle.floorName(p.floorId)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp)
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

@Composable
private fun Bubble(text: String) {
    Text(
        text, color = Color.White, fontSize = 14.sp,
        modifier = Modifier.background(Color(0xB3000000), RoundedCornerShape(12.dp)).padding(12.dp)
    )
}
