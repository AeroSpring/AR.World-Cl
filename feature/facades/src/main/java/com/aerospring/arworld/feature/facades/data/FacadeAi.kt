package com.aerospring.arworld.feature.facades.data

import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Реплика диалога. История живёт только на телефоне и уходит на сервер целиком с каждым вопросом. */
@Serializable
internal data class FacadeChatTurn(val role: String, val content: String)

/** Действие от ИИ: open_url {url} | call_phone {phone} | compose_email {email, subject?}. */
@Serializable
internal data class FacadeAiAction(
    val type: String,
    val url: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val subject: String? = null
)

@Serializable
private data class ChatBody(val message: String, val history: List<FacadeChatTurn>)

@Serializable
private data class ChatReply(val reply: String, val action: FacadeAiAction? = null)

internal sealed class FacadeChatResult {
    data class Ok(val reply: String, val action: FacadeAiAction?) : FacadeChatResult()
    data class Error(val message: String) : FacadeChatResult()
}

/** POST /facades/{id}/ai/chat — сервер ничего не хранит (ни вопрос, ни ответ). */
internal object FacadeAiApi {
    suspend fun chat(facadeId: String, message: String, history: List<FacadeChatTurn>): FacadeChatResult =
        withContext(Dispatchers.IO) {
            val c = try {
                URL("${ArWorldServerConfig.BASE_URL}/facades/${URLEncoder.encode(facadeId, "UTF-8")}/ai/chat")
                    .openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return@withContext FacadeChatResult.Error("Нет связи с сервером. Помощнику нужен интернет.")
            }
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10_000
                c.readTimeout = 40_000   // ответ ИИ может идти несколько секунд
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use {
                    it.write(facadesJson.encodeToString(ChatBody.serializer(), ChatBody(message, history.takeLast(20))).toByteArray())
                }
                val code = c.responseCode
                if (code == 200) {
                    val r = facadesJson.decodeFromString(ChatReply.serializer(), c.inputStream.bufferedReader().use { it.readText() })
                    FacadeChatResult.Ok(r.reply, r.action)
                } else {
                    // Сервер присылает понятный текст в detail («Слишком много вопросов подряд…»).
                    val detail = try {
                        c.errorStream?.bufferedReader()?.use { it.readText() }?.let {
                            facadesJson.parseToJsonElement(it).jsonObject["detail"]?.jsonPrimitive?.content
                        }
                    } catch (e: Exception) {
                        null
                    }
                    FacadeChatResult.Error(detail ?: "Помощник сейчас недоступен (код $code). Попробуйте позже.")
                }
            } catch (e: Exception) {
                FacadeChatResult.Error("Нет связи с сервером. Помощнику нужен интернет.")
            } finally {
                c.disconnect()
            }
        }
}
