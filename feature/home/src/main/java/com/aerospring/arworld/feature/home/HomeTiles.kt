package com.aerospring.arworld.feature.home

import com.aerospring.arworld.feature.home.R
import com.aerospring.arworld.feature.home.model.HomeTile
import java.util.Calendar
import java.util.TimeZone

/** Дата запуска для ещё не реализованных сервисов: 01 января 2027, UTC. */
private val DEFAULT_LAUNCH_DATE_MILLIS: Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
    set(2027, Calendar.JANUARY, 1, 0, 0, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

val homeTiles = listOf(
    HomeTile(
        id = "where_anything_is",
        title = "Где что находится",
        imageRes = R.drawable.tile_where_anything_is,
        route = "where_anything_is", // уже реализуем сегодня
        launchDateMillis = 0L // 0 = доступно сразу, дата запуска не актуальна
    ),
    HomeTile(
        id = "ar_business_cards",
        title = "AR.Визитки",
        imageRes = R.drawable.tile_ar_business_cards,
        route = null,
        launchDateMillis = DEFAULT_LAUNCH_DATE_MILLIS
    ),
    HomeTile(
        id = "ar_facades",
        title = "AR.Фасады",
        imageRes = R.drawable.tile_ar_facades,
        route = null,
        launchDateMillis = DEFAULT_LAUNCH_DATE_MILLIS
    ),
    HomeTile(
        id = "ar_furniture",
        title = "AR.Мебель",
        imageRes = R.drawable.tile_ar_furniture,
        route = null,
        launchDateMillis = DEFAULT_LAUNCH_DATE_MILLIS
    ),
    HomeTile(
        id = "ar_indoor_guide",
        title = "AR.Путеводитель внутри зданий",
        imageRes = R.drawable.tile_ar_indoor_guide,
        route = null,
        launchDateMillis = DEFAULT_LAUNCH_DATE_MILLIS
    ),
    HomeTile(
        id = "ar_navigator",
        title = "AR.Navigator",
        imageRes = R.drawable.tile_ar_navigator,
        route = null,
        launchDateMillis = DEFAULT_LAUNCH_DATE_MILLIS
    )
)