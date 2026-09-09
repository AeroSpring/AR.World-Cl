package com.aerospring.arworld.feature.home.model

import androidx.annotation.DrawableRes

/**
 * Описание одной плитки на главном экране.
 * @param route идентификатор фичи, на которую ведёт плитка (для навигации),
 *              null пока фича не реализована.
 * @param launchDateMillis дата запуска — если сейчас раньше неё, показываем snackbar-заглушку.
 */
data class HomeTile(
    val id: String,
    val title: String,
    @DrawableRes val imageRes: Int,
    val route: String?,
    val launchDateMillis: Long
)