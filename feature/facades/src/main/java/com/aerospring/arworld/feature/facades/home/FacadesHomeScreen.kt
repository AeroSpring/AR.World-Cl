package com.aerospring.arworld.feature.facades.home

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import com.aerospring.arworld.core.ui.component.SectionHelpDialog
import com.aerospring.arworld.core.ui.component.SectionHelpPrefs
import com.aerospring.arworld.feature.facades.FACADES_HELP_KEY
import com.aerospring.arworld.feature.facades.facadesHelpItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aerospring.arworld.core.data.geo.GeoMath
import com.aerospring.arworld.feature.facades.data.DownloadProgress
import com.aerospring.arworld.feature.facades.data.FacadePackage
import com.aerospring.arworld.feature.facades.data.FacadeStats
import com.aerospring.arworld.feature.facades.data.FacadeStore
import com.aerospring.arworld.feature.facades.data.FacadesApi
import com.aerospring.arworld.feature.facades.data.InstallerPrefs
import com.aerospring.arworld.feature.facades.data.InstallerSession
import com.aerospring.arworld.feature.facades.data.NearbyFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Строка списка: фасад с сервера и/или его копия на телефоне. */
private data class FacadeRow(
    val id: String,
    val title: String,
    val distanceM: Int?,
    val discoveryRadiusM: Int,
    val serverVersion: Long?,
    val cached: FacadePackage?,
    val complete: Boolean
) {
    val needsUpdate get() = cached == null || !complete || (serverVersion != null && serverVersion > cached.version)
    val nearEnough get() = distanceM != null && distanceM <= discoveryRadiusM
}

