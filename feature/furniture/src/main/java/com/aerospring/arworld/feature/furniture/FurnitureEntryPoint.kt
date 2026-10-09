package com.aerospring.arworld.feature.furniture

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.aerospring.arworld.feature.furniture.data.FurnitureAuthRepository
import com.aerospring.arworld.feature.furniture.data.FurnitureRole
import com.aerospring.arworld.feature.furniture.data.FurnitureTokenStore
import com.aerospring.arworld.feature.furniture.ui.FurnitureCatalogScreen
import com.aerospring.arworld.feature.furniture.ui.FurnitureGuestScreen
import com.aerospring.arworld.feature.furniture.ui.FurnitureLoginScreen
import kotlinx.coroutines.launch

const val FURNITURE_ROUTE = "ar_furniture"

/** Сохранённый вход: дизайнер или руководитель (роль определяет сервер). */
private data class FurnitureSession(val token: String, val displayName: String, val role: String) {
    val isManager: Boolean get() = role == FurnitureRole.MANAGER
}

@Composable
fun FurnitureEntryPoint(onExit: () -> Unit) {
    val context = LocalContext.current
    val tokenStore = remember { FurnitureTokenStore(context) }
    val scope = rememberCoroutineScope()

    var session by remember {
        mutableStateOf(
            tokenStore.getToken()?.let { token ->
                tokenStore.getDisplayName()?.let { name -> FurnitureSession(token, name, tokenStore.getRole()) }
            }
        )
    }

    // Гостевой режим (витрина без входа). Только когда никто не залогинен.
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
            onLoggedIn = { token, displayName, role ->
                tokenStore.saveToken(token, displayName, role)
                session = FurnitureSession(token, displayName, role)
            },
            onGuestClick = { guestMode = true },
        )
    } else {
        val token = current.token
        FurnitureCatalogScreen(
            token = token,
            displayName = current.displayName,
            isManager = current.isManager,
            onUnauthorized = { clearSession() },
            onLogoutClick = {
                scope.launch { FurnitureAuthRepository.logout(token) }
                clearSession()
            },
            onBackClick = onExit,
        )
    }
}