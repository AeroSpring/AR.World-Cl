package com.aerospring.arworld.feature.furniture.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.feature.furniture.data.FurnitureAuthRepository
import com.aerospring.arworld.feature.furniture.data.FurnitureLoginResult
import kotlinx.coroutines.launch

@Composable
fun FurnitureLoginScreen(
    // role — FurnitureRole.DESIGNER или FurnitureRole.MANAGER (определяет сервер).
    onLoggedIn: (token: String, displayName: String, role: String) -> Unit,
    // Гостевой вход (витрина). null — кнопки нет.
    onGuestClick: (() -> Unit)? = null,
) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (login.isBlank() || password.isBlank() || isLoading) return
        errorMessage = null
        isLoading = true
        scope.launch {
            when (val result = FurnitureAuthRepository.login(login.trim(), password)) {
                is FurnitureLoginResult.Success -> {
                    isLoading = false
                    onLoggedIn(result.token, result.displayName, result.role)
                }
                is FurnitureLoginResult.Error -> {
                    isLoading = false
                    errorMessage = result.message
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("AR.Мебель", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text("Логин") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
            }

            Button(onClick = { submit() }, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Войти: дизайнер или руководитель")
                }
            }

            if (onGuestClick != null) {
                Spacer(Modifier.height(28.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                Text(
                    "Расставьте мебель мебельных компаний у себя в комнате в натуральную величину " +
                            "и закажите понравившуюся. Без регистрации.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                // Такая же заметная, как кнопка входа: гостей будет больше, чем дизайнеров.
                Button(
                    onClick = onGuestClick,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Примерить мебель в своей комнате")
                }
            }
        }
    }
}