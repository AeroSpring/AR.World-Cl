package com.aerospring.arworld.feature.guide.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerospring.arworld.core.ui.component.SectionHelpDialog
import com.aerospring.arworld.feature.guide.guideHelpItems
import com.aerospring.arworld.feature.guide.data.GuideApi
import com.aerospring.arworld.feature.guide.data.GuideBuildingItem
import com.aerospring.arworld.feature.guide.data.GuideBundle
import com.aerospring.arworld.feature.guide.data.GuideProgress
import com.aerospring.arworld.feature.guide.data.GuideStore
import kotlinx.coroutines.launch

/**
 * Здания с путеводителем. Список — с сервера (только опубликованные) плюс уже скачанные на телефон
 * (работают без сети). Касание: копии нет или на сервере новее — скачать с процентами, затем открыть.
 */
@Composable
internal fun GuideHomeScreen(store: GuideStore, onExit: () -> Unit, onOpen: (GuideBundle) -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf<List<GuideBuildingItem>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var cached by remember(reload) { mutableStateOf(store.cachedBundles()) }
    var loadingSlug by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<GuideProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        server = try {
            offline = false
            GuideApi.buildings()
        } catch (e: Exception) {
            offline = true
            null
        }
    }

    fun open(slug: String, serverVersion: Long?) {
        val copy = cached.firstOrNull { it.slug == slug }
        if (copy != null && store.isComplete(copy) && (serverVersion == null || serverVersion <= copy.bundleVersion)) {
            onOpen(copy)
            return
        }
        error = null
        loadingSlug = slug
        progress = GuideProgress(0, 0)
        scope.launch {
            try {
                val b = store.download(slug) { p -> scope.launch { progress = p } }
                cached = store.cachedBundles()
                onOpen(b)
            } catch (e: Exception) {
                error = if (copy != null && store.isComplete(copy)) null else "Не получилось скачать: нет связи. Попробуйте ещё раз."
                if (copy != null && store.isComplete(copy)) onOpen(copy)
            } finally {
                loadingSlug = null
                progress = null
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Путеводитель по ТЦ", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { showHelp = true }) { Icon(Icons.Outlined.Info, "Справка") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val serverList = server
            // Здания: с сервера + скачанные, которых на сервере сейчас не видно (нет сети).
            val items = buildList<Pair<Triple<String, String, String>, Long?>> {
                serverList?.forEach { add(Triple(it.slug, it.name, it.address) to it.bundleVersion) }
                cached.filter { c -> serverList?.none { it.slug == c.slug } != false }
                    .forEach { add(Triple(it.slug, it.name, it.address) to null) }
            }
            if (serverList == null && !offline) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            if (offline) Text("Нет связи с сервером — показаны здания, скачанные на телефон.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (items.isEmpty() && (serverList != null || offline)) {
                Text("Пока нет зданий с путеводителем.", modifier = Modifier.padding(vertical = 16.dp))
                if (offline) TextButton(onClick = { reload++ }) { Text("Повторить") }
            }
            items.forEach { (info, serverVersion) ->
                val (slug, name, address) = info
                val copy = cached.firstOrNull { it.slug == slug }
                val status = when {
                    copy == null -> "Не скачано — скачается при открытии"
                    serverVersion != null && serverVersion > copy.bundleVersion -> "Есть обновление — скачается при открытии"
                    else -> "Скачано, работает без сети"
                }
                Card(Modifier.fillMaxWidth().clickable(enabled = loadingSlug == null) { open(slug, serverVersion) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        if (address.isNotBlank()) Text(address, fontSize = 13.sp)
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        if (loadingSlug == slug) {
                            val p = progress
                            Text(if (p == null || p.totalFiles == 0) "Загрузка: готовимся…" else "Загрузка ${p.percent}% · файл ${p.doneFiles} из ${p.totalFiles}",
                                fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            LinearProgressIndicator(progress = { (p?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (showHelp) SectionHelpDialog(title = "Путеводитель по ТЦ", items = guideHelpItems, onDismiss = { showHelp = false })
}