/**
 * Главный экран раздела: фасады рядом и их копии на телефоне.
 *
 * Фасад скачивается целиком заранее (эталоны, маска, модели всех сцен), с «Загрузка N%»:
 * у самого фасада мобильный интернет может не работать. Если фасад в радиусе обнаружения —
 * скачивание начинается само. Без связи показываем то, что уже скачано.
 *
 * «Открыть» — AR-просмотр (шаг 3.2), «Что скачано» — сводка копии на телефоне.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FacadesHomeScreen(
    onExit: () -> Unit,
    onOpenStand: () -> Unit,
    onOpenFacade: (String) -> Unit,
    /** Монтажник: предпросмотр фасада с черновиками (копия в отдельной папке) и панель монтажа. */
    onOpenPreview: (String) -> Unit
) {
    val context = LocalContext.current
    val store = remember { FacadeStore(context) }
    val scope = rememberCoroutineScope()
    val locationAllowed = remember {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val location by rememberFacadesLocation(enabled = locationAllowed)

    var nearby by remember { mutableStateOf<List<NearbyFacade>?>(null) }
    var cached by remember { mutableStateOf(store.cachedPackages()) }
    var networkProblem by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var lastFetchAt by remember { mutableStateOf<Location?>(null) }
    val progress = remember { mutableStateMapOf<String, DownloadProgress>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var opened by remember { mutableStateOf<FacadePackage?>(null) }
    val downloadLock = remember { Mutex() }
    var installer by remember { mutableStateOf<InstallerSession?>(InstallerPrefs.load(context.applicationContext)) }
    var installerLogin by remember { mutableStateOf(false) }
    // Справка «i»: сама — один раз при первом входе в раздел, дальше по кнопке.
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!SectionHelpPrefs.wasShown(context, FACADES_HELP_KEY)) {
            showHelp = true
            SectionHelpPrefs.markShown(context, FACADES_HELP_KEY)
        }
    }

    fun refreshCached() { cached = store.cachedPackages() }

    fun download(id: String) {
        if (progress.containsKey(id)) return
        progress[id] = DownloadProgress(0, 0, 0, 0)
        errors.remove(id)
        scope.launch {
            try {
                downloadLock.withLock {   // по одному фасаду: так каждый скачается быстрее
                    store.download(id) { p -> scope.launch { if (progress.containsKey(id)) progress[id] = p } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                errors[id] = if (e.message == "HTTP 404") "Фасад сейчас не показывается" else "Нет связи с сервером: скачивание прервано"
            } catch (e: Exception) {
                errors[id] = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                progress.remove(id)
                refreshCached()
            }
        }
    }

    suspend fun fetchNearby(loc: Location) {
        loading = true
        try {
            val list = FacadesApi.nearby(loc.latitude, loc.longitude)
            nearby = list
            networkProblem = null
            withContext(Dispatchers.IO) { store.saveNearby(list) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            networkProblem = "Нет связи с сервером — показаны фасады, скачанные раньше."
            if (nearby == null) nearby = store.cachedNearby()
        } finally {
            loading = false
        }
    }

    // Список «рядом» — при первом определении места и после смещения больше чем на 200 м.
    // Счётчики, накопленные у фасада без связи, — на сервер при входе в раздел и после выхода из AR.
    LaunchedEffect(Unit) { FacadeStats.flush(context.applicationContext) }

    // Пока список на экране — раз в минуту спрашиваем сервер заново: правки в студии (новые модели,
    // сцены, эталоны) видны сразу, а фасад в радиусе обнаружения сам докачивается. В фоне не опрашиваем.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(60_000)
                location?.let { loc -> lastFetchAt = loc; fetchNearby(loc) }
            }
        }
    }

    LaunchedEffect(location) {
        val loc = location ?: return@LaunchedEffect
        val prev = lastFetchAt
        if (prev == null || prev.distanceTo(loc) > 200f) {
            lastFetchAt = loc
            fetchNearby(loc)
        }
    }

    val rows = remember(nearby, cached, location) {
        val byId = cached.associateBy { it.facadeId }
        val fromServer = nearby.orEmpty().map { n ->
            val c = byId[n.id]
            FacadeRow(n.id, n.title, n.distanceM, n.discoveryRadiusM, n.version, c, c != null && store.isComplete(c))
        }
        val serverIds = fromServer.map { it.id }.toSet()
        val onlyCached = cached.filter { it.facadeId !in serverIds }.map { c ->
            val loc = location
            val d = if (loc != null && c.geo.lat != null && c.geo.lng != null)
                GeoMath.distanceMeters(loc.latitude, loc.longitude, c.geo.lat, c.geo.lng).toInt() else null
            FacadeRow(c.facadeId, c.title, d, c.geo.radiusM, null, c, store.isComplete(c))
        }
        (fromServer + onlyCached).sortedWith(compareBy(nullsLast<Int>()) { it.distanceM })
    }

    // Фасад в радиусе обнаружения — скачиваем сами, пока есть связь.
    LaunchedEffect(rows, networkProblem) {
        if (networkProblem != null) return@LaunchedEffect
        rows.filter { it.serverVersion != null && it.nearEnough && it.needsUpdate && it.id !in errors }.forEach { download(it.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AR.Фасады") },
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    // Монтаж — для сотрудников и монтажников заказчика; прохожему кнопка не мешает.
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Outlined.Info, contentDescription = "Справка") }
                    if (installer == null) TextButton(onClick = { installerLogin = true }) { Text("Монтаж") }
                    // Диагностика (стенд шага 1) — только монтажнику: прохожему она ни к чему.
                    if (installer != null) TextButton(onClick = onOpenStand) { Text("Диагностика") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            installer?.let { s ->
                item(key = "installer") {
                    InstallerBlock(session = s, onLogout = { installer = null }, onOpenPreview = onOpenPreview)
                }
            }
            item {
                val hint = when {
                    !locationAllowed -> "Нет доступа к геолокации: показываем только скачанные фасады."
                    location == null -> "Определяем, где вы…"
                    loading -> "Ищем фасады рядом…"
                    else -> null
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                networkProblem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
            if (rows.isEmpty() && !loading) {
                item {
                    Text(
                        if (location == null && locationAllowed) "" else "Рядом нет фасадов AR.Мир. Они появятся здесь, когда вы окажетесь недалеко от них.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            items(rows, key = { it.id }) { row ->
                FacadeCard(
                    row = row,
                    progress = progress[row.id],
                    error = errors[row.id],
                    onDownload = { download(row.id) },
                    onOpen = { onOpenFacade(row.id) },
                    onSummary = { opened = row.cached }
                )
            }
        }
    }

    if (showHelp) SectionHelpDialog(title = "AR.Фасады", items = facadesHelpItems, onDismiss = { showHelp = false })

    if (installerLogin) {
        InstallerLoginDialog(onLoggedIn = { installer = it; installerLogin = false }, onDismiss = { installerLogin = false })
    }

    opened?.let { pkg ->
        PackageDialog(
            pkg = pkg,
            sizeOnPhone = store.sizeOnPhone(pkg.facadeId),
            onDelete = { store.delete(pkg.facadeId); refreshCached(); opened = null },
            onClose = { opened = null }
        )
    }
}

@Composable
private fun FacadeCard(
    row: FacadeRow,
    progress: DownloadProgress?,
    error: String?,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onSummary: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = row.cached != null && row.complete && progress == null) { onOpen() }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                row.distanceM?.let { Text(formatDistance(it), style = MaterialTheme.typography.bodyMedium) }
            }
            val status = when {
                progress != null -> null
                row.cached == null -> "Не скачан"
                !row.complete -> "Скачан не полностью"
                row.needsUpdate -> "Есть обновление"
                else -> "Скачан — работает без интернета"
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (progress.fileCount == 0) "Загрузка: готовимся…"
                    else "Загрузка ${progress.percent}% · файл ${progress.fileIndex} из ${progress.fileCount}" +
                        (if (progress.totalBytes > 0) " · ${formatSize(progress.doneBytes)} из ${formatSize(progress.totalBytes)}" else ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (progress == null) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.serverVersion != null && row.needsUpdate) {
                        Button(onClick = onDownload) { Text(if (row.cached == null) "Скачать" else "Обновить") }
                    }
                    if (row.cached != null && row.complete) {
                        Button(onClick = onOpen) { Text("Открыть") }
                        OutlinedButton(onClick = onSummary) { Text("Что скачано") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageDialog(pkg: FacadePackage, sizeOnPhone: Long, onDelete: () -> Unit, onClose: () -> Unit) {
    val now = pkg.scenes.firstOrNull { it.id == pkg.activeSceneNow }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(pkg.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("На телефоне: ${formatSize(sizeOnPhone)}")
                Text("Эталонов: ${pkg.references.size} · маска: ${if (pkg.mask != null) "есть" else "нет"}")
                Text("Сцен: ${pkg.scenes.size}" + (now?.let { " · сейчас: «${it.title}»" } ?: ""))
                pkg.scenes.forEach { s ->
                    Text("• ${s.title}: моделей ${s.models.size}" + (if (s.schedules.isNotEmpty()) ", по расписанию" else if (s.isDefault) ", основная" else ""),
                        style = MaterialTheme.typography.bodySmall)
                    s.ad?.line?.let { Text("   $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text("ИИ-помощник: ${if (pkg.ai.enabled) "есть" else "нет"}")
                pkg.ad.testBanner?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Удалить с телефона") } }
    )
}

private fun formatDistance(m: Int): String =
    if (m < 1000) "$m м" else String.format(java.util.Locale.forLanguageTag("ru"), "%.1f км", m / 1000.0)

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(java.util.Locale.forLanguageTag("ru"), "%.1f МБ", bytes / 1048576.0)
    else "${maxOf(1, bytes / 1024)} КБ"
