package com.aerospring.arworld.feature.furniture.ar

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
//import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import com.aerospring.arworld.core.ui.component.HelpItem
import com.aerospring.arworld.core.ui.component.SectionHelpDialog
import com.aerospring.arworld.core.ui.component.SectionHelpPrefs
import com.aerospring.arworld.feature.furniture.data.FurnitureModel
import com.aerospring.arworld.feature.furniture.data.FurnitureShowcase
import com.aerospring.arworld.feature.furniture.data.FurnitureShowcaseStatsRepository
import com.aerospring.arworld.feature.furniture.ui.FurnitureContactDialog
import com.aerospring.arworld.feature.furniture.model.FurnitureDownloadState
import com.aerospring.arworld.feature.furniture.model.FurnitureGlbDownloader
import com.aerospring.arworld.feature.furniture.model.GlbBounds
import com.aerospring.arworld.feature.furniture.model.GlbBoundsReader
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.hypot

private const val TAP_TOLERANCE_DP = 70f

/** На сколько кружок выбора приподнят над полом / отодвинут от стены (м). Модель не сдвигается. */
private const val MARKER_LIFT_M = 0.015f

/**
 * Свой материал перекрытия по глубине (лежит в assets/materials модуля feature/furniture).
 * Это материал SceneView 4.34.0 + «допуск»: реальный предмет прячет модель, только если он
 * ближе к камере больше чем на «допуск» (см. OCCLUSION_BIAS_PRESETS) × расстояние.
 * Так пол и стена, на которых стоит/висит модель, перестают «грызть» её края из-за шума глубины.
 * Скомпилирован matc 1.72.1 (версия Filament внутри SceneView 4.34.0). При обновлении
 * SceneView материал нужно пересобрать под её версию Filament!
 */
private const val OCCLUSION_MATERIAL_FILE = "materials/furniture_camera_stream_depth.filamat"
/**
 * Пресеты допуска (м, м на метр расстояния). Компромисс: больше допуск — пол/стена меньше «грызут»
 * модель; меньше допуск — точнее прячет за близкими реальными предметами (стул вплотную к стулу).
 * В режиме диагностики долгое нажатие на кнопку «слои» перебирает пресеты (для подбора на месте).
 * По умолчанию — индекс OCCLUSION_BIAS_DEFAULT.
 */
private val OCCLUSION_BIAS_PRESETS = listOf(
    0.00f to 0.00f,   // как в оригинальном SceneView
    0.02f to 0.01f,
    0.03f to 0.02f,
    0.05f to 0.03f,   // был по умолчанию до 09.10 12:48
)
private const val OCCLUSION_BIAS_DEFAULT = 3

/** Во сколько раз служебные долгие нажатия длиннее обычного (обычное ≈ 0,4–0,5 с). */
private const val SERVICE_LONG_PRESS_FACTOR = 2L

/**
 * Начальное значение режима диагностики раздела «AR.Мебель» (при каждом входе в раздел).
 * Во время работы включается/выключается долгим нажатием на счётчик «Пол/Стены».
 *
 * false (для пользователей): не рисуется ни один диагностический текст (белая и жёлтая строки,
 * голубой журнал событий), не считаются «живые» значения узлов, fps, детектор скачков JUMP и
 * журнал; расчёты и записи строк в журнал выключены полностью.
 * true (для отладки и тестов): всё это включено — можно снимать экран и видео, как раньше.
 *
 * Рабочая логика (пересоздание «невидимок», показ модели после проверки, лечение залипания узлов,
 * красная подсветка «кто мешает», счётчик плоскостей «Пол/Стены») от флага НЕ зависит.
 */
private const val FURNITURE_DEBUG_DEFAULT = false

/** Ключ для запоминания «справка по AR-сцене мебели уже показана». */
private const val HELP_SECTION_KEY = "ar_furniture_scene"

/** Краткая справка для дизайнера — содержимое окна по кнопке «i». */
private val furnitureHelpItems = listOf(
    HelpItem(
        marker = "🔄",
        title = "Осмотри комнату",
        text = "Медленно поводи камерой по полу и стенам. Счётчик «Пол / Стены» слева вверху " +
                "показывает, сколько поверхностей найдено. Сетка рисуется только на полу — " +
                "готовность стены видна по счётчику «Стены»."
    ),
    HelpItem(
        marker = "🪑",
        title = "Поставь модель",
        text = "Наведи центр экрана на пол или стену и нажми миниатюру внизу. " +
                "Пока модель загружается, остальные миниатюры недоступны."
    ),
    HelpItem(
        marker = "👆",
        title = "Двигай и поворачивай",
        text = "Нажми на модель — под ней появится голубой круг. Тяни одним пальцем — " +
                "переместить, двумя пальцами — повернуть. Выбор снимается нажатием на пустое место."
    ),
    HelpItem(
        marker = "✨",
        title = "Мебель стоит в комнате, а не поверх неё",
        text = "Значок «слои» вверху включает перекрытие: настоящие предметы начинают закрывать " +
                "модель. Поставь стул за реальный диван — и диван честно спрячет его часть, " +
                "как в жизни. Клиент видит не картинку поверх камеры, а мебель в своей комнате. " +
                "После включения пару секунд поводи камерой. Значок появляется только на " +
                "телефонах, которые это умеют.\n" +
                "❗ Лучше всего работает, когда предмет заметно отличается по цвету от пола и стен: " +
                "бежевое кресло на бежевом полу телефон различает хуже."
    ),
    HelpItem(
        marker = "🗑️",
        title = "Удалить",
        text = "Долгое нажатие на модель открывает кнопку «Удалить»."
    ),
    HelpItem(
        marker = "🚫",
        title = "Модели не пересекаются",
        text = "Если модель упирается в другую, мешающая подсвечивается красным. " +
                "Отодвинь её или поверни свою."
    ),
    HelpItem(
        marker = "📏",
        title = "Проверка масштаба",
        text = "Значок линейки: наведи перекрестие на начало и конец известного размера " +
                "(плитка, дверной проём, рулетка), введи реальный размер. " +
                "Отклонение до ±2% — модели показаны в реальном размере."
    ),
    HelpItem(
        marker = "⚠️",
        title = "Жёлтая плашка",
        text = "Камере мало ориентиров (однотонные пол и стены) — масштаб может быть неточным. " +
                "Поводи камерой, захватывая углы, плинтусы, двери, предметы."
    ),
    HelpItem(
        marker = "▦",
        title = "Сетка",
        text = "Кнопка сетки вверху скрывает её — так клиенту видна чистая картинка."
    )
)

/** Ключ «справка для гостя уже показана» — отдельный от дизайнерского. */
private const val GUEST_HELP_SECTION_KEY = "ar_furniture_scene_guest"

/** Справка для гостя (вход без авторизации, витрина). */
private val furnitureGuestHelpItems = listOf(
    HelpItem(
        marker = "🛋️",
        title = "Что это",
        text = "Мебель реальных мебельных компаний — прямо в вашей комнате, в натуральную величину. " +
                "Расставьте её, посмотрите со всех сторон и закажите понравившуюся."
    ),
    HelpItem(
        marker = "🔄",
        title = "Осмотрите комнату",
        text = "Медленно поводите камерой по полу и стенам. Счётчик «Пол / Стены» слева вверху " +
                "показывает, сколько поверхностей найдено."
    ),
    HelpItem(
        marker = "🏷️",
        title = "Выберите тип мебели",
        text = "Кнопки над миниатюрами — кухни, шкафы, столы и так далее. «Все» показывает всё."
    ),
    HelpItem(
        marker = "🪑",
        title = "Поставьте модель",
        text = "Наведите центр экрана на пол или стену и нажмите миниатюру внизу. " +
                "Большие модели загружаются дольше — дождитесь окончания."
    ),
    HelpItem(
        marker = "👆",
        title = "Двигайте и поворачивайте",
        text = "Нажмите на модель — под ней появится голубой круг. Тяните одним пальцем — переместить, " +
                "двумя пальцами — повернуть. Долгое нажатие — удалить. Модели не заходят друг в друга."
    ),
    HelpItem(
        marker = "✨",
        title = "Мебель стоит в комнате, а не поверх неё",
        text = "Нажмите значок «слои» вверху — и настоящие предметы начнут закрывать мебель. " +
                "Поставьте стул за свой диван: диван спрячет его часть, как в жизни. " +
                "После включения пару секунд поводите камерой. Значок есть только на телефонах, " +
                "которые это умеют.\n" +
                "❗ Лучше всего работает, когда предмет заметно отличается по цвету от пола и стен."
    ),
    HelpItem(
        marker = "💬",
        title = "Хочу такую",
        text = "Выберите модель и нажмите «Хочу такую» — откроются контакты компании и код модели. " +
                "Позвоните или напишите сами и назовите код. Приложение ваших данных не собирает."
    ),
    HelpItem(
        marker = "⚠️",
        title = "Жёлтая плашка",
        text = "Камере мало ориентиров (однотонные пол и стены) — размер может быть неточным. " +
                "Поводите камерой, захватывая углы, двери, предметы."
    ),
    HelpItem(
        marker = "🔑",
        title = "Вы дизайнер или руководитель?",
        text = "Значок входа вверху ведёт на вход для дизайнеров и руководителей мебельных компаний."
    )
)

/**
 * Порог «слабого трекинга» по числу точек-ориентиров ARCore в кадре (облако точек, сглаженное).
 * Однотонные пол и стены дают мало точек — тогда ARCore хуже держит масштаб и положение.
 * Значения — первое приближение: в режиме диагностики число точек видно рядом со счётчиком
 * «Пол/Стены», по видео их можно подстроить. Гистерезис (ON < OFF), чтобы плашка не мигала.
 */
private const val WEAK_TRACKING_POINTS_ON = 20f
private const val WEAK_TRACKING_POINTS_OFF = 30f

/** Состояние наблюдения за качеством отслеживания (обновляется в onSessionUpdated). */
private class TrackingMonitor {
    var frames = 0
    var smoothPoints = -1f
    var lowSinceMs = 0L
    var highSinceMs = 0L
    var weak = false
    var everTracked = false
    var lastLostMs = 0L
}

private fun distanceBetween(a: Position, b: Position): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

private data class LoadedModel(val instance: ModelInstance, val bounds: GlbBounds?)

/** Реальные габариты (полуразмеры) и смещение, переносящее опорную точку в центр основания
 *  (пол) / в центр в плоскости стены (стена). */
private data class ModelFootprint(
    val halfX: Float,
    val halfY: Float,
    val halfZ: Float,
    val modelOffset: Position,
)

private fun wrap180(a: Float): Float {
    var x = a % 360f
    if (x > 180f) x -= 360f
    if (x < -180f) x += 360f
    return x
}

/** Yaw из Euler-углов (градусы) узла, повёрнутого только вокруг Y.
 *  При |x| > 90 это тот же поворот в другой записи: (x-180, 180-y, z-180). */
private fun yawOfEuler(rx: Float, ry: Float): Float =
    if (kotlin.math.abs(wrap180(rx)) > 90f) wrap180(180f - ry) else wrap180(ry)

/**
 * Ручки к трём узлам одной поставленной модели: внешний (позиция + yaw), средний (наклон) и
 * ModelNode (смещение центрирования). *Sync сверяет фактические значения узла с тем, что должно быть
 * по состоянию, и при расхождении записывает правильные; возвращает описание исправления либо null.
 * *Push "дёргает" isVisible выкл/вкл.
 */
private const val PUSH_WINDOW_MS = 3_000L

private class ModelNodeHandles {
    var outerSync: ((Position, Float) -> String?)? = null
    var midSync: ((Float) -> String?)? = null
    var modelSync: ((Position) -> String?)? = null
    var outerPush: (() -> Unit)? = null
    var midPush: (() -> Unit)? = null
    var modelPush: (() -> Unit)? = null
    // Мировой поворот (yaw) ModelNode по данным SceneView/Filament — для обнаружения «залипшего» узла.
    var modelWorldYaw: (() -> Float)? = null
    // Принудительно переписать все три узла на ближайшем кадре синхронизации.
    var forceNext = false
    var staleFrames = 0
    // Мировая «верхняя» ось модели (компонента Y): для проверки наклона на стене.
    var modelWorldUpY: (() -> Float)? = null
    // «Встряска сцены» для этого эпизода залипания уже выполнена.
    var poked = false
    // ВРЕМЕННО: описание «рождения» узла (мировая позиция, видимость) для журнала.
    var modelBirthInfo: (() -> String)? = null
    var modelWorldPos: (() -> Position)? = null
    var modelWorldScale: (() -> Float)? = null
    var scaleFrames = 0
    var scalePoked = false
    var lastForceMs = 0L
    var nudgeSign = 1f
    var garbageFrames = 0
    // Для детектора «скачков» мировой трансформации при неизменном состоянии (диагностика).
    var lastWorld: FloatArray? = null
    var lastStateKey = Float.NaN
    var lastJumpLogMs = 0L
    // Мировая ось Z модели (нормаль «от стены»/направление по полу): не зависит от наклона tilt.
    var modelWorldZ: (() -> FloatArray)? = null
    // «Показать после проверки»: модель рождается скрытой и показывается, когда мировое положение и
    // разворот совпали с состоянием (или по таймауту).
    var revealed = false
    var okFrames = 0
    var modelSetVisible: ((Boolean) -> Unit)? = null
}

