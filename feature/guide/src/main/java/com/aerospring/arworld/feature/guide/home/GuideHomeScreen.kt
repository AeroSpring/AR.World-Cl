package com.aerospring.arworld.feature.guide.home

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerospring.arworld.core.ui.component.SectionHelpDialog
import com.aerospring.arworld.feature.guide.R
import com.aerospring.arworld.feature.guide.guideHelpItems
import com.aerospring.arworld.feature.guide.data.GuideApi
import com.aerospring.arworld.feature.guide.data.GuideBuildingItem
import com.aerospring.arworld.feature.guide.data.GuideBundle
import com.aerospring.arworld.feature.guide.data.GuideLocation
import com.aerospring.arworld.feature.guide.data.GuideProgress
import com.aerospring.arworld.feature.guide.data.GuideStore
import kotlinx.coroutines.launch

private const val PREFS = "guide_prefs"
private const val KEY_LOCATION_ASKED = "location_asked"

private val CardBg = Color(0xD91C1C22)
private val TextDim = Color(0xFFB8BCC6)
private val Accent = Color(0xFF8AB4FF)

/**
 * Здания с путеводителем.
 *  - Есть разрешение на примерное местоположение — только ТЦ рядом (сервер: в радиусе 50 км), ближние первыми.
 *  - Плюс уже скачанные на телефон (работают без сети).
 *  - Обновить: кнопка ⟳ вверху или потянуть список вниз. Нет связи — понятная карточка с «Повторить».
 *  - Касание: копии нет или на сервере новее — скачать с процентами, затем открыть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuideHomeScreen(store: GuideStore, onExit: () -> Unit, onOpen: (GuideBundle) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var server by remember { mutableStateOf<List<GuideBuildingItem>?>(null) }
    var nearby by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var cached by remember(reload) { mutableStateOf(store.cachedBundles()) }
    var loadingSlug by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<GuideProgress?>(null) }
    var progressAt by remember { mutableStateOf(0L) }
    var slow by remember { mutableStateOf(false) }
    var failedSlug by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var hasLocation by remember { mutableStateOf(GuideLocation.hasPermission(context)) }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = GuideLocation.hasPermission(context)
        reload++
    }
    LaunchedEffect(Unit) {
        // Один раз спрашиваем сами; дальше — только по кнопке «Разрешить».
        if (!hasLocation && !prefs.getBoolean(KEY_LOCATION_ASKED, false)) {
            prefs.edit().putBoolean(KEY_LOCATION_ASKED, true).apply()
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    LaunchedEffect(reload) {
        loading = true
        try {
            val loc = GuideLocation.approximate(context)
            server = GuideApi.buildings(loc?.latitude, loc?.longitude)
            nearby = loc != null
            offline = false
        } catch (e: Exception) {
            offline = true
        } finally {
            loading = false
        }
    }

    fun open(slug: String, serverVersion: Long?) {
        val copy = cached.firstOrNull { it.slug == slug }
        if (copy != null && store.isComplete(copy) && (serverVersion == null || serverVersion <= copy.bundleVersion)) {
            onOpen(copy)
            return
        }
        failedSlug = null
        loadingSlug = slug
        progress = GuideProgress(0, 0)
        progressAt = System.currentTimeMillis()
        scope.launch {
            try {
                val b = store.download(slug) { p -> scope.launch { progress = p; progressAt = System.currentTimeMillis() } }
                cached = store.cachedBundles()
                onOpen(b)
            } catch (e: Exception) {
                if (copy != null && store.isComplete(copy)) onOpen(copy) else failedSlug = slug
            } finally {
                loadingSlug = null
                progress = null
            }
        }
    }

    // Скачивание стоит дольше 6 секунд — честно говорим, что связь медленная (а не «зависло»).
    LaunchedEffect(loadingSlug) {
        slow = false
        while (loadingSlug != null) {
            kotlinx.coroutines.delay(1_000)
            slow = System.currentTimeMillis() - progressAt > 6_000
        }
        slow = false
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        // Фон — картинка раздела, размытая и затемнённая (размытие — Android 12+, ниже — просто темнее).
        Image(
            painter = painterResource(R.drawable.guide_home_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(28.dp)
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xB3101014), Color(0xE6101014), Color(0xF2101014)))
            )
        )

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
                Text("Путеводитель по ТЦ", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { reload++ }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "Обновить", tint = Color.White) }
                IconButton(onClick = { showHelp = true }) { Icon(Icons.Outlined.Info, "Справка", tint = Color.White) }
            }
            PullToRefreshBox(isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val serverList = server
                    // Здания: с сервера + скачанные, которых в ответе сервера нет (нет сети или далеко).
                    val items = buildList<Pair<GuideBuildingItem, Long?>> {
                        serverList?.forEach { add(it to it.bundleVersion) }
                        cached.filter { c -> serverList?.none { it.slug == c.slug } != false }
                            .forEach { add(GuideBuildingItem(it.slug, it.name, it.address) to null) }
                    }

                    if (offline) {
                        Notice(
                            icon = { Icon(Icons.Outlined.CloudOff, null, tint = Color.White) },
                            text = "Нет связи с сервером. Проверьте интернет; если включён VPN — попробуйте без него." +
                                if (cached.isNotEmpty()) " Скачанные здания работают и без сети." else "",
                            button = "Повторить",
                            onClick = { reload++ }
                        )
                    }
                    if (!offline && serverList != null && !hasLocation) {
                        Notice(
                            icon = { Icon(Icons.Outlined.LocationOn, null, tint = Color.White) },
                            text = "Покажем торговые центры рядом с вами — разрешите доступ к примерному местоположению. " +
                                "Точное место не нужно и никуда не сохраняется.",
                            button = "Разрешить",
                            onClick = { askLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)) }
                        )
                    }
                    if (serverList != null && nearby && serverList.isNotEmpty()) {
                        Text("Рядом с вами", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (items.isEmpty() && !loading) {
                        Text(
                            if (nearby) "Рядом пока нет торговых центров с путеводителем. Мы добавляем новые — загляните позже."
                            else "Пока нет зданий с путеводителем.",
                            color = Color.White, modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                    items.forEach { (b, serverVersion) ->
                        val copy = cached.firstOrNull { it.slug == b.slug }
                        val status = when {
                            copy == null -> "Скачается при открытии"
                            serverVersion != null && serverVersion > copy.bundleVersion -> "Есть обновление — скачается при открытии"
                            else -> "Скачано, работает без сети"
                        }
                        Column(
                            Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp))
                                .clickable(enabled = loadingSlug == null) { open(b.slug, serverVersion) }
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                b.distanceKm?.let { Text(distanceText(it), color = Accent, fontSize = 13.sp) }
                            }
                            val where = listOf(b.city, b.address).filter { it.isNotBlank() }.joinToString(", ")
                            if (where.isNotBlank()) Text(where, color = TextDim, fontSize = 13.sp)
                            Text(status, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            if (loadingSlug == b.slug) {
                                val p = progress
                                Text(
                                    if (p == null || p.totalFiles == 0) "Загрузка: готовимся…"
                                    else "Загрузка ${p.percent}% · файл ${minOf(p.doneFiles + 1, p.totalFiles)} из ${p.totalFiles}",
                                    color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
                                )
                                if (slow) Text("Связь медленная — загрузка идёт, подождите немного.", color = TextDim, fontSize = 12.sp)
                                LinearProgressIndicator(progress = { (p?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                            if (failedSlug == b.slug && loadingSlug == null) {
                                Text("Не получилось скачать: нет связи. Проверьте интернет; если включён VPN — попробуйте без него.",
                                    color = Color(0xFFFFB4A9), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                                Button(onClick = { open(b.slug, serverVersion) }, modifier = Modifier.padding(top = 6.dp)) {
                                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp))
                                    Text("  Повторить")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (showHelp) SectionHelpDialog(title = "Путеводитель по ТЦ", items = guideHelpItems, onDismiss = { showHelp = false })
}

@Composable
private fun Notice(icon: @Composable () -> Unit, text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icon()
            Text(text, color = Color.White, fontSize = 14.sp)
        }
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 10.dp)) { Text(button, color = Color.White) }
    }
}

/** «350 м» / «1,2 км» / «35 км». */
private fun distanceText(km: Double): String = when {
    km < 1.0 -> "${(km * 1000).toInt() / 10 * 10} м"
    km < 10.0 -> "%.1f км".format(km).replace('.', ',')
    else -> "${km.toInt()} км"
}
