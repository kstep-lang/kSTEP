package dev.kstep.viewer.ui

import dev.kstep.render.mesh.ProjectedTriangle
import kotlin.math.abs

/**
 * The outcome of [SelectionOutline.compute] (ADR-0093). [Segments] carries the silhouette of the selected parts as
 * screen-space line segments; every other outcome means "no outline this frame" and the caller keeps the plain tint.
 */
internal sealed interface OutlineResult {
    /** [count] segments, four floats each (x1, y1, x2, y2) at the start of [points]; the rest of the array is stale. */
    class Segments(
        val points: FloatArray,
        val count: Int,
    ) : OutlineResult

    /** The selection is too big or too hostile for an outline; the tint alone marks it. */
    class Fallback(
        val reason: Reason,
    ) : OutlineResult

    enum class Reason { OVER_TRIANGLE_BUDGET, OVER_EDGE_CAP, PROBE_LIMIT }

    /** Nothing selected (or nothing of it visible): no outline, no changed tint. */
    object None : OutlineResult
}

/**
 * Hash tables reused from frame to frame, so an outline costs no allocation after the first one. Fixed size (about
 * 5.5 MB), allocated on first use only. A generation stamp replaces clearing. Not thread safe: one per canvas, used
 * from the draw phase only.
 */
internal class OutlineScratch {
    private var keyAArr: LongArray? = null
    private var keyBArr: LongArray? = null
    private var partArr: IntArray? = null
    private var countArr: ByteArray? = null
    private var stampArr: IntArray? = null
    private var usedArr: IntArray? = null
    private var outArr: FloatArray? = null
    private var generation = 0

    val keyA: LongArray get() = keyAArr ?: LongArray(SelectionOutline.SLOTS).also { keyAArr = it }
    val keyB: LongArray get() = keyBArr ?: LongArray(SelectionOutline.SLOTS).also { keyBArr = it }
    val part: IntArray get() = partArr ?: IntArray(SelectionOutline.SLOTS).also { partArr = it }
    val count: ByteArray get() = countArr ?: ByteArray(SelectionOutline.SLOTS).also { countArr = it }
    val stamp: IntArray get() = stampArr ?: IntArray(SelectionOutline.SLOTS).also { stampArr = it }
    val used: IntArray
        get() = usedArr ?: IntArray(SelectionOutline.MAX_SELECTED_TRIANGLES * 3).also { usedArr = it }
    val out: FloatArray get() = outArr ?: FloatArray(SelectionOutline.MAX_EDGES * 4).also { outArr = it }

    /** Starts a new run: every slot of the previous one is stale from now on. */
    fun nextGeneration(): Int {
        if (generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 0
        }
        return ++generation
    }
}

/**
 * The silhouette of the selected parts in the projected frame (ADR-0093): an edge of the projected triangle soup that
 * only one selected triangle of a part touches. Two triangles of the same part that share an edge cancel it, so the
 * diagonals and the inside of a part vanish; the same edge shared with another part stays (once per part), so the
 * border between two selected parts is drawn.
 *
 * Bounded by construction: a fixed-size open-addressing table, a hard probe limit, a triangle budget and an edge cap;
 * never an exception, never work that grows faster than linearly in the triangles.
 */
internal object SelectionOutline {
    const val MAX_SELECTED_TRIANGLES = 40_000
    const val MAX_EDGES = 16_384
    const val SLOTS = 1 shl 18
    const val MAX_PROBES = 64
    const val QUANT = 16.0
    const val MAX_COORD = 1_000_000.0

    /** Test hook: the budget [compute] uses by default. Only tests change it, and restore it. */
    @Volatile
    internal var triangleBudget: Int = MAX_SELECTED_TRIANGLES

    private const val MASK = SLOTS - 1
    private const val LOW32 = 0xFFFFFFFFL
    private const val SATURATED: Byte = 2

