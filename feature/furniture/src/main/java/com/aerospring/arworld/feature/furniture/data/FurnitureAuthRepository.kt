package com.aerospring.arworld.feature.furniture.data

import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** Роль, которую сервер определил при входе. Значения совпадают с полем role в ответе. */
object FurnitureRole {
    const val DESIGNER = "designer"
    const val MANAGER = "manager"
}

sealed class FurnitureLoginResult {
    data class Success(
        val token: String,
        val displayName: String,
        val role: String = FurnitureRole.DESIGNER,
    ) : FurnitureLoginResult()

    data class Error(val message: String) : FurnitureLoginResult()
}

@Serializable
private data class LoginRequestBody(val login: String, val password: String)

@Serializable
private data class LoginResponseBody(
    val token: String,
    val role: String = FurnitureRole.DESIGNER,
    val displayName: String = "",
    val clientName: String = "",
)

/**
 * Вход в приложение — общий для дизайнера и руководителя: одна форма,
 * роль определяет сервер (/furniture/app/auth/login). По аналогии с
 * ArBcRepository — простым HttpURLConnection, без Retrofit/Ktor.
 */
object FurnitureAuthRepository {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(login: String, password: String): FurnitureLoginResult =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("${ArWorldServerConfig.BASE_URL}/furniture/app/auth/login")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                val body = json.encodeToString(LoginRequestBody(login, password))
                connection.outputStream.use { it.write(body.toByteArray()) }

                when (connection.responseCode) {
                    200 -> Unit
                    401 -> return@withContext FurnitureLoginResult.Error("Неверный логин или пароль")
                    403 -> return@withContext FurnitureLoginResult.Error("Вход заблокирован руководителем")
                    else -> return@withContext FurnitureLoginResult.Error("Сервер ответил ${connection.responseCode}")
                }

                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString(LoginResponseBody.serializer(), raw)
                // Руководителю показываем название компании, если имени нет.
                val name = parsed.displayName.ifBlank { parsed.clientName }
                FurnitureLoginResult.Success(parsed.token, name, parsed.role)
            } catch (e: Exception) {
                FurnitureLoginResult.Error(e.message ?: "Ошибка подключения к серверу")
            }
        }

    /** Best-effort — если сети нет, всё равно чистим токен локально в вызывающем коде.
     *  Общий выход: сервер убирает токен любой роли. */
    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        try {
            val connection = URL("${ArWorldServerConfig.BASE_URL}/furniture/app/auth/logout")
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