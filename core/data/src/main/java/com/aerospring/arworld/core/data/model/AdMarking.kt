package com.aerospring.arworld.core.data.model

import kotlinx.serialization.Serializable

/**
 * Маркировка рекламы (ст. 18.1 38-ФЗ) — блок `ad`, который сервер присылает у рекламного объекта.
 * Общий для всех разделов: «Где что находится», AR.Фасады, AR.Визитки, AR.Мебель.
 *
 * Приложение ничего не решает само: если блок пришёл — показывает [line]
 * («Реклама · ООО «Ромашка», ИНН 1234567890 · erid: 2SDnjeXXXX»), если нет — ничего.
 * Если пришёл [testBanner] — раздел в тестовом режиме, поверх экрана красная плашка.
 */
@Serializable
data class AdMarking(
    val mode: String = "on",
    val label: String = "Реклама",
    val advertiserName: String? = null,
    val advertiserInn: String? = null,
    val erid: String? = null,
    val testBanner: String? = null,
    val line: String? = null
)