@Composable
fun FurnitureSceneScreen(
    models: List<FurnitureModel>,
    onLogoutClick: () -> Unit,
    onBackClick: () -> Unit,
    // Гостевой режим (витрина). null — режим дизайнера, всё работает ровно как раньше.
    // В гостевом: кнопка «Выйти» становится «Вход для дизайнеров и руководителей» (onLogoutClick),
    // чипы типов над каруселью, «Хочу такую» в плашке выбора, своя справка.
    guestShowcase: FurnitureShowcase? = null,
) {
    val context = LocalContext.current
    val isGuest = guestShowcase != null
    val helpKey = if (isGuest) GUEST_HELP_SECTION_KEY else HELP_SECTION_KEY
    val helpItems = if (isGuest) furnitureGuestHelpItems else furnitureHelpItems
    // Гость: выбранный тип мебели (null — «Все») и код модели для окна «Хочу такую».
    var guestTypeFilter by remember { mutableStateOf<String?>(null) }
    var contactCode by remember { mutableStateOf<String?>(null) }
    // Гость: анонимная статистика витрины. Каждое событие по каждой модели — один раз
    // за заход в сцену (новый заход — новый набор). У дизайнера и руководителя не вызывается.
    val reportedShowcaseEvents = remember { mutableSetOf<String>() }
    fun reportShowcaseEvent(code: String, event: String) {
        if (!isGuest) return
        if (reportedShowcaseEvents.add("$event:$code")) FurnitureShowcaseStatsRepository.report(code, event)
    }
    // Руководитель: модели всех его дизайнеров приходят с designerId/designerName.
    // У дизайнера и гостя этих полей нет — чипов дизайнеров нет, фильтр всегда null.
    var designerFilter by remember { mutableStateOf<String?>(null) }
    val designerChips = remember(models, isGuest) {
        if (isGuest) emptyList()
        else models
            .mapNotNull { m ->
                m.designerId?.let { id -> id to (m.designerName?.takeIf { it.isNotBlank() } ?: "Без имени") }
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    val showDesignerChips = designerChips.size > 1
    val carouselModels = remember(models, guestShowcase, guestTypeFilter, designerFilter) {
        val filter = guestTypeFilter
        val byType = if (guestShowcase == null || filter == null) models
        else models.filter { guestShowcase.modelByCode(it.modelId)?.furnitureType == filter }
        val designer = designerFilter
        if (designer == null) byType else byType.filter { it.designerId == designer }
    }
    // Над каруселью ряд чипов (гость — типы, руководитель — дизайнеры): плашки снизу поднимаем выше него.
    val chipTypes = guestShowcase?.types.orEmpty()
    val showTypeChips = chipTypes.size > 1
    val bottomPillPadding = if (showTypeChips || showDesignerChips) 168.dp else 120.dp
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Пункт 5, шаг Б: свой поток камеры, чтобы включать/выключать перекрытие по глубине
    // (ARCameraStream.isDepthOcclusionEnabled). По умолчанию — то же, что создаёт ARSceneView сам.
    val cameraStream = rememberARCameraStream(materialLoader) {
        ARCameraStream(materialLoader, depthOcclusionMaterialFile = OCCLUSION_MATERIAL_FILE).apply {
            // Допуск задаём сразу и прямо материалу глубины (он наш, параметры в нём точно есть).
            val (biasM, biasPerM) = OCCLUSION_BIAS_PRESETS[OCCLUSION_BIAS_DEFAULT]
            depthOcclusionMaterial.defaultInstance.apply {
                setParameter("occlusionBiasMeters", biasM)
                setParameter("occlusionBiasPerMeter", biasPerM)
            }
        }
    }
    val modelLoadDispatcher = remember { Dispatchers.IO.limitedParallelism(3) }

    // Справка «i»: автопоказ один раз при первом входе на AR-сцену, дальше — только по кнопке.
    // Флаг «показано» ставим сразу в момент показа (как в других разделах).
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!SectionHelpPrefs.wasShown(context, helpKey)) {
            showHelp = true
            SectionHelpPrefs.markShown(context, helpKey)
        }
    }

    var pendingModel by remember { mutableStateOf<FurnitureModel?>(null) }
    var loadingModelId by remember { mutableStateOf<String?>(null) }
    var loadProgressText by remember { mutableStateOf("") }
    var currentFrame by remember { mutableStateOf<Frame?>(null) }
    var currentSession by remember { mutableStateOf<Session?>(null) }
    var placedModels by remember { mutableStateOf<List<PlacedModel>>(emptyList()) }
    // Гостю диагностика недоступна совсем (решение 09.10): ни при старте, ни долгим нажатием.
    var debugOn by remember { mutableStateOf(FURNITURE_DEBUG_DEFAULT && !isGuest) }
    // Пункт 5, шаг А: только ПРОВЕРКА поддержки ARCore Depth API (глубина НЕ включается,
    // конфигурация сессии не меняется). Показывается в диагностике под счётчиком «Пол/Стены».
    var depthSupportText by remember { mutableStateOf("Глубина: проверка…") }
    // Шаг Б: кнопка «Прятать за реальными предметами» показывается, только если устройство
    // поддерживает DepthMode.AUTOMATIC (всем, включая гостей витрины).
    // По умолчанию ВЫКЛЮЧЕНО: глубина не считается, сцена работает ровно как раньше.
    var depthSupported by remember { mutableStateOf(false) }
    var occlusionOn by remember { mutableStateOf(false) }
    var occlusionBiasIndex by remember { mutableStateOf(OCCLUSION_BIAS_DEFAULT) }
    // Диагностика глубины (только debugOn + перекрытие ВКЛ): в ЦЕНТРЕ экрана сравниваем
    // расстояние по карте глубины ARCore и расстояние до найденной плоскости (пол/стена).
    // Разница = насколько карта глубины «выпирает» из поверхности, на которой стоит модель.
    var depthProbeLine by remember { mutableStateOf("") }
    val depthProbeFrame = remember { IntArray(1) }
    var selectedInstanceId by remember { mutableStateOf<String?>(null) }
    // Модель, которая мешает перетаскиваемой: подсвечивается красным кружком ~3 с.
    var blockerHighlightId by remember { mutableStateOf<String?>(null) }
    var deleteMenuInstanceId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var viewportWidthPx by remember { mutableStateOf(0) }
    var viewportHeightPx by remember { mutableStateOf(0) }
    var touchDownPosition by remember { mutableStateOf(Offset.Zero) }
    var touchMoved by remember { mutableStateOf(false) }
    var isTwoFingerGesture by remember { mutableStateOf(false) }
    // Был ли в текущем жесте хоть один момент с двумя пальцами. Если да — оставшийся палец после
    // отпускания второго НЕ двигает модель и НЕ снимает выделение (см. ACTION_UP / ACTION_MOVE).
    var gestureHadMultiTouch by remember { mutableStateOf(false) }
    var lastTwoFingerAngle by remember { mutableStateOf(0f) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressTriggered by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var horizontalPlaneCount by remember { mutableStateOf(0) }
    var verticalPlaneCount by remember { mutableStateOf(0) }
    var settledInstanceIds by remember { mutableStateOf(setOf<String>()) }

    // Предупреждение о слабом трекинге: текст плашки (null — всё хорошо).
    val trackingMonitor = remember { TrackingMonitor() }
    var trackingHint by remember { mutableStateOf<String?>(null) }
    var trackingPointsShown by remember { mutableStateOf(-1) }

    // Проверка масштаба («рулетка»): две точки по перекрестию в центре экрана + реальный размер.
    // В этом режиме жесты над моделями отключены, карусель скрыта; выделение модели НЕ сбрасывается.
    var measureMode by remember { mutableStateOf(false) }
    var measurePointA by remember { mutableStateOf<Position?>(null) }
    var measurePointB by remember { mutableStateOf<Position?>(null) }
    var measureLive by remember { mutableStateOf<Position?>(null) }
    var measureRealCmText by remember { mutableStateOf("") }
    var measureVerdict by remember { mutableStateOf<String?>(null) }

    val instanceCache = remember { mutableMapOf<String, ModelInstance>() }
    val footprintCache = remember { mutableMapOf<String, ModelFootprint>() }
    val nodeProbes = remember { mutableMapOf<String, () -> String>() }
    // ВРЕМЕННО: «живое» сравнение состояния модели с тем, что SceneView/Filament считает мировым
    // положением и поворотом её узла (первая строка голубого журнала).
    val nodeLive = remember { mutableMapOf<String, (PlacedModel) -> String>() }
    val gestureActive = remember { BooleanArray(1) }
    // «Невидимка при рождении»: через PUSH_WINDOW_MS после постановки мировая позиция узла
    // оказывается далеко от состояния (по видео 21:07 и 21:23 это всегда совпадало с пропавшей
    // моделью). Тогда модель пересоздаётся на том же месте (не более 2 раз подряд).
    val respawnQueue = remember { mutableListOf<PlacedModel>() }
    // Счётчики за сессию: [0] — постановки пользователем, [1] — пересоздания «невидимок/залипших».
    val sessionCounters = remember { IntArray(2) }
    val respawnLineage = remember { mutableMapOf<String, Int>() }
    val respawnAction = remember { arrayOfNulls<(PlacedModel) -> Unit>(1) }
    val flushedIds = remember { mutableSetOf<String>() }
    val earlyFlushedIds = remember { mutableSetOf<String>() }
    val handles = remember { mutableMapOf<String, ModelNodeHandles>() }
    val placedAtMs = remember { mutableMapOf<String, Long>() }
    // Кружок выделения: «подталкивание» после создания (как у моделей) + метка, чтобы старый
    // (уже удалённый) кружок не получал подталкиваний.
    val ringPush = remember { mutableMapOf<String, Pair<Any, () -> Unit>>() }
    val ringBornMs = remember { mutableMapOf<String, Long>() }
    val lastNoHitLogMs = remember { LongArray(3) }
    val syncFixCount = remember { IntArray(1) }
    val syncLastInfo = remember { arrayOfNulls<String>(1) }
    var healCount by remember { mutableStateOf(0) }
    var healLine by remember { mutableStateOf("") }
    var debugLine by remember { mutableStateOf("") }
    // Диагностика (только при debugOn): журнал событий (касания, перемещения, повороты,
    // исправления синхронизатора) с метками времени — виден на видео экрана.
    val eventLog = remember { ArrayDeque<String>() }
    val eventLogDirty = remember { BooleanArray(1) }
    val rotLogCount = remember { IntArray(1) }
    val moveLogCount = remember { IntArray(1) }
    val lastFixLogMs = remember { LongArray(1) }
    var eventLogText by remember { mutableStateOf("") }
    fun logEvent(text: String) {
        if (!debugOn) return
        val t = System.currentTimeMillis() % 100_000L
        synchronized(eventLog) {
            eventLog.addLast("%02d.%03d %s".format(t / 1000, t % 1000, text))
            while (eventLog.size > 9) eventLog.removeFirst()
        }
        eventLogDirty[0] = true
    }
    val lastFrameTs = remember { LongArray(1) }
    val smoothFps = remember { FloatArray(1) }
    /*
    val isDarkTheme = isSystemInDarkTheme()
    val selectionColor = if (isDarkTheme) Color.Black else Color.White
    val selectionMaterial = remember(materialLoader, isDarkTheme) {
        materialLoader.createUnlitColorInstance(selectionColor.copy(alpha = 0.28f))
    }
    */
    val selectionMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0xFF29A8FF).copy(alpha = 0.40f))
    }
    val blockerMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0xFFFF3B30).copy(alpha = 0.45f))
    }
    // Те же цвета для кружков у моделей НА СТЕНЕ: там кружок стоит перед стеной и при отключённой
    // проверке глубины закрашивал бы саму модель, поэтому стенные кружки всегда с проверкой глубины.
    val selectionWallMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0xFF29A8FF).copy(alpha = 0.40f))
    }
    val blockerWallMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0xFFFF3B30).copy(alpha = 0.45f))
    }

    val tapTolerancePx = with(density) { TAP_TOLERANCE_DP.dp.toPx() }

    // Конфигурация жестов с удвоенным временем долгого нажатия — для служебных долгих нажатий
    // (диагностика, перебор допуска глубины), чтобы их нельзя было задеть случайно.
    val baseViewConfig = LocalViewConfiguration.current
    val slowLongPressConfig = remember(baseViewConfig) {
        object : ViewConfiguration by baseViewConfig {
            override val longPressTimeoutMillis: Long
                get() = baseViewConfig.longPressTimeoutMillis * SERVICE_LONG_PRESS_FACTOR
        }
    }

    /** Позиция кружка выбора: чуть над полом / чуть впереди стены (по нормали стены),
     *  чтобы кружок не сливался с поверхностью. Модель при этом не сдвигается. */
    fun markerPositionOf(placed: PlacedModel): Position {
        val p = placed.position
        return when (placed.surfaceType) {
            SurfaceType.FLOOR -> Position(p.x, p.y + MARKER_LIFT_M, p.z)
            SurfaceType.WALL -> {
                val yawRad = Math.toRadians(placed.rotationYDegrees.toDouble())
                Position(
                    p.x + (kotlin.math.sin(yawRad) * MARKER_LIFT_M).toFloat(),
                    p.y,
                    p.z + (kotlin.math.cos(yawRad) * MARKER_LIFT_M).toFloat(),
                )
            }
        }
    }

    LaunchedEffect(blockerHighlightId) {
        if (blockerHighlightId != null) {
            delay(3000)
            blockerHighlightId = null
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(3000)
            statusMessage = null
        }
    }

    LaunchedEffect(placedModels.map { it.instanceId }) {
        for (placed in placedModels) {
            if (placed.instanceId !in settledInstanceIds) {
                delay(100)
                settledInstanceIds = settledInstanceIds + placed.instanceId
            }
        }
    }

    // ТИКЕР. Раз в 0,3 с:
    // 1) "подталкивает" только что созданные узлы (первые 4 с): дёргает isVisible выкл/вкл уже ПОСЛЕ
    //    присоединения к сцене (известная особенность SceneView: состояние нового узла не доходит до
    //    Filament, пока свойство не переприсвоено; разовый вызов в apply{} успевает не всегда);
    // 2) переносит статистику покадровой синхронизации в жёлтую строку (чтобы не перерисовывать
    //    экран на каждый кадр).
    LaunchedEffect(Unit) {
        var shownCount = 0
        while (true) {
            delay(300)
            val now = System.currentTimeMillis()
            for (placed in placedModels) {
                val born = placedAtMs[placed.instanceId] ?: continue
                val h = handles[placed.instanceId] ?: continue
                if (now - born < PUSH_WINDOW_MS) {
                    // Подталкивание (isVisible выкл/вкл) НЕ выполняем во время жеста: по видео
                    // (16:44) запись поворота, сделанная в это окно, терялась — состояние уже 61°,
                    // а мировой поворот узла оставался 0° несколько секунд.
                    if (!gestureActive[0]) {
                        h.outerPush?.invoke()
                        h.midPush?.invoke()
                        h.modelPush?.invoke()
                    }
                    // Ранняя «перезапись набело» на 1,5 с: по видео (21:45) залипший поворот лечится
                    // именно ею, поэтому ждать конца окна подталкивания (3 с) не нужно.
                    if (now - born >= 1_500L && earlyFlushedIds.add(placed.instanceId)) {
                        h.forceNext = true
                    }
                } else if (flushedIds.add(placed.instanceId)) {
                    // Окно подталкивания закончилось — один раз переписываем все узлы набело.
                    h.forceNext = true
                    if (debugOn) {
                        val birth = h.modelBirthInfo?.invoke() ?: "нет узла"
                        logEvent(
                            "BIRTH state(%.2f,%.2f,%.2f) %s".format(
                                placed.position.x, placed.position.y, placed.position.z, birth,
                            ),
                        )
                    }
                    val fp = footprintCache[placed.instanceId]
                    val wp = h.modelWorldPos?.invoke()
                    if (fp != null && wp != null) {
                        val diag = kotlin.math.sqrt(fp.halfX * fp.halfX + fp.halfY * fp.halfY + fp.halfZ * fp.halfZ)
                        val dx = wp.x - placed.position.x
                        val dy = wp.y - placed.position.y
                        val dz = wp.z - placed.position.z
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                        if (dist > diag + 0.25f && (respawnLineage[placed.instanceId] ?: 0) < 3) {
                            logEvent("LOST dist=%.2f > %.2f -> пересоздаю".format(dist, diag + 0.25f))
                            respawnQueue.add(placed)
                        }
                    }
                }
            }
            for (placed in placedModels) {
                val ringBorn = ringBornMs[placed.instanceId] ?: continue
                if (now - ringBorn < 3_000L) ringPush[placed.instanceId]?.second?.invoke()
            }
            for (placed in placedModels) {
                val blockKey = "B:" + placed.instanceId
                val blockBorn = ringBornMs[blockKey] ?: continue
                if (now - blockBorn < 2_000L) ringPush[blockKey]?.second?.invoke()
            }
            if (respawnQueue.isNotEmpty() && loadingModelId == null) {
                val lost = respawnQueue.removeAt(0)
                respawnAction[0]?.invoke(lost)
            }
            if (debugOn) {
                eventLogDirty[0] = false
                val liveText = placedModels.find { it.instanceId == selectedInstanceId }
                    ?.let { nodeLive[it.instanceId]?.invoke(it) } ?: ""
                val joined = synchronized(eventLog) { eventLog.joinToString("\n") }
                val statsLine = "placed=%d respawned=%d".format(sessionCounters[0], sessionCounters[1])
                eventLogText = statsLine + (if (liveText.isEmpty()) "" else "\n" + liveText) + "\n" + joined
                if (syncFixCount[0] != shownCount) {
                    shownCount = syncFixCount[0]
                    healCount = shownCount
                    healLine = syncLastInfo[0] ?: ""
                }
            }
        }
    }

    suspend fun loadFreshInstance(model: FurnitureModel): LoadedModel? {
        val fullUrl = "${ArWorldServerConfig.BASE_URL}${model.url}"
        var localFile: File? = null
        FurnitureGlbDownloader.download(context, fullUrl).collect { state ->
            when (state) {
                is FurnitureDownloadState.Progress -> {
                    loadProgressText = if (state.isMegabytes) {
                        "Скачивание: ${state.percent} МБ"
                    } else {
                        "Скачивание: ${state.percent}%"
                    }
                }
                is FurnitureDownloadState.Done -> localFile = state.localFile
                is FurnitureDownloadState.Error -> {
                    statusMessage = "Не удалось скачать модель: ${state.message}"
                }
            }
        }
        loadProgressText = "Подготовка модели…"

        // Реальные габариты из самого файла (asset.boundingBox их искажает, см. GlbBoundsReader)
        val bounds = localFile?.let { file ->
            withContext(Dispatchers.IO) { GlbBoundsReader.read(file) }
        }

        val instance = try {
            withTimeout(45_000) {
                withContext(modelLoadDispatcher) {
                    suspendCancellableCoroutine<ModelInstance?> { continuation ->
                        modelLoader.loadModelInstanceAsync(fullUrl) { loaded ->
                            if (continuation.isActive) {
                                continuation.resume(loaded)
                            }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            statusMessage = "Таймаут загрузки модели (45с)"
            null
        }
        return if (instance != null) LoadedModel(instance, bounds) else null
    }

    fun floorReferenceY(): Float? {
        val session = currentSession ?: return null
        // Пол = самая НИЗКАЯ среди КРУПНЫХ горизонтальных плоскостей. Раньше брали просто самую
        // низкую: при естественном свете на глянцевой плитке ARCore иногда строит мелкие
        // «призрачные» плоскости ниже пола (отражения), и настоящий пол отвергался
        // («Не вижу поверхность»), а модели садились мимо.
        val planes = session.getAllTrackables(Plane::class.java).filter {
            it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    it.trackingState == TrackingState.TRACKING &&
                    it.subsumedBy == null
        }
        if (planes.isEmpty()) return null
        // Группируем плоскости по высоте (в пределах 12 см) и берём группу с наибольшей суммарной
        // площадью. По видео (21:19): при плоскостях -1.25 (7 м², 7 м²), -1.67 (4 м²), -1.83 (1 м²)
        // правило «самая низкая из крупных» выбирало -1.67, и модели вставали на 40 см НИЖЕ пола.
        val sorted = planes.sortedBy { it.centerPose.ty() }
        var bestY = sorted[0].centerPose.ty()
        var bestArea = -1f
        var i = 0
        while (i < sorted.size) {
            val y0 = sorted[i].centerPose.ty()
            var j = i
            var area = 0f
            var weightedY = 0f
            while (j < sorted.size && sorted[j].centerPose.ty() - y0 < 0.12f) {
                val a = sorted[j].extentX * sorted[j].extentZ
                area += a
                weightedY += a * sorted[j].centerPose.ty()
                j++
            }
            if (area > bestArea) {
                bestArea = area
                bestY = if (area > 0f) weightedY / area else y0
            }
            i = j
        }
        return bestY
    }

    fun describePlanes(): String {
        val session = currentSession ?: return "нет сессии"
        val planes = session.getAllTrackables(Plane::class.java)
            .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
            .sortedByDescending { it.extentX * it.extentZ }
            .take(5)
        val floor = floorReferenceY()?.let { "%.2f".format(it) } ?: "-"
        return "floorY=%s [%s]".format(floor, planes.joinToString(" ") { "%.2f(%.1f)".format(it.centerPose.ty(), it.extentX * it.extentZ) })
    }

    fun logNoHit(tag: String) {
        if (!debugOn) return
        val now = System.currentTimeMillis()
        if (now - lastNoHitLogMs[0] > 700L) {
            lastNoHitLogMs[0] = now
            logEvent("NOHIT %s %s".format(tag, describePlanes()))
        }
    }

    /**
     * Оценка качества отслеживания на каждом кадре. Только читает данные ARCore и меняет текст
     * плашки (Compose-состояние пишется лишь при изменении текста). Сцену и модели не трогает.
     */
    fun updateTrackingHint(frame: Frame) {
        val m = trackingMonitor
        val now = System.currentTimeMillis()
        val camera = frame.camera
        val text: String?
        if (camera.trackingState != TrackingState.TRACKING) {
            if (m.everTracked) m.lastLostMs = now
            m.lowSinceMs = 0L
            m.highSinceMs = 0L
            text = when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_FEATURES ->
                    "Камере не за что зацепиться: однотонные пол и стены. Наведи на угол, плинтус, дверь или предмет"
                TrackingFailureReason.EXCESSIVE_MOTION ->
                    "Слишком быстро — веди камеру медленнее"
                TrackingFailureReason.INSUFFICIENT_LIGHT ->
                    "Слишком темно — включи свет"
                TrackingFailureReason.BAD_STATE, TrackingFailureReason.CAMERA_UNAVAILABLE ->
                    "Отслеживание сбилось — подожди пару секунд, не закрывая камеру"
                else -> if (m.everTracked) {
                    "Отслеживание потеряно — медленно поводи камерой"
                } else {
                    "Медленно поводи камерой по полу и стенам, чтобы приложение «увидело» комнату"
                }
            }
        } else {
            m.everTracked = true
            m.frames += 1
            // Облако точек — раз в 10 кадров (~3 раза в секунду), обязательно освобождаем.
            if (m.frames % 10 == 0) {
                val count = try {
                    val cloud = frame.acquirePointCloud()
                    try {
                        cloud.points.remaining() / 4
                    } finally {
                        cloud.release()
                    }
                } catch (e: Exception) {
                    -1
                }
                if (count >= 0) {
                    m.smoothPoints = if (m.smoothPoints < 0f) count.toFloat() else m.smoothPoints * 0.7f + count * 0.3f
                    if (debugOn) trackingPointsShown = m.smoothPoints.toInt()
                }
            }
            if (m.smoothPoints >= 0f) {
                when {
                    m.smoothPoints < WEAK_TRACKING_POINTS_ON -> {
                        if (m.lowSinceMs == 0L) m.lowSinceMs = now
                        m.highSinceMs = 0L
                    }
                    m.smoothPoints > WEAK_TRACKING_POINTS_OFF -> {
                        if (m.highSinceMs == 0L) m.highSinceMs = now
                        m.lowSinceMs = 0L
                    }
                    else -> {
                        m.lowSinceMs = 0L
                        m.highSinceMs = 0L
                    }
                }
                if (!m.weak && m.lowSinceMs != 0L && now - m.lowSinceMs > 2_000L) m.weak = true
                if (m.weak && m.highSinceMs != 0L && now - m.highSinceMs > 1_500L) m.weak = false
            }
            val recentlyLost = m.lastLostMs != 0L && now - m.lastLostMs < 4_000L
            text = when {
                m.weak ->
                    "Масштаб может быть неточным: мало ориентиров. Медленно поводи камерой, захватывая углы, плинтусы, двери, предметы"
                recentlyLost ->
                    "Отслеживание восстановилось — масштаб может быть неточным, поводи камерой по комнате"
                else -> null
            }
        }
        if (trackingHint != text) trackingHint = text
    }

    /** Точка для «рулетки»: ближайшее попадание в плоскость (внутри её контура) или в точку-ориентир
     *  с оценённой нормалью. Нужна, чтобы мерить и на полу, и по дверному проёму. */
    fun measureHitAt(xPx: Float, yPx: Float): Position? {
        val frame = currentFrame ?: return null
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        for (result in frame.hitTest(xPx, yPx)) {
            val trackable = result.trackable
            val ok = (trackable is Plane && trackable.isPoseInPolygon(result.hitPose)) ||
                    (trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
            if (ok) {
                val pose = result.hitPose
                return Position(pose.tx(), pose.ty(), pose.tz())
            }
        }
        return null
    }

    fun resetMeasurement() {
        measurePointA = null
        measurePointB = null
        measureLive = null
        measureVerdict = null
    }

    fun setMeasurePoint() {
        val hit = measureLive ?: measureHitAt(viewportWidthPx / 2f, viewportHeightPx / 2f)
        // Без попадания кнопка в панели неактивна, а панель сама подсказывает, куда навести.
        if (hit == null) return
        if (measurePointA == null) {
            measurePointA = hit
        } else if (measurePointB == null) {
            measurePointB = hit
            measureVerdict = null
        }
    }

    fun checkMeasurement() {
        val a = measurePointA ?: return
        val b = measurePointB ?: return
        val realCm = measureRealCmText.trim().replace(',', '.').toFloatOrNull()
        if (realCm == null || realCm < 10f || realCm > 1000f) {
            measureVerdict = "Введи реальный размер в сантиметрах (от 10 до 1000)"
            return
        }
        val measuredCm = distanceBetween(a, b) * 100f
        val deviation = (measuredCm - realCm) / realCm * 100f
        val absDev = kotlin.math.abs(deviation)
        val shortNote = if (realCm < 50f) "\nОтрезок короткий — точнее мерить от 1 м." else ""
        measureVerdict = when {
            absDev <= 2f ->
                "Отклонение %+.1f%% — масштаб точный, модели показаны в реальном размере.".format(deviation)
            absDev <= 15f ->
                "Отклонение %+.1f%% — масштаб неточный: модели выглядят примерно на %.0f%% %s реального. Поводи камерой по комнате и перемерь.".format(
                    deviation, absDev, if (deviation > 0f) "меньше" else "больше",
                )
            else ->
                "Отклонение %+.1f%% — слишком большое. Скорее всего точка поставлена мимо или камера потеряла ориентиры. Перемерь.".format(deviation)
        } + shortNote
        logEvent("MEASURE %.1f real %.1f dev %+.1f%%".format(measuredCm, realCm, deviation))
    }

    data class SurfaceHit(val position: Position, val wallYawDegrees: Float?, val surfaceType: SurfaceType)

    // Перетаскивание по стене: допустимое отклонение точки попадания от плоскости текущей стены
    // (по нормали). ARCore при уточнении сдвигает стену на сантиметры; ложная параллельная плоскость
    // за стеной (блик, зеркало, проём) — на десятки см (в чате 9: стул ушёл ~0,5 м вглубь).
    val wallDepthToleranceM = 0.15f
    val wallJumpLogMs = remember { LongArray(1) }

    /**
     * restrictTo — если задан, ищем попадание только по этому типу поверхности
     * (используется при перетаскивании: модель с пола не должна случайно
     * "перескочить" на стену и наоборот).
     *
     * Для пола берём БЛИЖАЙШЕЕ подходящее попадание (frame.hitTest уже
     * сортирует от ближайшего), для стены — наоборот, САМОЕ ДАЛЬНЕЕ из
     * найденных вертикальных: иначе случайная близкая деталь (угол дверного
     * проёма, край мебели) перехватывает размещение раньше настоящей дальней
     * стены, в которую целился пользователь.
     */
    fun surfaceHitAt(
        xPx: Float,
        yPx: Float,
        restrictTo: SurfaceType? = null,
        onlyWallYawDegrees: Float? = null,
        // Только при перетаскивании по стене: точка модели на текущей стене. Попадания в
        // параллельные плоскости, которые глубже/ближе неё больше чем на wallDepthToleranceM,
        // отбрасываются — модель остаётся на месте, палец продолжает тянуть.
        onlyWallNear: Position? = null,
    ): SurfaceHit? {
        val frame = currentFrame ?: return null
        val results = frame.hitTest(xPx, yPx)
        val floorY = floorReferenceY()

        if (restrictTo != SurfaceType.WALL) {
            for (result in results) {
                val trackable = result.trackable
                if (trackable is Plane &&
                    trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    trackable.isPoseInPolygon(result.hitPose)
                ) {
                    val pose = result.hitPose
                    if (floorY == null || kotlin.math.abs(pose.ty() - floorY) < 0.15f) {
                        return SurfaceHit(Position(pose.tx(), pose.ty(), pose.tz()), null, SurfaceType.FLOOR)
                    }
                }
            }
            if (restrictTo == SurfaceType.FLOOR) return null
        }

        fun wallYawOf(pose: com.google.ar.core.Pose): Float {
            val n = pose.rotateVector(floatArrayOf(0f, 1f, 0f))
            return Math.toDegrees(atan2(n[0].toDouble(), n[2].toDouble())).toFloat()
        }

        // При перетаскивании модели на стене допускаем только ту же стену (та же ориентация
        // нормали): иначе при «самом дальнем» попадании модель перескакивает на другую
        // вертикальную плоскость (шкаф, угол) с другой глубиной и прежним разворотом.
        val farthestWallHit = results
            .filter { result ->
                val trackable = result.trackable
                trackable is Plane && trackable.type == Plane.Type.VERTICAL && trackable.isPoseInPolygon(result.hitPose)
            }
            .filter { result ->
                onlyWallYawDegrees == null ||
                        kotlin.math.abs(wrap180(wallYawOf(result.hitPose) - onlyWallYawDegrees)) < 25f
            }
            .let { sameYaw ->
                if (onlyWallYawDegrees == null || onlyWallNear == null) return@let sameYaw
                // Нормаль текущей стены из её yaw: yaw = atan2(nx, nz) -> n = (sin, 0, cos)
                val yawRad = Math.toRadians(onlyWallYawDegrees.toDouble())
                val nx = kotlin.math.sin(yawRad).toFloat()
                val nz = kotlin.math.cos(yawRad).toFloat()
                fun depthOf(result: com.google.ar.core.HitResult): Float {
                    val p = result.hitPose
                    return (p.tx() - onlyWallNear.x) * nx + (p.tz() - onlyWallNear.z) * nz
                }
                val (near, far) = sameYaw.partition { kotlin.math.abs(depthOf(it)) < wallDepthToleranceM }
                if (debugOn && far.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - wallJumpLogMs[0] > 700L) {
                        wallJumpLogMs[0] = now
                        val worst = far.maxByOrNull { kotlin.math.abs(depthOf(it)) }!!
                        logEvent(
                            "WALLJUMP %+.2fm %s".format(
                                depthOf(worst),
                                if (near.isEmpty()) "стоп" else "своя стена есть",
                            ),
                        )
                    }
                }
                near
            }
            .maxByOrNull { it.distance }

        if (farthestWallHit != null) {
            val pose = farthestWallHit.hitPose
            val yaw = wallYawOf(pose)
            return SurfaceHit(Position(pose.tx(), pose.ty(), pose.tz()), yaw, SurfaceType.WALL)
        }
        return null
    }

    fun placePendingModel(
        respawn: PlacedModel? = null,
        respawnModel: FurnitureModel? = null,
        reselect: Boolean = false,
    ) {
        // Пока грузится/садится предыдущая модель — новые нажатия игнорируем
        if (loadingModelId != null) return

        val model = respawnModel ?: pendingModel ?: return
        if (viewportWidthPx == 0 || viewportHeightPx == 0) {
            statusMessage = "Сцена ещё не готова, подожди секунду"
            return
        }

        val surfaceHit = if (respawn != null) {
            SurfaceHit(
                respawn.position,
                if (respawn.surfaceType == SurfaceType.WALL) respawn.rotationYDegrees else null,
                respawn.surfaceType,
            )
        } else {
            surfaceHitAt(viewportWidthPx / 2f, viewportHeightPx / 2f)
        }
        if (surfaceHit == null) {
            logNoHit("place")
            statusMessage = "Не вижу поверхность в этой точке — наведи камеру на пол или стену"
            return
        }

        // Флаг ставим синхронно, ДО launch: иначе два быстрых тапа в одном кадре
        // оба пройдут проверку выше.
        loadingModelId = model.modelId
        loadProgressText = "Подключаюсь…"
        statusMessage = null

        scope.launch {
            try {
                val loaded = loadFreshInstance(model)
                if (loaded == null) {
                    statusMessage = "Не удалось загрузить модель"
                    return@launch
                }
                val instance = loaded.instance
                val bounds = loaded.bounds

                val footprint = if (bounds != null) {
                    ModelFootprint(
                        halfX = bounds.halfX,
                        halfY = bounds.halfY,
                        halfZ = bounds.halfZ,
                        modelOffset = when (surfaceHit.surfaceType) {
                            // Опорная точка -> центр основания: центр по X/Z, низ на полу
                            SurfaceType.FLOOR -> Position(-bounds.centerX, -bounds.minY, -bounds.centerZ)
                            // На стене: центр в плоскости стены, а по глубине модель ставится
                            // ЗАДНЕЙ стороной (minZ, +Z смотрит из стены) ровно на стену. Раньше
                            // глубина не трогалась, и модель с опорой в центре (стул) наполовину
                            // уходила в стену — без глубины это не видно, а режим перекрытия
                            // честно прятал «утонувшую» часть (видео 09.10 12:28: срез сиденья).
                            SurfaceType.WALL -> Position(
                                -bounds.centerX,
                                -bounds.centerY,
                                -(bounds.centerZ - bounds.halfZ),
                            )
                        },
                    )
                } else {
                    // Не удалось прочитать габариты из файла — прежнее поведение
                    val box = instance.asset.boundingBox
                    ModelFootprint(box.halfExtent[0], box.halfExtent[1], box.halfExtent[2], Position(0f, 0f, 0f))
                }
                val footprintRadius = when (surfaceHit.surfaceType) {
                    SurfaceType.FLOOR -> maxOf(footprint.halfX, footprint.halfZ)
                    SurfaceType.WALL -> maxOf(footprint.halfX, footprint.halfY)
                }

                val newInstanceId = UUID.randomUUID().toString()
                val candidate = PlacedModel(
                    instanceId = newInstanceId,
                    modelId = model.modelId,
                    modelName = model.modelName,
                    modelUrl = model.url,
                    position = surfaceHit.position,
                    surfaceType = surfaceHit.surfaceType,
                    rotationYDegrees = respawn?.rotationYDegrees ?: (surfaceHit.wallYawDegrees ?: 0f),
                    tiltDegrees = respawn?.tiltDegrees ?: 0f,
                    footprintRadius = footprintRadius,
                )

                var collides = false
                for (placed in placedModels) {
                    if (respawn == null && placed.overlapsWith(candidate)) {
                        collides = true
                        break
                    }
                }

                if (collides) {
                    statusMessage = "Здесь уже стоит другая модель — наведи камеру левее"
                } else {
                    instanceCache[newInstanceId] = instance
                    footprintCache[newInstanceId] = footprint
                    placedAtMs[newInstanceId] = System.currentTimeMillis()
                    placedModels = placedModels + candidate
                    if (respawn == null) sessionCounters[0] += 1 else sessionCounters[1] += 1
                    // Статистика: только настоящая постановка гостем (не служебное пересоздание узла).
                    if (respawn == null) reportShowcaseEvent(candidate.modelId, FurnitureShowcaseStatsRepository.EVENT_PLACED)
                    if (respawn != null) {
                        respawnLineage[newInstanceId] = (respawnLineage[respawn.instanceId] ?: 0) + 1
                        if (reselect) selectedInstanceId = newInstanceId
                        logEvent("RESPAWN %s".format(respawn.modelName))
                    }
                    val statePos = candidate.position
                    if (debugOn) {
                        scope.launch {
                            delay(1500)
                            val probe = nodeProbes[newInstanceId]?.invoke() ?: "узла нет"
                            debugLine = "%s fps=%d\nstate=%.2f,%.2f,%.2f\nnode %s".format(
                                candidate.surfaceType.name, smoothFps[0].toInt(),
                                statePos.x, statePos.y, statePos.z, probe,
                            )
                        }
                    }
                    statusMessage = null
                    // Держим блокировку, пока модель не "осела" в сцене (~100 мс).
                    // Страховка: не дольше 2 секунд, чтобы карусель не зависла навсегда.
                    withTimeoutOrNull(2_000) {
                        snapshotFlow { newInstanceId in settledInstanceIds }.first { it }
                    }
                }
            } finally {
                loadingModelId = null
            }
        }
    }

    fun findTappedModel(tapOffset: Offset): PlacedModel? {
        val frame = currentFrame ?: return null
        val cameraRight = frame.camera.displayOrientedPose.xAxis
        var closest: PlacedModel? = null
        var closestScore = Float.MAX_VALUE
        for (placed in placedModels) {
            val footprint = footprintCache[placed.instanceId]
            // Опорная точка — центр основания (пол) или центр (стена); для выбора берём центр объёма
            val lift = if (placed.surfaceType == SurfaceType.FLOOR) (footprint?.halfY ?: 0f) else 0f
            val center = Position(placed.position.x, placed.position.y + lift, placed.position.z)
            val centerOnScreen = projectToScreen(frame.camera, center, viewportWidthPx, viewportHeightPx)
                ?: continue

            // Зона выбора = видимый на экране размер модели (но не меньше прежних 70dp)
            val radiusMeters = footprint?.let { (it.halfX + it.halfY + it.halfZ) / 3f } ?: 0f
            val edge = Position(
                center.x + cameraRight[0] * radiusMeters,
                center.y + cameraRight[1] * radiusMeters,
                center.z + cameraRight[2] * radiusMeters,
            )
            val radiusPx = projectToScreen(frame.camera, edge, viewportWidthPx, viewportHeightPx)
                ?.let { (it - centerOnScreen).getDistance() } ?: 0f
            val reach = maxOf(tapTolerancePx, radiusPx)

            val distance = (centerOnScreen - tapOffset).getDistance()
            val score = distance / reach
            if (distance <= reach && score < closestScore) {
                closest = placed
                closestScore = score
            }
        }
        return closest
    }

    fun angleBetweenPointers(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    fun moveSelectedModel(xPx: Float, yPx: Float) {
        val id = selectedInstanceId ?: return
        val current = placedModels.find { it.instanceId == id } ?: return
        val targetHit = surfaceHitAt(
            xPx, yPx,
            restrictTo = current.surfaceType,
            onlyWallYawDegrees = if (current.surfaceType == SurfaceType.WALL) current.rotationYDegrees else null,
            onlyWallNear = if (current.surfaceType == SurfaceType.WALL) current.position else null,
        )
        if (targetHit == null) {
            logNoHit("move")
            return
        }
        val target = targetHit.position

        fun collidesAt(pos: Position): Boolean {
            val candidate = current.copy(position = pos)
            for (placed in placedModels) {
                if (placed.instanceId != id && placed.overlapsWith(candidate)) return true
            }
            return false
        }

        // Модель не "замирает" у препятствия, а едет как можно дальше к пальцу: сначала цель,
        // затем скольжение вдоль одной из осей (пол), затем доли пути от текущего места к цели.
        val from = current.position
        val options = ArrayList<Position>(6)
        options.add(target)
        if (current.surfaceType == SurfaceType.FLOOR) {
            options.add(Position(target.x, target.y, from.z))
            options.add(Position(from.x, target.y, target.z))
        }
        for (k in floatArrayOf(0.75f, 0.5f, 0.25f)) {
            options.add(
                Position(
                    from.x + (target.x - from.x) * k,
                    from.y + (target.y - from.y) * k,
                    from.z + (target.z - from.z) * k,
                ),
            )
        }
        // Кто мешает ЗАПРОШЕННОМУ положению (подсвечиваем всегда, даже если модель при этом
        // «проскальзывает» вдоль препятствия): иначе подсказка была бы только при полной блокировке,
        // то есть «в одну сторону».
        val targetBlocker = placedModels
            .filter { it.instanceId != id && it.overlapsWith(current.copy(position = target)) }
            .minByOrNull {
                val bx = it.position.x - target.x
                val by = it.position.y - target.y
                val bz = it.position.z - target.z
                bx * bx + by * by + bz * bz
            }
        if (targetBlocker != null && blockerHighlightId != targetBlocker.instanceId) {
            blockerHighlightId = targetBlocker.instanceId
        }
        val free = options.firstOrNull { !collidesAt(it) }
        if (free == null) {
            // Текстовая подсказка — только при полной блокировке, не чаще раза в 3,5 с.
            val toastNowMs = System.currentTimeMillis()
            if (targetBlocker != null && toastNowMs - lastNoHitLogMs[2] > 3_500L) {
                lastNoHitLogMs[2] = toastNowMs
                val blockerOnScreen = currentFrame
                    ?.let { projectToScreen(it.camera, targetBlocker.position, viewportWidthPx, viewportHeightPx) }
                    ?.let { it.x >= 0f && it.y >= 0f && it.x <= viewportWidthPx && it.y <= viewportHeightPx }
                    ?: false
                statusMessage = if (blockerOnScreen) {
                    "Мешает модель «${targetBlocker.modelName}» — она подсвечена красным. Отодвинь её или поверни свою"
                } else {
                    "Мешает модель «${targetBlocker.modelName}», она сейчас вне кадра — отведи камеру, чтобы увидеть её"
                }
            }
            val nowMs = System.currentTimeMillis()
            if (debugOn && nowMs - lastNoHitLogMs[1] > 700L) {
                lastNoHitLogMs[1] = nowMs
                val blockers = placedModels
                    .filter { it.instanceId != id && it.overlapsWith(current.copy(position = target)) }
                    .joinToString(",") { it.modelName }
                logEvent("BLOCK by [%s]".format(blockers))
            }
            return
        }
        if (free.x == from.x && free.y == from.y && free.z == from.z) return
        placedModels = placedModels.map { if (it.instanceId == id) it.copy(position = free) else it }
        if (debugOn && moveLogCount[0]++ % 8 == 0) {
            logEvent("MOVE %.2f,%.2f,%.2f->%.2f,%.2f,%.2f".format(from.x, from.y, from.z, free.x, free.y, free.z))
        }
    }

    /** Для FLOOR крутим rotationYDegrees (вертикальная ось, как раньше).
     *  Для WALL крутим tiltDegrees — наклон вокруг нормали стены (как картина). */
    fun rotateSelectedModel(deltaDegrees: Float) {
        val id = selectedInstanceId ?: return
        if (debugOn && rotLogCount[0]++ % 8 == 0) {
            val cur = placedModels.find { it.instanceId == id }
            logEvent("ROT d=%.1f yaw=%.0f tilt=%.0f".format(deltaDegrees, cur?.rotationYDegrees ?: 0f, cur?.tiltDegrees ?: 0f))
        }
        placedModels = placedModels.map { placed ->
            if (placed.instanceId != id) return@map placed
            when (placed.surfaceType) {
                SurfaceType.FLOOR -> placed.copy(rotationYDegrees = placed.rotationYDegrees + deltaDegrees)
                SurfaceType.WALL -> placed.copy(tiltDegrees = placed.tiltDegrees + deltaDegrees)
            }
        }
    }

    fun deleteModel(instanceId: String) {
        placedModels = placedModels.filterNot { it.instanceId == instanceId }
        instanceCache.remove(instanceId)
        footprintCache.remove(instanceId)
        nodeProbes.remove(instanceId)
        nodeLive.remove(instanceId)
        handles.remove(instanceId)
        placedAtMs.remove(instanceId)
        ringPush.remove(instanceId)
        ringBornMs.remove(instanceId)
        ringPush.remove("B:" + instanceId)
        ringBornMs.remove("B:" + instanceId)
        if (blockerHighlightId == instanceId) blockerHighlightId = null
        flushedIds.remove(instanceId)
        earlyFlushedIds.remove(instanceId)
        if (selectedInstanceId == instanceId) selectedInstanceId = null
        if (deleteMenuInstanceId == instanceId) deleteMenuInstanceId = null
    }

    respawnAction[0] = { lost: PlacedModel ->
        val lostModel = models.firstOrNull { it.url == lost.modelUrl }
        val wasSelected = selectedInstanceId == lost.instanceId
        deleteModel(lost.instanceId)
        if (lostModel != null) placePendingModel(lost, lostModel, wasSelected)
    }

    // «Подвижка на 1 мм» через состояние: единственное, что стабильно лечило залипший yaw на стене,
    // — это перемещение (запись позиции внешнего узла штатным путём Compose). Повторяем его
    // программно, чередуя знак, чтобы модель не уползала.
    fun nudgePosition(instanceId: String, h: ModelNodeHandles) {
        val sign = h.nudgeSign
        h.nudgeSign = -sign
        scope.launch {
            placedModels = placedModels.map {
                if (it.instanceId == instanceId) {
                    it.copy(position = Position(it.position.x + 0.001f * sign, it.position.y, it.position.z))
                } else {
                    it
                }
            }
        }
    }

    // «Встряска сцены»: по видео (20:46) залипшая модель оживает сразу после того, как пользователь
    // снимает выделение и выделяет заново, то есть когда кружок выделения удаляется и создаётся.
    // Делаем то же программно: убираем кружок на 250 мс и возвращаем.
    var ringSuppressed by remember { mutableStateOf(false) }
    fun pokeScene(reason: String) {
        if (ringSuppressed) return
        logEvent("POKE %s".format(reason))
        scope.launch {
            ringSuppressed = true
            delay(250)
            ringSuppressed = false
        }
    }

    // Покадровая синхронизация: узлы принудительно приводятся к состоянию (позиция, yaw, наклон,
    // смещение центрирования), не дожидаясь перекомпозиции Compose, которая иногда запаздывает
    // (видно по жёлтой строке: поворот в состоянии уже есть, а узел ещё нет).
    fun syncNodes() {
        for (placed in placedModels) {
            val id = placed.instanceId
            val h = handles[id] ?: continue
            val footprint = footprintCache[id] ?: continue
            val settled = id in settledInstanceIds
            val yaw = if (settled) placed.rotationYDegrees else 0f
            val tilt = if (settled) placed.tiltDegrees else 0f
            var info: String? = null
            val forcedThisFrame = h.forceNext
            h.outerSync?.invoke(placed.position, yaw)?.let {
                if (debugOn) { info = (info ?: "") + it + " "; syncFixCount[0] += 1 }
            }
            h.midSync?.invoke(tilt)?.let {
                if (debugOn) { info = (info ?: "") + it + " "; syncFixCount[0] += 1 }
            }
            h.modelSync?.invoke(footprint.modelOffset)?.let {
                if (debugOn) { info = (info ?: "") + it + " "; syncFixCount[0] += 1 }
            }
            if (forcedThisFrame) h.forceNext = false
            // Обнаружение «залипшего» узла: состояние говорит одно, а мировой поворот модели по данным
            // SceneView — другое дольше ~0,2 с. Наклон мал -> сравниваем yaw; иначе (стена, поворот
            // в плоскости стены) сравниваем мировую «верхнюю» ось с cos(наклона).
            val ageMs = System.currentTimeMillis() - (placedAtMs[id] ?: 0L)
            var healthy = false
            if (settled && ageMs > 300L) {
                val tiltSmall = kotlin.math.abs(wrap180(tilt)) < 5f
                var staleNow = false
                var staleText = ""
                if (tiltSmall) {
                    val worldYaw = h.modelWorldYaw?.invoke()
                    if (worldYaw != null) {
                        val diff = minOf(
                            kotlin.math.abs(wrap180(worldYaw + yaw)),
                            kotlin.math.abs(wrap180(worldYaw - yaw)),
                        )
                        staleNow = diff > 8f
                        staleText = "yaw state=%.0f world=%.0f".format(yaw, worldYaw)
                    }
                } else {
                    val upY = h.modelWorldUpY?.invoke()
                    if (upY != null) {
                        val expected = kotlin.math.cos(Math.toRadians(tilt.toDouble())).toFloat()
                        staleNow = kotlin.math.abs(upY - expected) > 0.25f
                        staleText = "tilt=%.0f upY=%.2f exp=%.2f".format(tilt, upY, expected)
                    }
                }
                // Поворот «от стены»/по полу проверяем по мировой оси Z модели: она не зависит от
                // наклона. Знак оси допускаем любой (проверяем обе зеркальные ориентации).
                var yawStale = false
                val wz = h.modelWorldZ?.invoke()
                if (wz != null) {
                    val yawRad = Math.toRadians(yaw.toDouble())
                    val sy = kotlin.math.sin(yawRad).toFloat()
                    val cy = kotlin.math.cos(yawRad).toFloat()
                    val best = maxOf(wz[0] * sy + wz[2] * cy, -wz[0] * sy + wz[2] * cy)
                    if (best < 0.985f) {
                        staleNow = true
                        yawStale = true
                        staleText += " zAxis yaw=%.0f dot=%.2f".format(yaw, best)
                    }
                }
                // Мировая позиция узла «уехала» от состояния дальше, чем допускает размер модели
                // (по видео 21:46 — на 2,5 м при неподвижной модели) — тоже залипание.
                var posGarbage = false
                val wpos = h.modelWorldPos?.invoke()
                if (wpos != null) {
                    val diag = kotlin.math.sqrt(
                        footprint.halfX * footprint.halfX + footprint.halfY * footprint.halfY + footprint.halfZ * footprint.halfZ,
                    )
                    val ddx = wpos.x - placed.position.x
                    val ddy = wpos.y - placed.position.y
                    val ddz = wpos.z - placed.position.z
                    val dist = kotlin.math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
                    if (dist > diag + 0.25f) {
                        staleNow = true
                        posGarbage = true
                        staleText += " pos dist=%.2f".format(dist)
                    }
                }
                // Если мировая позиция «уехала» и не возвращается ~1,5 с (по видео 22:46 модель
                // пропала уже после нормального рождения), пересоздаём модель так же, как при
                // «плохом рождении» (не более 2 раз подряд).
                // По видео 23:02 на стене залипание (и позиции, и yaw) длится секундами и не лечится
                // перезаписью, а новая модель обычно рождается нормально — поэтому пересоздаём уже
                // через ~1 с (30 кадров) залипания, не только при «уехавшей» позиции.
                if (posGarbage || yawStale) h.garbageFrames += 1 else h.garbageFrames = 0
                if (h.garbageFrames >= 30 && (respawnLineage[id] ?: 0) < 3 &&
                    respawnQueue.none { it.instanceId == id }
                ) {
                    h.garbageFrames = 0
                    logEvent("LOST later %s -> пересоздаю".format(staleText))
                    respawnQueue.add(placed)
                }
                healthy = !staleNow
                if (staleNow) {
                    h.staleFrames += 1
                    if (h.staleFrames >= 6) {
                        val nowForce = System.currentTimeMillis()
                        if (!h.poked) {
                            h.poked = true
                            logEvent("STALE %s".format(staleText))
                            pokeScene(placed.modelName)
                        }
                        // Пока залипание не ушло — повторяем «перезапись набело» раз в 0,8 с.
                        if (nowForce - h.lastForceMs > 800L) {
                            h.lastForceMs = nowForce
                            h.forceNext = true
                            nudgePosition(id, h)
                        }
                    }
                } else {
                    h.staleFrames = 0
                    h.poked = false
                }
            }
            if (!h.revealed) {
                if (healthy) h.okFrames += 1 else h.okFrames = 0
                if (h.okFrames >= 8 || ageMs > 3_500L) {
                    h.revealed = true
                    h.modelSetVisible?.invoke(true)
                    logEvent("REVEAL %s age=%dms ok=%d".format(placed.modelName, ageMs, h.okFrames))
                }
            }
            // Диагностика (только debugOn): мировая трансформация модели изменилась САМА (состояние то же).
            if (debugOn) {
                val jumpPos = h.modelWorldPos?.invoke()
                val jumpZ = h.modelWorldZ?.invoke()
                val jumpUp = h.modelWorldUpY?.invoke()
                val stateKey = placed.position.x + placed.position.y * 3f + placed.position.z * 7f +
                        placed.rotationYDegrees * 0.01f + placed.tiltDegrees * 0.013f
                if (jumpPos != null && jumpZ != null && jumpUp != null) {
                    val last = h.lastWorld
                    if (last != null && h.lastStateKey == stateKey) {
                        val jdx = jumpPos.x - last[0]
                        val jdy = jumpPos.y - last[1]
                        val jdz = jumpPos.z - last[2]
                        val dPos = kotlin.math.sqrt(jdx * jdx + jdy * jdy + jdz * jdz)
                        val dZ = jumpZ[0] * last[3] + jumpZ[1] * last[4] + jumpZ[2] * last[5]
                        val dUp = kotlin.math.abs(jumpUp - last[6])
                        val nowJ = System.currentTimeMillis()
                        if ((dPos > 0.05f || dZ < 0.98f || dUp > 0.05f) && nowJ - h.lastJumpLogMs > 500L) {
                            h.lastJumpLogMs = nowJ
                            logEvent("JUMP dpos=%.2f dz=%.2f dup=%.2f".format(dPos, dZ, dUp))
                        }
                    }
                    h.lastWorld = floatArrayOf(jumpPos.x, jumpPos.y, jumpPos.z, jumpZ[0], jumpZ[1], jumpZ[2], jumpUp)
                    h.lastStateKey = stateKey
                }
            }
            val worldScale = h.modelWorldScale?.invoke()
            if (settled && worldScale != null && kotlin.math.abs(worldScale - 1f) > 0.05f) {
                h.scaleFrames += 1
                if (h.scaleFrames >= 6 && !h.scalePoked) {
                    h.scalePoked = true
                    h.forceNext = true
                    logEvent("SCALE world=%.2f".format(worldScale))
                }
            } else {
                h.scaleFrames = 0
                h.scalePoked = false
            }
            if (debugOn && info != null) {
                syncLastInfo[0] = "fix#%d %s: %s".format(syncFixCount[0], placed.modelName, info)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastFixLogMs[0] > 150L) {
                    lastFixLogMs[0] = nowMs
                    logEvent("FIX %s".format(info))
                }
            }
        }
    }

    // Перекрытие в материале потока камеры. Без глубины в сессии SceneView сам остаётся на
    // обычном материале, поэтому флаг включаем вместе с depthMode (оба от occlusionOn).
    SideEffect {
        cameraStream.isDepthOcclusionEnabled = occlusionOn
        OCCLUSION_BIAS_PRESETS[occlusionBiasIndex].let { (biasM, biasPerM) ->
            cameraStream.depthOcclusionMaterial.defaultInstance.apply {
                setParameter("occlusionBiasMeters", biasM)
                setParameter("occlusionBiasPerMeter", biasPerM)
            }
        }
        // Кружок выбора и красный кружок «кто мешает» — это интерфейс, а не мебель. В режиме
        // перекрытия шумная карта глубины пола рвёт их на пятна, поэтому НА ПОЛУ они рисуются
        // поверх всего (без проверки глубины). На стене — всегда с проверкой (см. *WallMaterial).
        // В обычном режиме — как раньше.
        selectionMaterial.setDepthCulling(!occlusionOn)
        blockerMaterial.setDepthCulling(!occlusionOn)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    viewportWidthPx = size.width
                    viewportHeightPx = size.height
                },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            planeRenderer = showGrid,
            // Глубина считается только при включённом перекрытии. Смена режима на лету —
            // штатный путь SceneView (LaunchedEffect(depthMode) → session.configure), остальной
            // конфиг сессии при этом сохраняется. Возможен короткий рывок кадра при переключении.
            depthMode = if (occlusionOn) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED,
            cameraStream = cameraStream,
            sessionConfiguration = { session: Session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                // Только опрос возможностей устройства; config.depthMode здесь НЕ трогаем — им управляет параметр depthMode выше.
                depthSupportText = try {
                    val auto = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    val raw = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                    depthSupported = auto
                    "Глубина: " + (if (auto) "поддерживается" else "НЕ поддерживается") +
                            " (raw: " + (if (raw) "да" else "нет") + ")"
                } catch (e: Exception) {
                    "Глубина: ошибка проверки (${e.javaClass.simpleName})"
                }
            },
            onSessionUpdated = { session: Session, frame: Frame ->
                if (debugOn) {
                    val ts = frame.timestamp
                    if (lastFrameTs[0] != 0L) {
                        val dt = (ts - lastFrameTs[0]) / 1_000_000_000f
                        if (dt > 0f) smoothFps[0] = smoothFps[0] * 0.9f + (1f / dt) * 0.1f
                    }
                    lastFrameTs[0] = ts
                }
                syncNodes()
                currentSession = session
                currentFrame = frame
                val planes = session.getAllTrackables(Plane::class.java)
                horizontalPlaneCount = planes.count {
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING
                }
                verticalPlaneCount = planes.count {
                    it.type == Plane.Type.VERTICAL && it.trackingState == TrackingState.TRACKING
                }
                updateTrackingHint(frame)
                if (measureMode && measurePointB == null && viewportWidthPx > 0 && viewportHeightPx > 0) {
                    val live = measureHitAt(viewportWidthPx / 2f, viewportHeightPx / 2f)
                    if (live != measureLive) measureLive = live
                }
                if (debugOn && occlusionOn && viewportWidthPx > 0 && depthProbeFrame[0]++ % 10 == 0) {
                    depthProbeLine = probeDepthAtCenter(frame, viewportWidthPx, viewportHeightPx)
                } else if (!(debugOn && occlusionOn) && depthProbeLine.isNotEmpty()) {
                    depthProbeLine = ""
                }
            },
            onTouchEvent = touch@{ event: MotionEvent, _ ->
                // В режиме «рулетки» касания сцены ничего не делают (точки ставятся кнопкой).
                if (measureMode) return@touch true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownPosition = Offset(event.x, event.y)
                        touchMoved = false
                        isTwoFingerGesture = false
                        gestureHadMultiTouch = false
                        longPressTriggered = false
                        rotLogCount[0] = 0
                        moveLogCount[0] = 0
                        gestureActive[0] = true
                        logEvent("DOWN")
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(500)
                            val tapped = findTappedModel(touchDownPosition)
                            if (tapped != null) {
                                selectedInstanceId = tapped.instanceId
                                deleteMenuInstanceId = tapped.instanceId
                                longPressTriggered = true
                            }
                        }
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        longPressJob?.cancel()
                        gestureHadMultiTouch = true
                        logEvent("PTR+ n=${event.pointerCount}")
                        if (event.pointerCount >= 2) {
                            isTwoFingerGesture = true
                            lastTwoFingerAngle = angleBetweenPointers(event)
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount >= 2 && isTwoFingerGesture) {
                            val currentAngle = angleBetweenPointers(event)
                            val delta = currentAngle - lastTwoFingerAngle
                            lastTwoFingerAngle = currentAngle
                            if (selectedInstanceId != null) {
                                rotateSelectedModel(-delta)
                            }
                        } else if (event.pointerCount == 1 && !gestureHadMultiTouch) {
                            val current = Offset(event.x, event.y)
                            val distance = (current - touchDownPosition).getDistance()
                            if (distance > tapTolerancePx) {
                                touchMoved = true
                                longPressJob?.cancel()
                                deleteMenuInstanceId = null
                                if (selectedInstanceId != null) {
                                    moveSelectedModel(event.x, event.y)
                                }
                            }
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        logEvent("PTR- n=${event.pointerCount}")
                        if (event.pointerCount <= 2) {
                            isTwoFingerGesture = false
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        gestureActive[0] = false
                        longPressJob?.cancel()
                        // Тап = ни движения, ни двух пальцев, ни долгого нажатия. Выделение снимает
                        // ТОЛЬКО тап по пустому месту (или переключает тап по другой модели);
                        // после поворота двумя пальцами выбор сохраняется.
                        if (!touchMoved && !gestureHadMultiTouch && !isTwoFingerGesture && !longPressTriggered &&
                            event.actionMasked == MotionEvent.ACTION_UP
                        ) {
                            deleteMenuInstanceId = null
                            val tapped = findTappedModel(Offset(event.x, event.y))
                            if (tapped?.instanceId != selectedInstanceId) {
                                logEvent("SEL ${tapped?.modelName ?: "none"}")
                            }
                            selectedInstanceId = tapped?.instanceId
                        } else {
                            logEvent("UP keep sel (moved=$touchMoved multi=$gestureHadMultiTouch lp=$longPressTriggered)")
                        }
                        gestureHadMultiTouch = false
                        isTwoFingerGesture = false
                        touchMoved = false
                        longPressTriggered = false
                    }
                }
                true
            },
        ) {
            placedModels.forEach { placed ->
                key(placed.instanceId) {
                    val instance = instanceCache[placed.instanceId]
                    val footprint = footprintCache[placed.instanceId]
                    if (instance != null && footprint != null) {
                        val isSettled = placed.instanceId in settledInstanceIds
                        // Родитель — разворот "от стены"/по полу (yaw). Средний узел — только наклон
                        // (tilt) вокруг центра модели. ModelNode внутри смещён так, что опорная точка
                        // = центр основания, поэтому всё крутится вокруг центра модели.
                        Node(
                            position = placed.position,
                            rotation = if (isSettled) Rotation(0f, placed.rotationYDegrees, 0f) else Rotation(0f, 0f, 0f),
                            apply = {
                                val node = this
                                val h = handles.getOrPut(placed.instanceId) { ModelNodeHandles() }
                                h.outerSync = { pos: Position, yaw: Float ->
                                    val p = node.position
                                    val sc = node.scale
                                    val r = node.rotation
                                    val curYaw = yawOfEuler(r.x, r.y)
                                    val flipped = kotlin.math.abs(wrap180(r.x)) > 90f
                                    val ex = if (flipped) wrap180(r.x - 180f) else wrap180(r.x)
                                    val ez = if (flipped) wrap180(r.z - 180f) else wrap180(r.z)
                                    val nearGimbal = kotlin.math.abs(kotlin.math.abs(curYaw) - 90f) < 8f
                                    val bad = h.forceNext ||
                                            kotlin.math.abs(p.x - pos.x) > 0.005f ||
                                            kotlin.math.abs(p.y - pos.y) > 0.005f ||
                                            kotlin.math.abs(p.z - pos.z) > 0.005f ||
                                            kotlin.math.abs(wrap180(curYaw - yaw)) > 0.5f ||
                                            (!nearGimbal && (kotlin.math.abs(ex) > 1f || kotlin.math.abs(ez) > 1f)) ||
                                            kotlin.math.abs(sc.x - 1f) > 0.02f ||
                                            kotlin.math.abs(sc.y - 1f) > 0.02f ||
                                            kotlin.math.abs(sc.z - 1f) > 0.02f
                                    if (bad) {
                                        val before = if (debugOn) {
                                            "O(%.2f,%.2f,%.2f ry=%.0f>%.2f,%.2f,%.2f ry=%.0f)".format(p.x, p.y, p.z, curYaw, pos.x, pos.y, pos.z, yaw)
                                        } else {
                                            "x"
                                        }
                                        if (h.forceNext) {
                                            // Запись того же значения, что уже в кэше узла, до Filament не
                                            // доходит (видно по видео: залипший yaw лечится только записью
                                            // ДРУГОГО значения — перемещением/поворотом). Поэтому сначала
                                            // пишем слегка иное, затем правильное, в одном кадре.
                                            node.transform = Transform(
                                                position = Position(pos.x + 0.001f, pos.y, pos.z),
                                                rotation = Rotation(0f, yaw + 0.3f, 0f),
                                                scale = Scale(1f),
                                            )
                                        }
                                        node.transform = Transform(
                                            position = Position(pos.x, pos.y, pos.z),
                                            rotation = Rotation(0f, yaw, 0f),
                                            scale = Scale(1f),
                                        )
                                        before
                                    } else {
                                        null
                                    }
                                }
                                h.outerPush = {
                                    node.isVisible = false
                                    node.isVisible = true
                                }
                                this.isVisible = false
                                this.isVisible = true
                            },
                        ) {
                            Node(
                                rotation = if (isSettled) Rotation(0f, 0f, placed.tiltDegrees) else Rotation(0f, 0f, 0f),
                                apply = {
                                    val node = this
                                    val h = handles.getOrPut(placed.instanceId) { ModelNodeHandles() }
                                    h.midSync = { tilt: Float ->
                                        val p = node.position
                                        val sc = node.scale
                                        val r = node.rotation
                                        val bad = h.forceNext ||
                                                kotlin.math.abs(p.x) > 0.005f ||
                                                kotlin.math.abs(p.y) > 0.005f ||
                                                kotlin.math.abs(p.z) > 0.005f ||
                                                kotlin.math.abs(wrap180(r.x)) > 1f ||
                                                kotlin.math.abs(wrap180(r.y)) > 1f ||
                                                kotlin.math.abs(wrap180(r.z - tilt)) > 0.5f ||
                                                kotlin.math.abs(sc.x - 1f) > 0.02f ||
                                                kotlin.math.abs(sc.y - 1f) > 0.02f ||
                                                kotlin.math.abs(sc.z - 1f) > 0.02f
                                        if (bad) {
                                            val before = if (debugOn) "T(rz=%.0f>%.0f)".format(r.z, tilt) else "x"
                                            if (h.forceNext) {
                                                node.transform = Transform(
                                                    position = Position(0.001f, 0f, 0f),
                                                    rotation = Rotation(0f, 0f, tilt + 0.3f),
                                                    scale = Scale(1f),
                                                )
                                            }
                                            node.transform = Transform(
                                                position = Position(0f, 0f, 0f),
                                                rotation = Rotation(0f, 0f, tilt),
                                                scale = Scale(1f),
                                            )
                                            before
                                        } else {
                                            null
                                        }
                                    }
                                    h.midPush = {
                                        node.isVisible = false
                                        node.isVisible = true
                                    }
                                    this.isVisible = false
                                    this.isVisible = true
                                },
                            ) {
                                ModelNode(
                                    modelInstance = instance,
                                    autoAnimate = false,
                                    position = footprint.modelOffset,
                                    apply = {
                                        val modelNode = this
                                        val h = handles.getOrPut(placed.instanceId) { ModelNodeHandles() }
                                        nodeLive[placed.instanceId] = { st: PlacedModel ->
                                            val wp = modelNode.worldPosition
                                            val wr = modelNode.worldRotation
                                            "LIVE state(%.2f,%.2f,%.2f y=%.0f) world(%.2f,%.2f,%.2f y=%.0f) sc=%.2f v=%s".format(
                                                st.position.x, st.position.y, st.position.z, st.rotationYDegrees,
                                                wp.x, wp.y, wp.z, yawOfEuler(wr.x, wr.y), modelNode.worldScale.x,
                                                modelNode.isVisible,
                                            )
                                        }
                                        nodeProbes[placed.instanceId] = {
                                            val p = modelNode.worldPosition
                                            val r = modelNode.worldRotation
                                            "pos=%.2f,%.2f,%.2f rot=%.0f,%.0f,%.0f".format(p.x, p.y, p.z, r.x, r.y, r.z)
                                        }
                                        h.modelSync = { offset: Position ->
                                            val p = modelNode.position
                                            val sc = modelNode.scale
                                            val r = modelNode.rotation
                                            val bad = h.forceNext ||
                                                    kotlin.math.abs(p.x - offset.x) > 0.005f ||
                                                    kotlin.math.abs(p.y - offset.y) > 0.005f ||
                                                    kotlin.math.abs(p.z - offset.z) > 0.005f ||
                                                    kotlin.math.abs(wrap180(r.x)) > 1f ||
                                                    kotlin.math.abs(wrap180(r.y)) > 1f ||
                                                    kotlin.math.abs(wrap180(r.z)) > 1f ||
                                                    kotlin.math.abs(sc.x - 1f) > 0.02f ||
                                                    kotlin.math.abs(sc.y - 1f) > 0.02f ||
                                                    kotlin.math.abs(sc.z - 1f) > 0.02f
                                            if (bad) {
                                                val before = if (debugOn) "M(%.2f,%.2f,%.2f)".format(p.x, p.y, p.z) else "x"
                                                if (h.forceNext) {
                                                    modelNode.transform = Transform(
                                                        position = Position(offset.x + 0.001f, offset.y, offset.z),
                                                        rotation = Rotation(0f, 0f, 0.3f),
                                                        scale = Scale(1f),
                                                    )
                                                }
                                                modelNode.transform = Transform(
                                                    position = Position(offset.x, offset.y, offset.z),
                                                    rotation = Rotation(0f, 0f, 0f),
                                                    scale = Scale(1f),
                                                )
                                                before
                                            } else {
                                                null
                                            }
                                        }
                                        h.modelBirthInfo = {
                                            val wp = modelNode.worldPosition
                                            "world(%.2f,%.2f,%.2f) vis=%s sc=%.2f".format(
                                                wp.x, wp.y, wp.z, modelNode.isVisible, modelNode.worldScale.x,
                                            )
                                        }
                                        h.modelWorldPos = { modelNode.worldPosition }
                                        h.modelWorldZ = {
                                            val wq = modelNode.worldQuaternion
                                            floatArrayOf(
                                                2f * (wq.x * wq.z + wq.w * wq.y),
                                                2f * (wq.y * wq.z - wq.w * wq.x),
                                                1f - 2f * (wq.x * wq.x + wq.y * wq.y),
                                            )
                                        }
                                        h.modelWorldScale = {
                                            val ws = modelNode.worldScale
                                            (ws.x + ws.y + ws.z) / 3f
                                        }
                                        h.modelWorldUpY = {
                                            val wq = modelNode.worldQuaternion
                                            1f - 2f * (wq.x * wq.x + wq.z * wq.z)
                                        }
                                        h.modelWorldYaw = {
                                            val wr = modelNode.worldRotation
                                            yawOfEuler(wr.x, wr.y)
                                        }
                                        h.modelSetVisible = { visible: Boolean -> modelNode.isVisible = visible }
                                        h.modelPush = {
                                            modelNode.isVisible = false
                                            modelNode.isVisible = h.revealed
                                        }
                                        this.isVisible = false
                                        this.isVisible = h.revealed
                                    },
                                )
                            }
                        }
                        if (placed.instanceId == selectedInstanceId && !ringSuppressed) {
                            // Радиус описывает основание модели + 5 см, чтобы кружок всегда
                            // выступал из-под корпуса (даже у большой кухни)
                            val ringRadius = when (placed.surfaceType) {
                                SurfaceType.FLOOR -> hypot(footprint.halfX, footprint.halfZ)
                                SurfaceType.WALL -> hypot(footprint.halfX, footprint.halfY)
                            } + 0.05f
                            // Ключ округлён до 1 см: подвижки на 1 мм (лечение залипания) не должны
                            // пересоздавать кружок — по видео 22:32 из-за этого он «исчезал» на секунды.
                            key(
                                Math.round(placed.position.x * 100f),
                                Math.round(placed.position.y * 100f),
                                Math.round(placed.position.z * 100f),
                                placed.surfaceType,
                            ) {
                                val ringToken = remember { Any() }
                                DisposableEffect(ringToken) {
                                    onDispose {
                                        if (ringPush[placed.instanceId]?.first === ringToken) {
                                            ringPush.remove(placed.instanceId)
                                            ringBornMs.remove(placed.instanceId)
                                        }
                                    }
                                }
                                CylinderNode(
                                    radius = ringRadius,
                                    height = 0.002f,
                                    materialInstance = if (placed.surfaceType == SurfaceType.WALL)
                                        selectionWallMaterial else selectionMaterial,
                                    position = markerPositionOf(placed),
                                    rotation = if (placed.surfaceType == SurfaceType.WALL)
                                        Rotation(90f, placed.rotationYDegrees, 0f)
                                    else
                                        Rotation(0f, 0f, 0f),
                                    apply = {
                                        val ringNode = this
                                        ringPush[placed.instanceId] = ringToken to {
                                            ringNode.isVisible = false
                                            ringNode.isVisible = true
                                        }
                                        ringBornMs[placed.instanceId] = System.currentTimeMillis()
                                        this.isVisible = false
                                        this.isVisible = true
                                    },
                                )
                            }
                        }
                        if (placed.instanceId == blockerHighlightId) {
                            val blockRadius = when (placed.surfaceType) {
                                SurfaceType.FLOOR -> hypot(footprint.halfX, footprint.halfZ)
                                SurfaceType.WALL -> hypot(footprint.halfX, footprint.halfY)
                            } + 0.08f
                            val blockKey = "B:" + placed.instanceId
                            key(
                                Math.round(placed.position.x * 100f),
                                Math.round(placed.position.y * 100f),
                                Math.round(placed.position.z * 100f),
                                placed.surfaceType,
                            ) {
                                val blockToken = remember { Any() }
                                DisposableEffect(blockToken) {
                                    onDispose {
                                        if (ringPush[blockKey]?.first === blockToken) {
                                            ringPush.remove(blockKey)
                                            ringBornMs.remove(blockKey)
                                        }
                                    }
                                }
                                CylinderNode(
                                    radius = blockRadius,
                                    height = 0.003f,
                                    materialInstance = if (placed.surfaceType == SurfaceType.WALL)
                                        blockerWallMaterial else blockerMaterial,
                                    position = markerPositionOf(placed),
                                    rotation = if (placed.surfaceType == SurfaceType.WALL)
                                        Rotation(90f, placed.rotationYDegrees, 0f)
                                    else
                                        Rotation(0f, 0f, 0f),
                                    apply = {
                                        val blockNode = this
                                        ringPush[blockKey] = blockToken to {
                                            blockNode.isVisible = false
                                            blockNode.isVisible = true
                                        }
                                        ringBornMs[blockKey] = System.currentTimeMillis()
                                        this.isVisible = false
                                        this.isVisible = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            IconButton(onClick = onLogoutClick) {
                if (isGuest) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = "Вход для дизайнеров и руководителей", tint = Color.White)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Выйти", tint = Color.White)
                }
            }
            IconButton(onClick = { showGrid = !showGrid }) {
                Icon(
                    if (showGrid) Icons.Default.GridOff else Icons.Default.GridOn,
                    contentDescription = if (showGrid) "Скрыть сетку" else "Показать сетку",
                    tint = Color.White,
                )
            }
            IconButton(enabled = measureMode || loadingModelId == null, onClick = {
                if (measureMode) {
                    measureMode = false
                    resetMeasurement()
                } else {
                    resetMeasurement()
                    measureRealCmText = ""
                    deleteMenuInstanceId = null
                    measureMode = true
                }
            }) {
                Icon(
                    Icons.Default.Straighten,
                    contentDescription = if (measureMode) "Закрыть проверку масштаба" else "Проверить масштаб",
                    tint = if (measureMode) Color(0xFF00E676) else Color.White,
                )
            }
            // Перекрытие моделей реальными предметами по карте глубины (ARCore Depth API).
            // Доступно всем, включая гостей витрины (решение 09.10).
            if (depthSupported) {
                // Box вместо IconButton: нужно ещё долгое нажатие (только в диагностике —
                // перебор пресетов допуска). Размер 48 dp — как у IconButton, ряд не съезжает.
                // Долгое нажатие (перебор допуска) — тоже вдвое дольше обычного.
                CompositionLocalProvider(LocalViewConfiguration provides slowLongPressConfig) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        occlusionOn = !occlusionOn
                                        statusMessage = if (occlusionOn) {
                                            if (isGuest) "Прятать за реальными предметами: ВКЛ. Поводите камерой пару секунд"
                                            else "Прятать за реальными предметами: ВКЛ. Поводи камерой пару секунд"
                                        } else {
                                            "Прятать за реальными предметами: ВЫКЛ"
                                        }
                                    },
                                    onLongPress = {
                                        if (debugOn) {
                                            occlusionBiasIndex = (occlusionBiasIndex + 1) % OCCLUSION_BIAS_PRESETS.size
                                            val (bm, bpm) = OCCLUSION_BIAS_PRESETS[occlusionBiasIndex]
                                            statusMessage = "Допуск глубины: %.0f см + %.0f см/м".format(bm * 100f, bpm * 100f)
                                        }
                                    },
                                )
                            },
                    ) {
                        Icon(
                            if (occlusionOn) Icons.Default.Layers else Icons.Default.LayersClear,
                            contentDescription = if (occlusionOn) "Не прятать за реальными предметами"
                            else "Прятать за реальными предметами",
                            tint = if (occlusionOn) Color(0xFF00E676) else Color.White,
                        )
                    }
                }
            }
            // Справка «i» — в том же стиле, что и остальные кнопки: белая иконка прямо на камере.
            IconButton(onClick = { showHelp = true }) {
                Icon(Icons.Outlined.Info, contentDescription = "Справка", tint = Color.White)
            }
        }

        TrackingBanner(
            text = { trackingHint },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 100.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 420.dp),
        )

        // Счётчик «Пол / Стены» — под рядом кнопок, слева (ряд кнопок может расти вправо).
        // Ряд кнопок: отступ 16 dp + высота IconButton 48 dp = 64 dp, поэтому счётчик начинается
        // с 64 dp и по касаниям с кнопками больше не пересекается. Начало текста выровнено по
        // левому краю иконки «Назад» (16 + 12 + 12 = 28 dp от края экрана).
        // Долгое нажатие на счётчик (вкл/выкл диагностики) — вдвое дольше обычного,
        // чтобы пользователь не включил её случайно.
        CompositionLocalProvider(LocalViewConfiguration provides slowLongPressConfig) {
            Text(
                if (debugOn && trackingPointsShown >= 0) {
                    "Пол: $horizontalPlaneCount  Стены: $verticalPlaneCount  Точки: $trackingPointsShown\n" +
                            depthSupportText + (if (occlusionOn) " · перекрытие ВКЛ" + biasLabel(occlusionBiasIndex) else "") +
                            (if (depthProbeLine.isNotEmpty()) "\n$depthProbeLine" else "")
                } else if (debugOn) {
                    "Пол: $horizontalPlaneCount  Стены: $verticalPlaneCount\n" +
                            depthSupportText + (if (occlusionOn) " · перекрытие ВКЛ" + biasLabel(occlusionBiasIndex) else "") +
                            (if (depthProbeLine.isNotEmpty()) "\n$depthProbeLine" else "")
                } else {
                    "Пол: $horizontalPlaneCount  Стены: $verticalPlaneCount"
                },
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 64.dp, start = 16.dp)
                    .pointerInput(isGuest) {
                        // У гостя долгое нажатие на счётчик ничего не делает → диагностика и
                        // перебор допуска глубины (он работает только в диагностике) ему недоступны.
                        if (isGuest) return@pointerInput
                        detectTapGestures(onLongPress = {
                            debugOn = !debugOn
                            statusMessage = if (debugOn) "Режим диагностики включён" else "Режим диагностики выключен"
                        })
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        if (debugOn) {
            Text(
                debugLine,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 100.dp, start = 190.dp, end = 16.dp),
            )

            Text(
                healLine,
                color = Color.Yellow,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 152.dp, start = 100.dp, end = 16.dp),
            )
        }

        if (debugOn && eventLogText.isNotEmpty()) {
            Text(
                eventLogText,
                color = Color.Cyan,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 128.dp, start = 8.dp)
                    .widthIn(max = 230.dp)
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
        }

        val currentStatusMessage = statusMessage
        if (currentStatusMessage != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPillPadding, start = 16.dp, end = 16.dp),
            ) {
                Text(currentStatusMessage)
            }
        }

        if (measureMode) {
            MeasureReticleLayer(
                frame = { currentFrame },
                pointA = { measurePointA },
                pointB = { measurePointB },
                live = { measureLive },
            )
            MeasurePanel(
                pointA = { measurePointA },
                pointB = { measurePointB },
                live = { measureLive },
                realCmText = measureRealCmText,
                onRealCmTextChange = {
                    measureRealCmText = it
                    measureVerdict = null
                },
                verdict = measureVerdict,
                onSetPoint = { setMeasurePoint() },
                onCheck = { checkMeasurement() },
                onReset = { resetMeasurement() },
                onClose = {
                    measureMode = false
                    resetMeasurement()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(16.dp)
                    .widthIn(max = 480.dp),
            )
        }

        val menuId = if (measureMode) null else deleteMenuInstanceId
        if (menuId != null) {
            Box(modifier = Modifier.align(Alignment.Center).padding(16.dp)) {
                Button(
                    onClick = { deleteModel(menuId) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить")
                }
            }
        }

        val loadingId = loadingModelId
        if (loadingId != null) {
            val loadingName = models.find { it.modelId == loadingId }?.modelName.orEmpty()
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPillPadding, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Column {
                        Text("Загружаю «$loadingName»")
                        Text(loadProgressText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        val selectedPlaced = placedModels.find { it.instanceId == selectedInstanceId }
        val selectedModelName = selectedPlaced?.modelName
        // Руководитель: чья модель (у дизайнера и гостя designerName нет — строка прежняя).
        val selectedDesignerName = selectedPlaced
            ?.let { placed -> models.find { it.modelId == placed.modelId }?.designerName }
            ?.takeIf { it.isNotBlank() }
        // Гость: модель витрины для выбранной (modelId поставленной модели = код витрины).
        val selectedShowcaseModel = selectedPlaced?.let { guestShowcase?.modelByCode(it.modelId) }
        if (selectedModelName != null &&
            !measureMode &&
            loadingId == null &&
            currentStatusMessage == null &&
            deleteMenuInstanceId == null
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPillPadding, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (selectedDesignerName != null) "Выбрано: «$selectedModelName» · $selectedDesignerName"
                        else "Выбрано: «$selectedModelName»"
                    )
                    Text(
                        "Тяни пальцем — переместить · двумя пальцами — повернуть · долгое нажатие — удалить",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (selectedShowcaseModel != null) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = {
                            contactCode = selectedShowcaseModel.code
                            reportShowcaseEvent(selectedShowcaseModel.code, FurnitureShowcaseStatsRepository.EVENT_CONTACT)
                        }) {
                            Text("Хочу такую · ${selectedShowcaseModel.code}")
                        }
                    }
                }
            }
        }

        // Гость: чипы типов мебели над каруселью (только непустые типы — их отдаёт сервер).
        if (!measureMode && showTypeChips) LazyRow(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 116.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                GuestTypeChip(text = "Все", selected = guestTypeFilter == null, onClick = { guestTypeFilter = null })
            }
            items(chipTypes) { type ->
                GuestTypeChip(
                    text = type.name,
                    selected = guestTypeFilter == type.id,
                    onClick = { guestTypeFilter = type.id },
                )
            }
        }

        // Руководитель: чипы дизайнеров над каруселью (только если дизайнеров с моделями больше одного).
        if (!measureMode && showDesignerChips) LazyRow(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 116.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                GuestTypeChip(text = "Все", selected = designerFilter == null, onClick = { designerFilter = null })
            }
            items(designerChips) { (designerId, designerName) ->
                GuestTypeChip(
                    text = designerName,
                    selected = designerFilter == designerId,
                    onClick = { designerFilter = designerId },
                )
            }
        }

        if (!measureMode) LazyRow(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(carouselModels) { model ->
                ModelThumbnail(
                    model = model,
                    isSelected = model.modelId == pendingModel?.modelId,
                    isLoading = model.modelId == loadingModelId,
                    isLocked = loadingModelId != null,
                    onClick = {
                        pendingModel = model
                        placePendingModel()
                    },
                )
            }
        }
    }

    if (showHelp) {
        SectionHelpDialog(
            title = "Как пользоваться",
            items = helpItems,
            onDismiss = { showHelp = false }
        )
    }

    val contactModel = contactCode?.let { guestShowcase?.modelByCode(it) }
    if (guestShowcase != null && contactModel != null) {
        FurnitureContactDialog(
            model = contactModel,
            company = guestShowcase.company(contactModel.companyId),
            typeName = guestShowcase.typeName(contactModel.furnitureType),
            onDismiss = { contactCode = null },
        )
    }
}

