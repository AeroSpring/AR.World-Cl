package com.aerospring.arworld.core.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Обезличенные счётчики для отчётности о рекламе: показы, открытия карточки, действия.
 * Только числа по дням — без данных о человеке и устройстве (так записано в офертах).
 * Повторный показ того же объекта в этой сессии приложения не считается.
 *
 * Отправка — POST https://autoknowledge.tech/ads/events. Если связи нет, счётчики
 * остаются в памяти и уходят при следующей попытке.
 */
class AdStatsReporter(private val section: String) {

    @Serializable
    private data class Item(
        val objectId: String,
        val erid: String? = null,
        val day: String,
        var impressions: Int = 0,
        var cardOpens: Int = 0,
        var actions: Int = 0
    )

    @Serializable
    private data class Batch(val section: String, val items: List<Item>)

    private val lock = Any()
    private val shownThisSession = HashSet<String>()
    private val counters = LinkedHashMap<String, Item>()

    private fun bump(objectId: String, erid: String?, change: (Item) -> Unit) {
        val day = LocalDate.now().toString()
        val key = "$objectId|${erid.orEmpty()}|$day"
        synchronized(lock) {
            val item = counters.getOrPut(key) { Item(objectId = objectId, erid = erid?.ifBlank { null }, day = day) }
            change(item)
        }
    }

    fun impression(objectId: String, erid: String?) {
        val first = synchronized(lock) { shownThisSession.add(objectId) }
        if (first) bump(objectId, erid) { it.impressions += 1 }
    }

    fun cardOpen(objectId: String, erid: String?) = bump(objectId, erid) { it.cardOpens += 1 }

    fun action(objectId: String, erid: String?) = bump(objectId, erid) { it.actions += 1 }

    /** Отправить накопленное. Ошибки не наружу: счётчики не должны мешать работе раздела. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        val batch: List<Item> = synchronized(lock) {
            val taken = counters.values.take(MAX_ITEMS).map { it.copy() }
            taken.forEach { counters.remove("${it.objectId}|${it.erid.orEmpty()}|${it.day}") }
            taken
        }
        if (batch.isEmpty()) return@withContext
        val ok = try {
            val conn = URL(EVENTS_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(json.encodeToString(Batch(section, batch)).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            // Вернуть обратно, сложив с тем, что успело накопиться за время отправки.
            synchronized(lock) {
                batch.forEach { b ->
                    val key = "${b.objectId}|${b.erid.orEmpty()}|${b.day}"
                    val cur = counters[key]
                    if (cur == null) counters[key] = b else {
                        cur.impressions += b.impressions
                        cur.cardOpens += b.cardOpens
                        cur.actions += b.actions
                    }
                }
            }
        }
    }

    companion object {
        private const val EVENTS_URL = "https://autoknowledge.tech/ads/events"
        private const val MAX_ITEMS = 500
        private val json = Json { encodeDefaults = true }

        private val reporters = HashMap<String, AdStatsReporter>()

        /** Один счётчик на раздел на всё время работы приложения (= одна сессия). */
        fun forSection(section: String): AdStatsReporter =
            synchronized(reporters) { reporters.getOrPut(section) { AdStatsReporter(section) } }
    }
}
