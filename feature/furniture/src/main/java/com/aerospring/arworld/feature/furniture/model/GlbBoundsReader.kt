package com.aerospring.arworld.feature.furniture.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Реальные габариты модели в её собственных координатах (метры), с учётом трансформаций узлов. */
data class GlbBounds(
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
) {
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f
    val centerZ: Float get() = (minZ + maxZ) / 2f
    val halfX: Float get() = (maxX - minX) / 2f
    val halfY: Float get() = (maxY - minY) / 2f
    val halfZ: Float get() = (maxZ - minZ) / 2f
}

/**
 * Считает реальный bounding box из JSON-части .glb: min/max POSITION-аксессоров,
 * умноженные на мировую матрицу каждого узла. Нужен потому, что asset.boundingBox
 * в Filament/SceneView берётся по аксессорам и НЕ учитывает повороты/масштабы узлов
 * (у моделей из Blender на каждом узле стоит поворот Z-up -> Y-up).
 *
 * Читает только заголовок и JSON-чанк (сотни КБ), не весь файл. При любой проблеме
 * возвращает null — вызывающий код откатывается на прежнее поведение.
 */
object GlbBoundsReader {

    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val MAX_JSON_BYTES = 64 * 1024 * 1024
    private const val MAX_DEPTH = 64

    fun read(file: File): GlbBounds? = try {
        readInternal(file)
    } catch (e: Exception) {
        null
    }

    private class Acc {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var found = false
    }

    private fun readInternal(file: File): GlbBounds? {
        val jsonText = RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(20)
            raf.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.getInt(0) != GLB_MAGIC) return null
            val jsonLength = buf.getInt(12)
            if (jsonLength <= 0 || jsonLength > MAX_JSON_BYTES || jsonLength > raf.length() - 20) return null
            val jsonBytes = ByteArray(jsonLength)
            raf.readFully(jsonBytes)
            String(jsonBytes, Charsets.UTF_8)
        }

        val root = JSONObject(jsonText)
        val nodes = root.optJSONArray("nodes") ?: return null
        val meshes = root.optJSONArray("meshes") ?: return null
        val accessors = root.optJSONArray("accessors") ?: return null

        val rootNodes = ArrayList<Int>()
        val scenes = root.optJSONArray("scenes")
        if (scenes != null && scenes.length() > 0) {
            val sceneIndex = root.optInt("scene", 0).coerceIn(0, scenes.length() - 1)
            val sceneNodes = scenes.getJSONObject(sceneIndex).optJSONArray("nodes")
            if (sceneNodes != null) {
                for (i in 0 until sceneNodes.length()) rootNodes.add(sceneNodes.getInt(i))
            }
        }
        if (rootNodes.isEmpty()) {
            val isChild = BooleanArray(nodes.length())
            for (i in 0 until nodes.length()) {
                val children = nodes.getJSONObject(i).optJSONArray("children") ?: continue
                for (k in 0 until children.length()) {
                    val c = children.getInt(k)
                    if (c in isChild.indices) isChild[c] = true
                }
            }
            for (i in isChild.indices) if (!isChild[i]) rootNodes.add(i)
        }

        val acc = Acc()
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        for (index in rootNodes) visit(nodes, meshes, accessors, index, identity, 0, acc)

        if (!acc.found) return null
        return GlbBounds(acc.minX, acc.minY, acc.minZ, acc.maxX, acc.maxY, acc.maxZ)
    }

    private fun visit(
        nodes: JSONArray, meshes: JSONArray, accessors: JSONArray,
        index: Int, parent: FloatArray, depth: Int, acc: Acc,
    ) {
        if (depth > MAX_DEPTH || index < 0 || index >= nodes.length()) return
        val node = nodes.getJSONObject(index)
        val world = multiply(parent, localMatrix(node))

        val meshIndex = node.optInt("mesh", -1)
        if (meshIndex >= 0 && meshIndex < meshes.length()) {
            val primitives = meshes.getJSONObject(meshIndex).optJSONArray("primitives")
            if (primitives != null) {
                for (p in 0 until primitives.length()) {
                    val positionIndex = primitives.getJSONObject(p)
                        .optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                    if (positionIndex < 0 || positionIndex >= accessors.length()) continue
                    val accessor = accessors.getJSONObject(positionIndex)
                    val min = accessor.optJSONArray("min")
                    val max = accessor.optJSONArray("max")
                    if (min == null || max == null || min.length() < 3 || max.length() < 3) continue
                    addTransformedBox(world, min, max, acc)
                }
            }
        }

        val children = node.optJSONArray("children")
        if (children != null) {
            for (c in 0 until children.length()) {
                visit(nodes, meshes, accessors, children.getInt(c), world, depth + 1, acc)
            }
        }
    }

    private fun addTransformedBox(m: FloatArray, min: JSONArray, max: JSONArray, acc: Acc) {
        val xs = floatArrayOf(min.getDouble(0).toFloat(), max.getDouble(0).toFloat())
        val ys = floatArrayOf(min.getDouble(1).toFloat(), max.getDouble(1).toFloat())
        val zs = floatArrayOf(min.getDouble(2).toFloat(), max.getDouble(2).toFloat())
        for (x in xs) for (y in ys) for (z in zs) {
            val wx = m[0] * x + m[4] * y + m[8] * z + m[12]
            val wy = m[1] * x + m[5] * y + m[9] * z + m[13]
            val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
            if (wx < acc.minX) acc.minX = wx
            if (wy < acc.minY) acc.minY = wy
            if (wz < acc.minZ) acc.minZ = wz
            if (wx > acc.maxX) acc.maxX = wx
            if (wy > acc.maxY) acc.maxY = wy
            if (wz > acc.maxZ) acc.maxZ = wz
            acc.found = true
        }
    }

    /** Локальная матрица узла (column-major, как в glTF): matrix либо T * R * S. */
    private fun localMatrix(node: JSONObject): FloatArray {
        val matrix = node.optJSONArray("matrix")
        if (matrix != null && matrix.length() == 16) {
            return FloatArray(16) { matrix.getDouble(it).toFloat() }
        }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f

        val r00 = 1f - 2f * (qy * qy + qz * qz)
        val r01 = 2f * (qx * qy - qz * qw)
        val r02 = 2f * (qx * qz + qy * qw)
        val r10 = 2f * (qx * qy + qz * qw)
        val r11 = 1f - 2f * (qx * qx + qz * qz)
        val r12 = 2f * (qy * qz - qx * qw)
        val r20 = 2f * (qx * qz - qy * qw)
        val r21 = 2f * (qy * qz + qx * qw)
        val r22 = 1f - 2f * (qx * qx + qy * qy)

        return floatArrayOf(
            r00 * sx, r10 * sx, r20 * sx, 0f,
            r01 * sy, r11 * sy, r21 * sy, 0f,
            r02 * sz, r12 * sz, r22 * sz, 0f,
            tx, ty, tz, 1f,
        )
    }

    /** a * b для column-major 4x4. */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[c * 4 + k]
                result[c * 4 + row] = sum
            }
        }
        return result
    }
}