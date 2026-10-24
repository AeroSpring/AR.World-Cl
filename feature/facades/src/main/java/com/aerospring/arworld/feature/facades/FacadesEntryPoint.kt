package com.aerospring.arworld.feature.facades

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.aerospring.arworld.feature.facades.ar.FacadeArScreen
import com.aerospring.arworld.feature.facades.data.FacadeStore
import com.aerospring.arworld.feature.facades.data.InstallerPrefs
import com.aerospring.arworld.feature.facades.home.FacadesHomeScreen
import com.aerospring.arworld.feature.facades.home.PREVIEW_STORE
import com.aerospring.arworld.feature.facades.permissions.RequireFacadesPermissions
import com.aerospring.arworld.feature.facades.stand.FacadesStandScreen

/** Маршрут раздела. Живёт внутри модуля, как у arbc и furniture (ArWorldDestinations не трогаем). */
const val FACADES_ROUTE = "ar_facades"

/**
 * Вход в раздел AR.Фасады.
 *
 * Главный экран — фасады рядом и их копии на телефоне (шаг 3.1), «Открыть» — AR-просмотр (шаг 3.2). Диагностический стенд
 * шага 1 остаётся доступен кнопкой «Диагностика» — для уличных проверок; перед релизом
 * раздела кнопку спрятать (чек-лист релиза).
 */
@Composable
fun FacadesEntryPoint(onExit: () -> Unit) {
    var standOpen by rememberSaveable { mutableStateOf(false) }
    var openedFacadeId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedPreview by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val store = remember { FacadeStore(context.applicationContext) }
    val previewStore = remember { FacadeStore(context.applicationContext, PREVIEW_STORE) }
    RequireFacadesPermissions(onExit = onExit) { locationGranted ->
        val opened = openedFacadeId
        when {
            standOpen -> {
                BackHandler { standOpen = false }
                FacadesStandScreen(locationGranted = locationGranted, onExit = { standOpen = false })
            }
            opened != null -> {
                // Предпросмотр монтажника — из отдельной копии (с черновиками) и с панелью «Монтаж».
                val installer = remember(opened, openedPreview) { if (openedPreview) InstallerPrefs.load(context.applicationContext) else null }
                FacadeArScreen(
                    facadeId = opened,
                    store = if (openedPreview) previewStore else store,
                    installer = installer,
                    onExit = { openedFacadeId = null; openedPreview = false }
                )
            }
            else -> FacadesHomeScreen(
                onExit = onExit,
                onOpenStand = { standOpen = true },
                onOpenFacade = { openedPreview = false; openedFacadeId = it },
                onOpenPreview = { openedPreview = true; openedFacadeId = it }
            )
        }
    }
}
