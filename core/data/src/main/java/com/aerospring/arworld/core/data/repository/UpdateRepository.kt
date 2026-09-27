package com.aerospring.arworld.core.data.repository

import com.aerospring.arworld.core.data.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object UpdateRepository {
    private const val VERSION_INFO_URL = "https://autoknowledge.tech/models/appVersion.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Сравнение по versionCode (целое число) — надёжнее, чем сравнивать строки версий. */
    suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(VERSION_INFO_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext UpdateCheckResult.Error("Сервер вернул код $responseCode")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val info = json.decodeFromString<UpdateInfo>(body)

            if (info.versionCode > currentVersionCode) {
                UpdateCheckResult.Available(info)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Не удалось проверить обновления")
        }
    }
}