package com.aerospring.arworld.feature.furniture.data

import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * Анонимная статистика витрины: гость поставил модель в комнату / нажал «Хочу такую».
 * Отправляется только код модели и тип события — никаких данных о госте и устройстве.
 *
 * «Выстрелил и забыл»: запрос уходит в фоне, сцену не ждёт и не тормозит; любые ошибки
 * (нет сети, сервер недоступен) молча игнорируются — статистика никогда не мешает гостю.
 * Своя область корутин, а не область экрана: событие «Хочу такую» успевает уйти, даже
 * если гость сразу ушёл звонить и экран закрылся.
 */
object FurnitureShowcaseStatsRepository {
    const val EVENT_PLACED = "placed"
    const val EVENT_CONTACT = "contact"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun report(code: String, event: String) {
        scope.launch {
            var connection: HttpURLConnection? = null
            try {
                val body = buildJsonObject {
                    put("code", code)
                    put("event", event)
                }.toString().toByteArray(Charsets.UTF_8)
                connection = (URL("${ArWorldServerConfig.BASE_URL}/furniture/showcase/events")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(body.size)
                }
                connection.outputStream.use { it.write(body) }
                connection.responseCode // дожидаемся ответа (204), тело не нужно
            } catch (_: Exception) {
                // статистика не важнее гостя — молча
            } finally {
                connection?.disconnect()
            }
        }
    }
}