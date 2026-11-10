package com.aerospring.arworld.feature.guide

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aerospring.arworld.feature.guide.ar.GuideArScreen
import com.aerospring.arworld.feature.guide.data.GuideStore
import com.aerospring.arworld.feature.guide.home.GuideHomeScreen

/** Маршрут раздела. Живёт внутри модуля, как у arbc, furniture и facades (ArWorldDestinations не трогаем). */
const val GUIDE_ROUTE = "ar_indoor_guide"

/**
 * Вход в раздел «Путеводитель по ТЦ»: список зданий → AR-путеводитель по выбранному.
 * Список работает и без камеры; разрешение на камеру спрашиваем, когда открывают здание.
 */
@Composable
fun GuideEntryPoint(onExit: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GuideStore(context.applicationContext) }
    var openedSlug by rememberSaveable { mutableStateOf<String?>(null) }
    val slug = openedSlug
    val bundle = remember(slug) { slug?.let { store.cachedBundle(it) } }
    if (slug != null && bundle != null) {
        BackHandler { openedSlug = null }
        RequireGuideCamera(onExit = { openedSlug = null }) {
            GuideArScreen(bundle = bundle, store = store, onExit = { openedSlug = null })
        }
    } else {
        GuideHomeScreen(store = store, onExit = onExit, onOpen = { openedSlug = it.slug })
    }
}