    fun compute(
        triangles: List<ProjectedTriangle>,
        highlight: CanvasHighlight,
        visibility: CanvasVisibility?,
        scratch: OutlineScratch,
        maxProbes: Int = MAX_PROBES,
        budget: Int = triangleBudget,
    ): OutlineResult {
        val selectedCount = highlight.selectedTriangleCount
        if (selectedCount == 0) return OutlineResult.None
        if (selectedCount > budget || selectedCount > MAX_SELECTED_TRIANGLES) {
            return OutlineResult.Fallback(OutlineResult.Reason.OVER_TRIANGLE_BUDGET)
        }
        val keyA = scratch.keyA
        val keyB = scratch.keyB
        val part = scratch.part
        val count = scratch.count
        val stamp = scratch.stamp
        val used = scratch.used
        val gen = scratch.nextGeneration()
        var usedCount = 0
        var any = false
        for (t in triangles) {
            if (!highlight.isSelected(t.sourceIndex)) continue
            if (visibility != null && visibility.modeOf(t.sourceIndex) == CanvasVisibility.HIDDEN) continue
            if (!finite(t)) continue
            any = true
            val p = highlight.triangleToPart[t.sourceIndex]
            val ax = quantize(t.ax)
            val ay = quantize(t.ay)
            val bx = quantize(t.bx)
            val by = quantize(t.by)
            val cx = quantize(t.cx)
            val cy = quantize(t.cy)
            for (edge in 0..2) {
                val k1 =
                    pack(
                        if (edge ==
                            0
                        ) {
                            ax
                        } else if (edge == 1) {
                            bx
                        } else {
                            cx
                        },
                        if (edge == 0) {
                            ay
                        } else if (edge == 1) {
                            by
                        } else {
                            cy
                        },
                    )
                val k2 =
                    pack(
                        if (edge ==
                            0
                        ) {
                            bx
                        } else if (edge == 1) {
                            cx
                        } else {
                            ax
                        },
                        if (edge == 0) {
                            by
                        } else if (edge == 1) {
                            cy
                        } else {
                            ay
                        },
                    )
                if (k1 == k2) continue
                val lo = if (k1 < k2) k1 else k2
                val hi = if (k1 < k2) k2 else k1
                var slot = hash(lo, hi, p) and MASK
                var probes = 0
                while (true) {
                    if (stamp[slot] != gen) {
                        if (usedCount >= used.size) return OutlineResult.Fallback(OutlineResult.Reason.OVER_EDGE_CAP)
                        stamp[slot] = gen
                        keyA[slot] = lo
                        keyB[slot] = hi
                        part[slot] = p
                        count[slot] = 1
                        used[usedCount++] = slot
                        break
                    }
                    if (keyA[slot] == lo && keyB[slot] == hi && part[slot] == p) {
                        if (count[slot] < SATURATED) count[slot] = (count[slot] + 1).toByte()
                        break
                    }
                    if (++probes >= maxProbes) return OutlineResult.Fallback(OutlineResult.Reason.PROBE_LIMIT)
                    slot = (slot + 1) and MASK
                }
            }
        }
        if (!any) return OutlineResult.None
        val out = scratch.out
        var segments = 0
        for (i in 0 until usedCount) {
            val slot = used[i]
            if (count[slot].toInt() != 1) continue
            if (segments >= MAX_EDGES) return OutlineResult.Fallback(OutlineResult.Reason.OVER_EDGE_CAP)
            val o = segments * 4
            out[o] = unpackHigh(keyA[slot])
            out[o + 1] = unpackLow(keyA[slot])
            out[o + 2] = unpackHigh(keyB[slot])
            out[o + 3] = unpackLow(keyB[slot])
            segments++
        }
        return if (segments == 0) OutlineResult.None else OutlineResult.Segments(out, segments)
    }

    private fun finite(t: ProjectedTriangle): Boolean =
        ok(t.ax) && ok(t.ay) && ok(t.bx) && ok(t.by) && ok(t.cx) && ok(t.cy)

    // NaN fails every comparison, so it is rejected together with the infinities and the huge values.
    private fun ok(v: Double): Boolean = abs(v) <= MAX_COORD

    private fun quantize(v: Double): Int = Math.round(v * QUANT).toInt()

    private fun pack(
        x: Int,
        y: Int,
    ): Long = (x.toLong() shl 32) or (y.toLong() and LOW32)

    private fun unpackHigh(key: Long): Float = (key shr 32).toInt() / QUANT.toFloat()

    private fun unpackLow(key: Long): Float = key.toInt() / QUANT.toFloat()

    private fun hash(
        a: Long,
        b: Long,
        p: Int,
    ): Int {
        var z = a + b * -7046029254386353131L + p.toLong() * -4658895280553007687L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return (z ushr 32).toInt()
    }
}
