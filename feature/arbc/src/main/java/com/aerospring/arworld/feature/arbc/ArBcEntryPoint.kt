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
 * Раздел открывается ВСЕГДА со сканера (никакого захардкоженного clientId и никакого
 * системного deeplink/App Links — см. ArBcQrScanScreen). Пока clientId не распознан —
 * показываем сканер; после успешного скана — переключаемся на саму сцену. "Назад" из
 * сцены возвращает к сканеру (пересканировать другую визитку), а не сразу на главный экран.
 */
@Composable
fun ArBcEntryPoint(onExit: () -> Unit) {
    var clientId by remember { mutableStateOf<String?>(null) }
    val currentClientId = clientId

    if (currentClientId == null) {
        ArBcQrScanScreen(
            onScanned = { clientId = it },
            onBackClick = onExit
        )
    } else {
        ArBcScreen(
            clientId = currentClientId,
            onBackClick = { clientId = null }
        )
    }
}