package com.aerospring.arworld.feature.guide.data

import java.util.PriorityQueue
import kotlin.math.hypot

/**
 * Поиск пути по графу здания — те же правила, что на сервере (backend guide_graph.py), чтобы
 * маршрут в приложении совпадал с «Проверить маршрут» в студии:
 *  - маршрут всегда кратчайший;
 *  - эскалатор едет в одну сторону (oneWay);
 *  - лифт +20 м «ожидания», лестница ×1,3 — только при выборе пути, показываемая длина честная;
 *  - «для колясок» — без лестниц и эскалаторов (и без проходов с accessible=false).
 * Работает без сети — по копии здания на телефоне.
 */
internal class GuideRoute(val nodeIds: List<String>, val lengthM: Float, val floorChanges: Int)

private const val LIFT_WAIT_PENALTY_M = 20f
private const val STAIRS_FACTOR = 1.3f
private val NOT_ACCESSIBLE_KINDS = setOf("stairs", "escalator_up", "escalator_down")

internal fun findRoute(b: GuideBundle, start: String, goal: String, accessibleOnly: Boolean): GuideRoute? {
    if (b.node(start) == null || b.node(goal) == null) return null
    // Соседи: (куда, цена для выбора пути, честная длина)
    val adj = HashMap<String, MutableList<Triple<String, Float, Float>>>()
    for (e in b.edges) {
        if (accessibleOnly && (!e.accessible || e.kind in NOT_ACCESSIBLE_KINDS)) continue
        val len = e.lengthM
        val cost = when (e.kind) {
            "lift" -> len + LIFT_WAIT_PENALTY_M
            "stairs" -> len * STAIRS_FACTOR
            else -> len
        }
        adj.getOrPut(e.from) { mutableListOf() } += Triple(e.to, cost, len)
        if (!e.oneWay) adj.getOrPut(e.to) { mutableListOf() } += Triple(e.from, cost, len)
    }
    val best = HashMap<String, Float>().apply { put(start, 0f) }
    val came = HashMap<String, Pair<String, Float>>()   // узел → (откуда пришли, честная длина шага)
    val queue = PriorityQueue<Pair<Float, String>>(compareBy { it.first }).apply { add(0f to start) }
    while (queue.isNotEmpty()) {
        val (g, cur) = queue.poll()!!
        if (cur == goal) break
        if (g > (best[cur] ?: Float.MAX_VALUE)) continue
        for ((next, cost, len) in adj[cur].orEmpty()) {
            val ng = g + cost
            if (ng < (best[next] ?: Float.MAX_VALUE)) {
                best[next] = ng
                came[next] = cur to len
                queue.add(ng to next)
            }
        }
    }
    if (goal !in best) return null
    val path = ArrayList<String>()
    var length = 0f
    var cur = goal
    path += cur
    while (cur != start) {
        val (prev, len) = came[cur] ?: return null
        length += len
        cur = prev
        path += cur
    }
    path.reverse()
    val floors = path.mapNotNull { b.node(it)?.floorId }
    val changes = floors.zipWithNext().count { (a, c) -> a != c }
    return GuideRoute(path, length, changes)
}

/**
 * Ближайшая точка графа на этаже к месту на плане (x, y в метрах).
 * Точки посреди лестницы (высота над полом) не берём: маршрут начинается с пола.
 */
internal fun nearestNode(b: GuideBundle, floorId: String, x: Float, y: Float): GuideNode? =
    b.nodes.filter { it.floorId == floorId && it.heightM < 0.3f }.minByOrNull { hypot(it.x - x, it.y - y) }

/** Поиск места «по задаче»: название, категория, слова для поиска. Пустой запрос — все. */
internal fun searchPlaces(b: GuideBundle, query: String): List<GuidePlace> {
    val q = query.trim().lowercase()
    val active = b.places.filter { it.isActive && it.entranceNodeId != null }
    if (q.isEmpty()) return active.sortedBy { it.name.lowercase() }
    return active.filter { p ->
        p.name.lowercase().contains(q) || p.category.lowercase().contains(q) || p.keywords.any { it.lowercase().contains(q) }
    }.sortedWith(compareBy({ !it.name.lowercase().startsWith(q) }, { it.name.lowercase() }))
}
