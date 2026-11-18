package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable

/** What the inspection panel shows for a selected assembly group (ADR-0054). */
class GroupInfo(
    val name: String,
    val partCount: Int,
    val triangleCount: Long,
    val bboxMin: DoubleArray,
    val bboxMax: DoubleArray,
) {
    val center: DoubleArray get() = DoubleArray(3) { (bboxMin[it] + bboxMax[it]) / 2.0 }
}

object GroupInspection {
    /** The group's figures from the parts in its contiguous part range; `null` if [node] is not a group of [table]. */
    fun of(
        tree: AssemblyTree,
        node: Int,
        table: PartTable,
    ): GroupInfo? {
        if (node !in 0 until tree.size || !tree.isGroup(node)) return null
        val first = tree.firstPart(node)
        val last = tree.lastPart(node)
        if (first < 0 || last >= table.parts.size || first > last) return null
        val min = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val max = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        var triangles = 0L
        for (p in first..last) {
            val part = table.parts[p]
            triangles += part.triangleCount
            for (axis in 0 until 3) {
                min[axis] = minOf(min[axis], part.bboxMin[axis])
                max[axis] = maxOf(max[axis], part.bboxMax[axis])
            }
        }
        return GroupInfo(tree.name(node), last - first + 1, triangles, min, max)
    }

    /** `W x D x H mm` of the group's box. */
    fun size(info: GroupInfo): String = InspectionText.size(info.bboxMin, info.bboxMax)

    fun partsLine(info: GroupInfo): String = "${info.partCount}"

    /** Thousands-separated triangle count (a group can exceed Int in theory only through the sum, so Long). */
    fun triangles(info: GroupInfo): String = InspectionText.triangles(info.triangleCount)
}
