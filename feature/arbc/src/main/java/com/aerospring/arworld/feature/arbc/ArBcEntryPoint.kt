package com.aerospring.arworld.feature.arbc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Маршрут этой фичи объявлен здесь же, внутри модуля — а не в общем
 *  ArWorldDestinations, чтобы feature:arbc ничего не менял в шаред-коде,
 *  общем для всех разделов приложения. */
const val AR_BUSINESS_CARDS_ROUTE = "ar_business_cards"

/**
 * Три внутренних состояния раздела — сканер / витрина активных визиток / сцена
 * конкретного клиента — живут целиком внутри этой фичи, НЕ как отдельные
 * top-level route в общей навигации (тот же принцип, что и раньше: feature:arbc
 * не трогает ArWorldDestinations). И сканер, и витрина — два равноправных способа
 * попасть в сцену клиента; "назад" из сцены всегда возвращает на сканер (не на
 * витрину — так пользователь может пересканировать другую визитку сразу).
 */
private sealed interface ArBcEntryState {
    data object Scanner : ArBcEntryState
    data object Showcase : ArBcEntryState
    data class Scene(val clientId: String) : ArBcEntryState
}

@Composable
fun ArBcEntryPoint(onExit: () -> Unit) {
    var state by remember { mutableStateOf<ArBcEntryState>(ArBcEntryState.Scanner) }

    when (val current = state) {
        is ArBcEntryState.Scanner -> ArBcQrScanScreen(
            onScanned = { clientId -> state = ArBcEntryState.Scene(clientId) },
            onShowcaseClick = { state = ArBcEntryState.Showcase },
            onBackClick = onExit
        )

        is ArBcEntryState.Showcase -> ArBcShowcaseScreen(
            onClientSelected = { clientId -> state = ArBcEntryState.Scene(clientId) },
            onBackClick = { state = ArBcEntryState.Scanner }
        )

        is ArBcEntryState.Scene -> ArBcScreen(
            clientId = current.clientId,
            onBackClick = { state = ArBcEntryState.Scanner }
        )
    }
}