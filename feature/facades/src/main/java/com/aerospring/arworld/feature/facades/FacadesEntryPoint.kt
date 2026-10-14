package com.aerospring.arworld.feature.facades

import androidx.compose.runtime.Composable
import com.aerospring.arworld.feature.facades.permissions.RequireFacadesPermissions
import com.aerospring.arworld.feature.facades.stand.FacadesStandScreen

/** Маршрут раздела. Живёт внутри модуля, как у arbc и furniture (ArWorldDestinations не трогаем). */
const val FACADES_ROUTE = "ar_facades"

/**
 * Вход в раздел AR.Фасады.
 *
 * Шаг 1 плана: пока здесь только диагностический стенд (глубина, Scene Semantics,
 * Geospatial, запись и воспроизведение AR-сессий). Рабочие экраны раздела появятся позже.
 */
@Composable
fun FacadesEntryPoint(onExit: () -> Unit) {
    RequireFacadesPermissions(onExit = onExit) { locationGranted ->
        FacadesStandScreen(locationGranted = locationGranted, onExit = onExit)
    }
}
