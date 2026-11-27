package dev.kstep.desktop.pick

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.model.PartTable

/** What the inspection panel shows for a multi-selection (ADR-0092): counts and the common box, never a mass. */
class MultiInfo(
    val partCount: Int,
    val triangleCount: Long,
    val bboxMin: DoubleArray,
    val bboxMax: DoubleArray,
)

object MultiInspection {
    const val NAMES_SHOWN = 5
    const val NAME_CLIP = 40

    fun of(
        parts: IntArray,
        table: PartTable,
    ): MultiInfo? {
        val valid = parts.filter { it in table.parts.indices }
        if (valid.isEmpty()) return null
        val min = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val max = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        var triangles = 0L
        for (i in valid) {
            val p = table.parts[i]
            triangles += p.triangleCount
            for (axis in 0 until 3) {
                min[axis] = minOf(min[axis], p.bboxMin[axis])
                max[axis] = maxOf(max[axis], p.bboxMax[axis])
            }
        }
        return MultiInfo(valid.size, triangles, min, max)
    }

    fun title(info: MultiInfo): String = "${info.partCount} parts selected"

    fun triangles(info: MultiInfo): String = InspectionText.triangles(info.triangleCount)

    /** `W x D x H mm` of the common box. */
    fun size(info: MultiInfo): String = InspectionText.size(info.bboxMin, info.bboxMax)

    /** Up to [NAMES_SHOWN] names (made safe to show), then "and M more". */
    fun nameLines(refs: List<PartRef>): List<String> {
        val shown = refs.take(NAMES_SHOWN).map { DisplayName.escaped(it.name, NAME_CLIP) }
        val rest = refs.size - shown.size
        return if (rest > 0) shown + "and $rest more" else shown
    }
}
