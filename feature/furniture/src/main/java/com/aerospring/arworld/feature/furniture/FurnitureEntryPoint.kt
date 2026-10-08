package com.aerospring.arworld.feature.furniture

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.aerospring.arworld.feature.furniture.data.FurnitureAuthRepository
import com.aerospring.arworld.feature.furniture.data.FurnitureTokenStore
import com.aerospring.arworld.feature.furniture.ui.FurnitureCatalogScreen
import com.aerospring.arworld.feature.furniture.ui.FurnitureGuestScreen
import com.aerospring.arworld.feature.furniture.ui.FurnitureLoginScreen
import kotlinx.coroutines.launch

const val FURNITURE_ROUTE = "ar_furniture"

@Composable
fun FurnitureEntryPoint(onExit: () -> Unit) {
    val context = LocalContext.current
    val tokenStore = remember { FurnitureTokenStore(context) }
    val scope = rememberCoroutineScope()

    var session by remember {
        mutableStateOf(
            tokenStore.getToken()?.let { token -> tokenStore.getDisplayName()?.let { it to token } }
        )
    }

    // Гостевой режим (витрина без входа). Только когда дизайнер не залогинен.
    var guestMode by remember { mutableStateOf(false) }

    fun clearSession() {
        tokenStore.clear()
        session = null
    }

    val current = session
    if (current == null && guestMode) {
        FurnitureGuestScreen(
            onLoginClick = { guestMode = false },
            onBackClick = onExit,
        )
    } else if (current == null) {
        FurnitureLoginScreen(
            onLoggedIn = { token, displayName ->
                tokenStore.saveToken(token, displayName)
                session = displayName to token
            },
            onGuestClick = { guestMode = true },
        )
    } else {
        val (displayName, token) = current
        FurnitureCatalogScreen(
            token = token,
            displayName = displayName,
            onUnauthorized = { clearSession() },
            onLogoutClick = {
                scope.launch { FurnitureAuthRepository.logout(token) }
                clearSession()
            },
            onBackClick = onExit,
        )
    }
}