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
import com.aerospring.arworld.feature.facades.home.FacadesHomeScreen
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
    val context = LocalContext.current
    val store = remember { FacadeStore(context.applicationContext) }
    RequireFacadesPermissions(onExit = onExit) { locationGranted ->
        val opened = openedFacadeId
        when {
            standOpen -> {
                BackHandler { standOpen = false }
                FacadesStandScreen(locationGranted = locationGranted, onExit = { standOpen = false })
            }
            opened != null -> FacadeArScreen(facadeId = opened, store = store, onExit = { openedFacadeId = null })
            else -> FacadesHomeScreen(onExit = onExit, onOpenStand = { standOpen = true }, onOpenFacade = { openedFacadeId = it })
        }
    }
}
