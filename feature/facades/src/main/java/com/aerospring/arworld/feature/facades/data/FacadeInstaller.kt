package com.aerospring.arworld.feature.facades.data

import android.content.Context
import android.graphics.Bitmap
import com.aerospring.arworld.core.data.network.ArWorldServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/**
 * Режим монтажника (шаг 3.4). Вход — тот же логин и пароль, что в студии / кабинете.
 * Монтажник — сотрудник или вход заказчика с правом «может снимать эталоны» (только свои фасады).
 * Токен (30 дней) хранится в личных настройках приложения; «Выйти» его стирает.
 */
internal data class InstallerSession(val token: String, val displayName: String, val clientName: String?)

internal object InstallerPrefs {
    private const val FILE = "facades_installer"

    fun load(context: Context): InstallerSession? {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val token = p.getString("token", null) ?: return null
        return InstallerSession(token, p.getString("name", "") ?: "", p.getString("client", null))
    }

    fun save(context: Context, s: InstallerSession) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("token", s.token).putString("name", s.displayName).putString("client", s.clientName).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

@Serializable
internal data class InstallFacade(
    val id: String,
    val title: String,
    val address: String = "",
    val clientName: String = "",
    val status: String = "",
    val version: Long = 0,
    val visibleToPassersBy: Boolean = false,
    val hiddenReason: String = "",
    val references: Int = 0
)

@Serializable
private data class LoginBody(val login: String, val password: String)

@Serializable
private data class LoginReply(val token: String, val displayName: String = "")

@Serializable
private data class MeReply(val displayName: String = "", val kind: String = "", val clientName: String? = null)

/** Ошибка с понятным текстом для экрана (из detail сервера или «нет связи»). */
internal class InstallerException(message: String, val code: Int = 0) : Exception(message)

internal object FacadesInstallApi {
    private val base = ArWorldServerConfig.BASE_URL

    /** Вход + проверка права монтажа. Без права токен не сохраняется. */
    suspend fun login(login: String, password: String): InstallerSession = withContext(Dispatchers.IO) {
        val r = facadesJson.decodeFromString(
            LoginReply.serializer(),
            request("POST", "$base/facades/auth/login", null, "application/json",
                facadesJson.encodeToString(LoginBody.serializer(), LoginBody(login.trim(), password)).toByteArray())
        )
        val me = facadesJson.decodeFromString(MeReply.serializer(), request("GET", "$base/facades/install/me", r.token))
        InstallerSession(r.token, me.displayName.ifBlank { r.displayName }, me.clientName)
    }

    suspend fun facades(s: InstallerSession): List<InstallFacade> = withContext(Dispatchers.IO) {
        facadesJson.decodeFromString(ListSerializer(InstallFacade.serializer()), request("GET", "$base/facades/install/facades", s.token))
    }

    /** Эталон, снятый у фасада: PNG, ширина, освещение, поза центра в координатах фасада. */
    suspend fun uploadReference(
        s: InstallerSession, facadeId: String, bitmap: Bitmap, widthM: Float, lighting: String,
        position: FloatArray, rotation: FloatArray, distanceM: Float
    ): Unit = withContext(Dispatchers.IO) {
        val png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        val pose = String.format(Locale.US, "{\"position\":[%.4f,%.4f,%.4f],\"rotation\":[%.6f,%.6f,%.6f,%.6f]}",
            position[0], position[1], position[2], rotation[0], rotation[1], rotation[2], rotation[3])
        val boundary = "----arworld" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("widthM", String.format(Locale.US, "%.3f", widthM))
            field("lighting", lighting)
            field("pose", pose)
            field("distanceM", String.format(Locale.US, "%.2f", distanceM))
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"capture.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            write(png)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        request("POST", "$base/facades/install/facades/${enc(facadeId)}/references", s.token, "multipart/form-data; boundary=$boundary", body)
    }

    suspend fun saveCalibration(s: InstallerSession, facadeId: String, c: FacadeCalibration): Unit = withContext(Dispatchers.IO) {
        val json = String.format(Locale.US, "{\"position\":[%.4f,%.4f,%.4f],\"rotationY\":%.3f,\"scale\":%.4f}",
            c.position.getOrElse(0) { 0f }, c.position.getOrElse(1) { 0f }, c.position.getOrElse(2) { 0f }, c.rotationY, c.scale)
        request("PUT", "$base/facades/install/facades/${enc(facadeId)}/calibration", s.token, "application/json", json.toByteArray())
    }

    private fun enc(id: String) = URLEncoder.encode(id, "UTF-8")

    private fun request(method: String, url: String, token: String?, contentType: String? = null, body: ByteArray? = null): String {
        val c = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw InstallerException("Нет связи с сервером")
        }
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val detail = try {
                c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }?.let { t ->
                    val d = facadesJson.parseToJsonElement(t).jsonObject["detail"]
                    // detail бывает строкой или объектом/списком (ошибки проверки) — берём строку, если есть.
                    runCatching { d?.jsonPrimitive?.content }.getOrNull()
                }
            } catch (e: Exception) {
                null
            }
            throw InstallerException(
                when (code) {
                    401 -> detail ?: "Вход устарел: войдите заново"
                    else -> detail ?: "Сервер ответил ошибкой $code"
                }, code
            )
        } catch (e: InstallerException) {
            throw e
        } catch (e: IOException) {
            throw InstallerException("Нет связи с сервером")
        } finally {
            c.disconnect()
        }
    }
}
