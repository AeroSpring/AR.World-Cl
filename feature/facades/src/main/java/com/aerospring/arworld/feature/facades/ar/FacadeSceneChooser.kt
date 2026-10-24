package com.aerospring.arworld.feature.facades.ar

import com.aerospring.arworld.feature.facades.data.FacadePackage
import com.aerospring.arworld.feature.facades.data.FacadeSchedule
import com.aerospring.arworld.feature.facades.data.FacadeScene
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Какая сцена фасада показывается сейчас — без сети, по расписанию из копии на телефоне.
 * То же правило, что на сервере (src/arworld/schedule.py):
 *   1) из включённых сцен — те, у которых есть правило, действующее сейчас;
 *   2) из них — с наибольшим приоритетом правила, при равенстве — с более узким периодом;
 *   3) если ничего не действует — основная сцена;
 *   4) если нет и её — сцены нет.
 * Ежегодное правило может переходить через Новый год (20.12–10.01), время — через полночь.
 * Время — по часовому поясу фасада (а не телефона).
 */
internal object FacadeSceneChooser {

    fun now(pkg: FacadePackage): LocalDateTime = try {
        LocalDateTime.now(ZoneId.of(pkg.timezone))
    } catch (e: Exception) {
        LocalDateTime.now()
    }

    fun choose(pkg: FacadePackage, now: LocalDateTime = now(pkg)): FacadeScene? {
        val active = pkg.scenes.filter { it.status == "active" }
        var best: Triple<Int, Long, String>? = null   // (приоритет, -длина периода, id)
        var bestScene: FacadeScene? = null
        for (scene in active) {
            for (rule in scene.schedules) {
                if (!matches(rule, now)) continue
                val key = Triple(rule.priority, -spanDays(rule), scene.id)
                if (best == null || compare(key, best) > 0) {
                    best = key
                    bestScene = scene
                }
            }
        }
        return bestScene ?: active.firstOrNull { it.isDefault }
    }

    private fun compare(a: Triple<Int, Long, String>, b: Triple<Int, Long, String>): Int =
        compareValuesBy(a, b, { it.first }, { it.second }, { it.third })

    fun matches(rule: FacadeSchedule, now: LocalDateTime): Boolean {
        if (!dateMatches(rule, now.toLocalDate())) return false
        val dow = rule.daysOfWeek
        if (!dow.isNullOrEmpty() && now.dayOfWeek.value !in dow) return false
        return timeMatches(rule, now.toLocalTime())
    }

    private fun dateMatches(rule: FacadeSchedule, d: LocalDate): Boolean {
        if (!rule.yearly) {
            val from = parseDate(rule.from) ?: return false
            val to = parseDate(rule.to) ?: return false
            return !d.isBefore(from) && !d.isAfter(to)
        }
        val a = monthDay(rule.from) ?: return false
        val b = monthDay(rule.to) ?: return false
        val x = d.monthValue * 100 + d.dayOfMonth
        return if (a <= b) x in a..b else x >= a || x <= b   // через Новый год
    }

    private fun timeMatches(rule: FacadeSchedule, t: LocalTime): Boolean {
        if (rule.timeFrom == null && rule.timeTo == null) return true
        val start = rule.timeFrom?.let(::parseTime) ?: LocalTime.MIN
        val end = rule.timeTo?.let(::parseTime) ?: LocalTime.of(23, 59, 59)
        return if (!start.isAfter(end)) !t.isBefore(start) && !t.isAfter(end)
        else !t.isBefore(start) || !t.isAfter(end)   // через полночь
    }

    private fun spanDays(rule: FacadeSchedule): Long {
        if (!rule.yearly) {
            val from = parseDate(rule.from) ?: return 0
            val to = parseDate(rule.to) ?: return 0
            return ChronoUnit.DAYS.between(from, to)
        }
        val a = monthDay(rule.from) ?: return 0
        val b = monthDay(rule.to) ?: return 0
        val da = LocalDate.of(2001, a / 100, a % 100)
        val db = LocalDate.of(2001, b / 100, b % 100)
        val days = ChronoUnit.DAYS.between(da, db)
        return if (days >= 0) days else days + 365
    }

    private fun parseDate(s: String): LocalDate? = try { LocalDate.parse(s.take(10)) } catch (e: Exception) { null }

    /** «12-20» или «2026-12-20» → 1220. */
    private fun monthDay(s: String): Int? = try {
        val parts = s.trim().split("-").takeLast(2)
        parts[0].toInt() * 100 + parts[1].toInt()
    } catch (e: Exception) {
        null
    }

    private fun parseTime(s: String): LocalTime? = try { LocalTime.parse(s.take(5)) } catch (e: Exception) { null }
}
