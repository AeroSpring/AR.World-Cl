package com.aerospring.arworld.core.data.repository

import com.aerospring.arworld.core.data.model.Poi

/**
 * Временный источник тестовых POI вокруг позиции пользователя — для отладки
 * ленивой загрузки, фильтра категорий и масштабирования по дистанции.
 * Позже заменяется реальным бэкендом/базой данных (администратор проекта).
 */
object SamplePoiRepository {
    fun sampleAround(centerLat: Double, centerLon: Double): List<Poi> = listOf(
        Poi("poi_auto_close", "Автосервис рядом", centerLat + 0.001, centerLon + 0.0008, "auto", "", "https://example.com/auto"),
        Poi("poi_it_close", "IT-компания рядом", centerLat - 0.0012, centerLon + 0.0005, "it", "", "https://example.com/it"),
        Poi("poi_beauty_close", "Салон красоты рядом", centerLat + 0.0006, centerLon - 0.0015, "beauty", "", "https://example.com/beauty"),
        Poi("poi_med_close", "Клиника рядом", centerLat - 0.0008, centerLon - 0.0009, "med", "", "https://example.com/med"),
        Poi("poi_auto_mid", "Автосалон в 5 км", centerLat + 0.03, centerLon + 0.02, "auto", "", "https://example.com/auto2"),
        Poi("poi_it_far", "Дата-центр в 40 км", centerLat + 0.25, centerLon - 0.15, "it", "", "https://example.com/it2")
    )
}