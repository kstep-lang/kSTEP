package dev.kstep.desktop.run

import dev.kstep.cli.DesktopPartLimits
import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartPlacement
import dev.kstep.desktop.model.PartTable
import dev.kstep.geometry.ColoredMesh
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Outcome of validating the worker's part list (ADR-0053). */
sealed interface PartListResult {
    /** No part list in the result: selection is silently off. */
    data object Absent : PartListResult

    data class Valid(
        val table: PartTable,
    ) : PartListResult

    /** The list is present but cannot be trusted: shown as a `KSTEP-D-012` warning, selection off. */
    data class Invalid(
        val reason: String,
    ) : PartListResult
}

/**
 * Validates the `parts` of the render worker's JSON against the GLB mesh that was actually read. Both come from a
 * script's output, so this is hostile input: fail closed, never throws, all counts in `Long` arithmetic, every bound
 * checked before an allocation that depends on it (ADR-0053).
 */
object PartListParser {
    private const val PLACEMENT_VALUES = 12
    private const val MAX_PLACEMENT_ABS = 1e9
    private const val CHANNEL_MAX = 255.0
    private const val COLOR_TOLERANCE = 1
    private const val AREA_EPSILON = 1e-18
    private val colorPattern = Regex("^#[0-9A-Fa-f]{6}$")

    fun parse(
        json: JsonObject,
        mesh: ColoredMesh?,
    ): PartListResult =
        try {
            parseChecked(json, mesh)
        } catch (_: RuntimeException) {
            PartListResult.Invalid("the part list could not be read")
        }