/** Чип над каруселью (типы мебели у гостя, дизайнеры у руководителя): полупрозрачный поверх камеры, выбранный — цветом темы. */
@Composable
private fun GuestTypeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun ModelThumbnail(
    model: FurnitureModel,
    isSelected: Boolean,
    isLoading: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .alpha(if (isLocked && !isLoading) 0.4f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val highlighted = isSelected || isLoading
        val boxModifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let {
                if (highlighted) it.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else it
            }
            .clickable(enabled = !isLocked) { onClick() }

        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            if (model.previewUrl != null) {
                AsyncImage(
                    model = "${ArWorldServerConfig.BASE_URL}${model.previewUrl}",
                    contentDescription = model.modelName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Chair, contentDescription = model.modelName, tint = Color.Gray)
            }
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(model.modelName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Плашка предупреждения о трекинге. Не перехватывает касания (нет pointerInput) — под ней
 *  сцена работает как обычно. Текст читается здесь, а не в родителе, чтобы смена текста
 *  перерисовывала только плашку. */
@Composable
private fun TrackingBanner(text: () -> String?, modifier: Modifier) {
    val current = text() ?: return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF2FFC107))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFF3E2723), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(current, color = Color(0xFF212121), style = MaterialTheme.typography.bodySmall)
    }
}

