package com.aerospring.arworld.feature.furniture

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.aerospring.arworld.feature.furniture.data.FurnitureTokenStore
import com.aerospring.arworld.feature.furniture.ui.FurnitureLoginScreen

/** Маршрут объявлен здесь же, внутри модуля — не в общем ArWorldDestinations,
 *  по уже принятому в проекте правилу (см. AR_BUSINESS_CARDS_ROUTE в arbc). */
const val FURNITURE_ROUTE = "ar_furniture"

/**
 * Если токен уже сохранён локально — сразу открываем каталог моделей, минуя
 * логин. Если он успел протухнуть на сервере — это обнаружится на первом же
 * запросе каталога (следующая порция), и экран каталога сам вернёт на логин.
 */
@Composable
fun FurnitureEntryPoint(onExit: () -> Unit) {
    val context = LocalContext.current
    val tokenStore = remember { FurnitureTokenStore(context) }
    var session by remember {
        mutableStateOf(
            tokenStore.getToken()?.let { token -> tokenStore.getDisplayName()?.let { it to token } }
        )
    }

    val current = session
    if (current == null) {
        FurnitureLoginScreen(
            onLoggedIn = { token, displayName ->
                tokenStore.saveToken(token, displayName)
                session = displayName to token
            }
        )
    } else {
        // Каталог моделей — следующая порция.
    }
}