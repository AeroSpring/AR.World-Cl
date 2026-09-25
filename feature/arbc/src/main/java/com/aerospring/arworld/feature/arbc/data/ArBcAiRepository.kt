package com.aerospring.arworld.feature.arbc.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class AiChatRequestBody(val message: String)

@Serializable
private data class AiChatResponseBody(val reply: String)

sealed class ArBcAiChatResult {
    data class Success(val reply: String) : ArBcAiChatResult()
    data class Error(val message: String) : ArBcAiChatResult()
}

/** Тянет ответ ИИ-помощника с того же сервера, что и scene.json (POST /ai/{clientId}/chat).
 *  По аналогии с ArBcRepository — простым HttpURLConnection, без сторонних HTTP-библиотек. */
object ArBcAiRepository {
    private const val BASE_URL = "https://autoknowledge.tech"

    suspend fun chat(clientId: String, message: String): ArBcAiChatResult =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("$BASE_URL/ai/$clientId/chat")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.connectTimeout = 10_000
                connection.readTimeout = 35_000 // LLM-ответ может занять несколько секунд

                val requestJson = arBcJson.encodeToString(AiChatRequestBody(message))
                connection.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    return@withContext ArBcAiChatResult.Error(
                        "Сервер ответил $responseCode${errorBody?.let { ": $it" } ?: ""}"
                    )
                }

                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = arBcJson.decodeFromString(AiChatResponseBody.serializer(), raw)
                ArBcAiChatResult.Success(parsed.reply)
            } catch (e: Exception) {
                ArBcAiChatResult.Error(e.message ?: "Ошибка обращения к ИИ-помощнику")
            }
        }
}