    private fun parseChecked(
        json: JsonObject,
        mesh: ColoredMesh?,
    ): PartListResult {
        val omitted = json["partsOmitted"]
        if (omitted != null && omitted !is JsonNull) {
            val reason = ((omitted as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
            return if (reason == "too_many_parts") {
                PartListResult.Invalid("more than ${DesktopPartLimits.MAX_PARTS} parts")
            } else {
                PartListResult.Invalid("the part list was omitted")
            }
        }
        val element = json["parts"]
        if (element == null || element is JsonNull) return PartListResult.Absent
        val array = element as? JsonArray ?: return PartListResult.Invalid("parts is not an array")
        if (array.isEmpty()) return PartListResult.Absent
        if (array.size > DesktopPartLimits.MAX_PARTS) {
            return PartListResult.Invalid("more than ${DesktopPartLimits.MAX_PARTS} parts")
        }
        if (mesh == null) return PartListResult.Invalid("a part list without geometry")
        val total = mesh.mesh.triangleCount

        val raws = ArrayList<RawPart>(array.size)
        var expectedFirst = 0L
        for ((index, item) in array.withIndex()) {
            val obj = item as? JsonObject ?: return invalid(index, "not an object")
            val name = NameSanitizer.cleanName(obj["name"]) ?: return invalid(index, "invalid name")
            val first = integer(obj["firstTriangle"]) ?: return invalid(index, "invalid firstTriangle")
            val count = integer(obj["triangleCount"]) ?: return invalid(index, "invalid triangleCount")
            if (first < 0 || count < 1) return invalid(index, "triangle range out of bounds")
            if (first != expectedFirst) return invalid(index, "triangle ranges are not contiguous")
            expectedFirst = first + count
            if (expectedFirst > total) return invalid(index, "triangle range exceeds the mesh")
            val placement = placement(obj["placement"]) ?: return invalid(index, "invalid placement")
            val hex = (obj["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (hex == null || !colorPattern.matches(hex)) return invalid(index, "invalid color")
            if (!colorMatches(hex, mesh, first.toInt())) return invalid(index, "color differs from the geometry")
            // ADR-0091: absent is fine (older worker), present must be a plain integer in range; a JSON null is not.
            val line =
                if (!obj.containsKey("sourceLine")) {
                    null
                } else {
                    sourceLine(obj["sourceLine"])
                        ?: return invalid(index, "invalid sourceLine")
                }
            raws += RawPart(name, first.toInt(), count.toInt(), placement, hex.uppercase(), line)
        }
        if (expectedFirst != total.toLong()) return invalid(-1, "triangle ranges do not cover the mesh")

        val triangleToPart = IntArray(total)
        val infos = ArrayList<PartInfo>(raws.size)
        for ((i, raw) in raws.withIndex()) {
            java.util.Arrays.fill(triangleToPart, raw.first, raw.first + raw.count, i)
            infos += describe(raw, mesh)
        }
        return PartListResult.Valid(PartTable(infos, triangleToPart))
    }

    private class RawPart(
        val name: String,
        val first: Int,
        val count: Int,
        val placement: PartPlacement,
        val colorHex: String,
        val sourceLine: Int?,
    )

    private fun sourceLine(e: JsonElement?): Int? =
        integer(e)?.takeIf { it in 1L..DesktopPartLimits.MAX_SOURCE_LINE.toLong() }?.toInt()

    private fun invalid(
        index: Int,
        what: String,
    ) = PartListResult.Invalid(if (index >= 0) "part ${index + 1}: $what" else what)

    private fun integer(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.intOrNull?.toLong()
    }

    private fun finite(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.doubleOrNull?.takeIf { it.isFinite() && abs(it) <= MAX_PLACEMENT_ABS }
    }

    private fun placement(e: JsonElement?): PartPlacement? {
        val array = e as? JsonArray ?: return null
        if (array.size != PLACEMENT_VALUES) return null
        val v = DoubleArray(PLACEMENT_VALUES)
        for (i in v.indices) v[i] = finite(array[i]) ?: return null
        // row-major 3x4: [r00 r01 r02 tx, r10 r11 r12 ty, r20 r21 r22 tz]
        val axes = doubleArrayOf(v[0], v[1], v[2], v[4], v[5], v[6], v[8], v[9], v[10])
        return PartPlacement(doubleArrayOf(v[3], v[7], v[11]), axes)
    }

    private fun colorMatches(
        hex: String,
        mesh: ColoredMesh,
        triangle: Int,
    ): Boolean {
        val c = mesh.colorAt(triangle)
        val r = hex.substring(1, 3).toInt(16)
        val g = hex.substring(3, 5).toInt(16)
        val b = hex.substring(5, 7).toInt(16)

        fun near(
            expected: Double,
            actual: Int,
        ) = abs((expected * CHANNEL_MAX).roundToInt() - actual) <= COLOR_TOLERANCE
        return near(c.r, r) && near(c.g, g) && near(c.b, b)
    }

    private fun describe(
        raw: RawPart,
        mesh: ColoredMesh,
    ): PartInfo {
        val c = mesh.mesh.coordinates
        val min = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val max = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val weighted = DoubleArray(3)
        var areaSum = 0.0
        for (t in raw.first until raw.first + raw.count) {
            val o = t * 9
            for (k in 0 until 9) {
                val axis = k % 3
                min[axis] = minOf(min[axis], c[o + k])
                max[axis] = maxOf(max[axis], c[o + k])
            }
            val ux = c[o + 3] - c[o]
            val uy = c[o + 4] - c[o + 1]
            val uz = c[o + 5] - c[o + 2]
            val wx = c[o + 6] - c[o]
            val wy = c[o + 7] - c[o + 1]
            val wz = c[o + 8] - c[o + 2]
            val nx = uy * wz - uz * wy
            val ny = uz * wx - ux * wz
            val nz = ux * wy - uy * wx
            val area = 0.5 * sqrt(nx * nx + ny * ny + nz * nz)
            if (area.isFinite()) {
                areaSum += area
                for (axis in 0 until 3) {
                    weighted[axis] += area * (c[o + axis] + c[o + 3 + axis] + c[o + 6 + axis]) / 3.0
                }
            }
        }
        val centroid =
            if (areaSum > AREA_EPSILON) {
                DoubleArray(3) { weighted[it] / areaSum }
            } else {
                DoubleArray(3) { (min[it] + max[it]) / 2.0 }
            }
        return PartInfo(raw.name, raw.first, raw.count, raw.placement, raw.colorHex, min, max, centroid, raw.sourceLine)
    }
}
