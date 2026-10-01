package com.aerospring.arworld.feature.furniture.data

import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

sealed class FurnitureLoginResult {
    data class Success(val token: String, val displayName: String) : FurnitureLoginResult()
    data class Error(val message: String) : FurnitureLoginResult()
}

@Serializable
private data class LoginRequestBody(val login: String, val password: String)

@Serializable
private data class LoginResponseBody(val token: String, val displayName: String)

/**
 * Авторизация дизайнера. По аналогии с ArBcRepository — простым
 * HttpURLConnection, без Retrofit/Ktor (в проекте пока нет общего
 * HTTP-клиента для JSON-эндпоинтов).
 */
object FurnitureAuthRepository {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(login: String, password: String): FurnitureLoginResult =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("${ArWorldServerConfig.BASE_URL}/furniture/auth/login")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                val body = json.encodeToString(LoginRequestBody(login, password))
                connection.outputStream.use { it.write(body.toByteArray()) }

                if (connection.responseCode != 200) {
                    return@withContext FurnitureLoginResult.Error(
                        if (connection.responseCode == 401) "Неверный логин или пароль"
                        else "Сервер ответил ${connection.responseCode}"
                    )
                }

                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString(LoginResponseBody.serializer(), raw)
                FurnitureLoginResult.Success(parsed.token, parsed.displayName)
            } catch (e: Exception) {
                FurnitureLoginResult.Error(e.message ?: "Ошибка подключения к серверу")
            }
        }

    /** Best-effort — если сети нет, всё равно чистим токен локально в вызывающем коде. */
    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        try {
            val connection = URL("${ArWorldServerConfig.BASE_URL}/furniture/auth/logout")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.connect()
            connection.responseCode
        } catch (_: Exception) {
            // не критично — локальная очистка токена всё равно произойдёт
        }
    }
}