private val MeasureGreen = Color(0xFF00E676)

/** Перекрестие в центре экрана и отрезок между точками «рулетки». Рисуется поверх камеры
 *  (в сцену SceneView ничего не добавляется). Кадр читается в фазе рисования — каждый кадр
 *  перерисовывается только этот слой. Касания не перехватывает. */
@Composable
private fun MeasureReticleLayer(
    frame: () -> Frame?,
    pointA: () -> Position?,
    pointB: () -> Position?,
    live: () -> Position?,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width.toInt()
        val h = size.height.toInt()
        val center = Offset(size.width / 2f, size.height / 2f)
        val liveHit = live()
        val fixedB = pointB()
        val reticleColor = if (liveHit != null || fixedB != null) MeasureGreen else Color.White
        val arm = 22.dp.toPx()
        val gap = 6.dp.toPx()
        val stroke = 2.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 15.dp.toPx(), center = center, style = Stroke(4.dp.toPx()))
        drawCircle(reticleColor, radius = 15.dp.toPx(), center = center, style = Stroke(stroke))
        drawLine(reticleColor, center - Offset(arm, 0f), center - Offset(gap, 0f), stroke)
        drawLine(reticleColor, center + Offset(gap, 0f), center + Offset(arm, 0f), stroke)
        drawLine(reticleColor, center - Offset(0f, arm), center - Offset(0f, gap), stroke)
        drawLine(reticleColor, center + Offset(0f, gap), center + Offset(0f, arm), stroke)

        val camera = frame()?.camera ?: return@Canvas
        val a = pointA()?.let { projectToScreen(camera, it, w, h) }
        val b = (fixedB ?: liveHit)?.let { projectToScreen(camera, it, w, h) }
        if (a != null && b != null) {
            drawLine(Color.Black.copy(alpha = 0.4f), a, b, 6.dp.toPx())
            drawLine(MeasureGreen, a, b, 3.dp.toPx())
        }
        for (p in listOfNotNull(a, if (fixedB != null) b else null)) {
            drawCircle(Color.White, radius = 7.dp.toPx(), center = p)
            drawCircle(MeasureGreen, radius = 5.dp.toPx(), center = p)
        }
    }
}

