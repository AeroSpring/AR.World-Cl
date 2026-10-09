package com.aerospring.arworld.feature.furniture.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Хранит токен входа локально, зашифрованным (Jetpack Security) —
 * первое использование EncryptedSharedPreferences в проекте.
 *
 * "Запомнить меня": отдельного флажка в UI нет — само наличие сохранённого
 * токена и есть "запоминание". Стирается только явным "Выйти", либо когда
 * сервер отклонит токен как истёкший (TTL — 30 дней, см. furniture.py).
 *
 * Роль (дизайнер / руководитель) хранится рядом с токеном. У сессий, сохранённых
 * до появления роли, её нет — они считаются дизайнерскими (так и было).
 */
class FurnitureTokenStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "furniture_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveToken(token: String, displayName: String, role: String = FurnitureRole.DESIGNER) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_ROLE, role)
            .apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getDisplayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)

    fun getRole(): String = prefs.getString(KEY_ROLE, null) ?: FurnitureRole.DESIGNER

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_ROLE = "role"
    }
}