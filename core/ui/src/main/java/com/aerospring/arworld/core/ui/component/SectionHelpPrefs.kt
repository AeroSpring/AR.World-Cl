package com.aerospring.arworld.core.ui.component

import android.content.Context

/**
 * Запоминает, показывали ли пользователю справку конкретного раздела (одна булева настройка на раздел).
 * При любом сбое/очистке данных справка просто покажется ещё раз — это безопасное поведение.
 *
 * Ключ раздела — стабильная строка вроде "where_anything_is", "arbc", "furniture".
 */
object SectionHelpPrefs {
    private const val PREFS_NAME = "ar_world_section_help"

    fun wasShown(context: Context, sectionKey: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(sectionKey, false)

    fun markShown(context: Context, sectionKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(sectionKey, true)
            .apply()
    }
}