/** Нижняя панель «рулетки»: шаги, живое расстояние, ввод реального размера и вывод. */
@Composable
private fun MeasurePanel(
    pointA: () -> Position?,
    pointB: () -> Position?,
    live: () -> Position?,
    realCmText: String,
    onRealCmTextChange: (String) -> Unit,
    verdict: String?,
    onSetPoint: () -> Unit,
    onCheck: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val a = pointA()
    val b = pointB()
    val liveHit = live()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Проверка масштаба", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
            when {
                a == null -> {
                    Text(
                        "Шаг 1. Наведи перекрестие на начало известного размера (край плитки, косяк проёма, " +
                                "отметка рулетки на полу) и нажми «Точка 1». Точнее всего — на полу, от 1 м.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                b == null -> {
                    val liveText = liveHit?.let { "≈ %.1f см".format(distanceBetween(a, it) * 100f) } ?: "—"
                    Text(
                        "Шаг 2. Наведи перекрестие на конец размера и нажми «Точка 2».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(liveText, style = MaterialTheme.typography.titleMedium)
                }
                else -> {
                    Text(
                        "Намерено: %.1f см".format(distanceBetween(a, b) * 100f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = realCmText,
                            onValueChange = { text -> onRealCmTextChange(text.filter { it.isDigit() || it == ',' || it == '.' }.take(6)) },
                            label = { Text("Реальный размер, см") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onCheck, enabled = realCmText.isNotBlank()) { Text("Проверить") }
                    }
                    if (verdict != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(verdict, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (b == null && liveHit == null) {
                Text(
                    "Перекрестие ни на что не попадает — наведи на пол или стену и медленно поводи камерой",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b == null) {
                    Button(onClick = onSetPoint, enabled = liveHit != null) {
                        Text(if (a == null) "Точка 1" else "Точка 2")
                    }
                }
                if (a != null) {
                    OutlinedButton(onClick = onReset) { Text("Заново") }
                }
            }
        }
    }
}

/**
 * Диагностика перекрытия: в центре экрана — расстояние по карте глубины ARCore и расстояние
 * до плоскости (пол/стена) по hit-test. Карта глубины — в ориентации кадра камеры, но центр
 * экрана всегда соответствует центру кадра, поэтому берём средний пиксель 5×5 в центре.
 * Глубина ARCore — расстояние вдоль оси камеры; в центре кадра оно совпадает с расстоянием луча.
 */
private fun probeDepthAtCenter(frame: Frame, viewW: Int, viewH: Int): String {
    val planeText = run {
        val hit = frame.hitTest(viewW / 2f, viewH / 2f).firstOrNull { r ->
            val t = r.trackable
            t is Plane && t.isPoseInPolygon(r.hitPose)
        } ?: return@run null
        val kind = if ((hit.trackable as Plane).type == Plane.Type.VERTICAL) "стена" else "пол"
        kind to hit.distance
    }
    val depthM: Float? = try {
        frame.acquireDepthImage16Bits().use { img ->
            val plane = img.planes[0]
            val buf = plane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
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
            if (n == 0) null else sum / n / 1000f
        }
    } catch (e: Exception) {
        null
    }
    val d = depthM?.let { "%.2f м".format(it) } ?: "нет"
    val p = planeText?.let { "%s %.2f м".format(it.first, it.second) } ?: "нет плоскости"
    val diff = if (depthM != null && planeText != null) {
        val cm = (planeText.second - depthM) * 100f
        "  разница %+.0f см".format(cm)
    } else ""
    return "Центр: глубина $d · $p$diff"
}

/** Подпись текущего допуска для строки диагностики, например « · допуск 5+3/м». */
private fun biasLabel(index: Int): String {
    val (bm, bpm) = OCCLUSION_BIAS_PRESETS[index]
    return " · допуск %.0f+%.0f/м".format(bm * 100f, bpm * 100f)
}