package com.aerospring.arworld.feature.facades.data

import android.content.Context
import android.util.Log
import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Что считаем. Только числа — без устройства, сессии, человека и текстов вопросов. */
internal enum class FacadeCounter { IMPRESSION, MODEL_TAP, CARD_OPEN, URL_OPEN, CALL, EMAIL, AI_QUESTION }

/** Строка счётчиков в формате POST /facades/events. */
@Serializable
internal data class FacadeEventRow(
    val facadeId: String,
    /** Сцена (s_…) — показы, касания, действия; карточка (a_…) — её открытия и действия из неё. */
    val objectId: String,
    val erid: String? = null,
    /** ГГГГ-ММ-ДД по часам телефона. */
    val day: String,
    val impressions: Int = 0,
    val modelTaps: Int = 0,
    val cardOpens: Int = 0,
    val urlOpens: Int = 0,
    val calls: Int = 0,
    val emails: Int = 0,
    val aiQuestions: Int = 0
) {
    val key get() = "$facadeId|$objectId|${erid.orEmpty()}|$day"

    fun plus(c: FacadeCounter) = when (c) {
        FacadeCounter.IMPRESSION -> copy(impressions = impressions + 1)
        FacadeCounter.MODEL_TAP -> copy(modelTaps = modelTaps + 1)
        FacadeCounter.CARD_OPEN -> copy(cardOpens = cardOpens + 1)
        FacadeCounter.URL_OPEN -> copy(urlOpens = urlOpens + 1)
        FacadeCounter.CALL -> copy(calls = calls + 1)
        FacadeCounter.EMAIL -> copy(emails = emails + 1)
        FacadeCounter.AI_QUESTION -> copy(aiQuestions = aiQuestions + 1)
    }

    /** Вычесть уже отправленное (пока шла отправка, могли досчитаться новые). */
    fun minus(o: FacadeEventRow) = copy(
        impressions = impressions - o.impressions, modelTaps = modelTaps - o.modelTaps,
        cardOpens = cardOpens - o.cardOpens, urlOpens = urlOpens - o.urlOpens, calls = calls - o.calls,
        emails = emails - o.emails, aiQuestions = aiQuestions - o.aiQuestions
    )

    val isEmpty get() = impressions <= 0 && modelTaps <= 0 && cardOpens <= 0 && urlOpens <= 0 &&
        calls <= 0 && emails <= 0 && aiQuestions <= 0
}

@Serializable
private data class EventsBody(val items: List<FacadeEventRow>)

/**
 * Офлайн-счётчики фасадов (шаг 3.3). У фасада связи может не быть, поэтому всё копится
 * в файле `filesDir/facades/stats.json` по (фасад, сцена/карточка, erid, день) и уходит
 * пачкой в `POST /facades/events`, когда связь есть: при выходе из AR-просмотра и при
 * открытии списка фасадов. Из файла стирается только то, что сервер принял (204).
 *
 * Один экземпляр на приложение — счётчики пишут и AR-экран, и карточка.
 */
internal object FacadeStats {
    private const val TAG = "FacadeStats"
    private val mutex = Mutex()
    private val shownThisRun = HashSet<String>()

    private fun file(context: Context) = File(File(context.filesDir, "facades").apply { mkdirs() }, "stats.json")

    private fun read(context: Context): MutableMap<String, FacadeEventRow> = try {
        facadesJson.decodeFromString(ListSerializer(FacadeEventRow.serializer()), file(context).readText())
            .associateByTo(LinkedHashMap()) { it.key }
    } catch (e: Exception) {
        LinkedHashMap()
    }

    private fun write(context: Context, rows: Collection<FacadeEventRow>) {
        val f = file(context)
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeText(facadesJson.encodeToString(ListSerializer(FacadeEventRow.serializer()), rows.filter { !it.isEmpty }))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** +1 к счётчику. [erid] — тот, что был показан (null — без erid). */
    suspend fun count(context: Context, facadeId: String, objectId: String, erid: String?, counter: FacadeCounter) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val rows = read(context)
                    val blank = FacadeEventRow(facadeId, objectId, erid?.takeIf { it.isNotBlank() }, LocalDate.now().toString())
                    rows[blank.key] = (rows[blank.key] ?: blank).plus(counter)
                    write(context, rows.values)
                } catch (e: Exception) {
                    Log.w(TAG, "Счётчик не записан: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Показ сцены — один раз за запуск приложения для (фасад, сцена): повторное открытие того же
     * фасада в той же сессии показом не считается. Идентификатор сессии на сервер не уходит.
     */
    suspend fun impression(context: Context, facadeId: String, sceneId: String, erid: String?) {
        val first = synchronized(shownThisRun) { shownThisRun.add("$facadeId|$sceneId") }
        if (first) count(context, facadeId, sceneId, erid, FacadeCounter.IMPRESSION)
    }

    /** Отправить накопленное. Нет связи — тихо оставить до следующего раза. */
    suspend fun flush(context: Context) {
        withContext(Dispatchers.IO) {
            val batch = mutex.withLock { read(context).values.filter { !it.isEmpty } }
            if (batch.isEmpty()) return@withContext
            val sent = try {
                batch.chunked(500).forEach { post(it) }
                true
            } catch (e: Exception) {
                Log.i(TAG, "Счётчики не отправлены (${e.javaClass.simpleName}), попробуем позже")
                false
            }
            if (sent) mutex.withLock {
                val rows = read(context)
                batch.forEach { s -> rows[s.key]?.let { rows[s.key] = it.minus(s) } }
                write(context, rows.values)
                Log.i(TAG, "Счётчики отправлены: строк ${batch.size}")
            }
        }
    }

    private fun post(items: List<FacadeEventRow>) {
        val c = URL("${ArWorldServerConfig.BASE_URL}/facades/events").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(facadesJson.encodeToString(EventsBody.serializer(), EventsBody(items)).toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
        } finally {
            c.disconnect()
        }
    }
}
