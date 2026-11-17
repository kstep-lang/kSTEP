package dev.kstep.render.export

import dev.kstep.geometry.TriangleMesh
import kotlin.math.roundToLong

/** Result of [MeshTopologyCheck.check]. */
class TopologyReport(
    val triangleCount: Int,
    val degenerateCount: Int,
    val openEdgeCount: Int,
    val nonManifoldEdgeCount: Int,
) {
    /** Closed and consistently oriented under [MeshTopologyCheck.WELD_SCALE] welding -- not a printability proof. */
    val watertight: Boolean get() = openEdgeCount == 0 && nonManifoldEdgeCount == 0
}

/**
 * Edge-manifoldness check over one mesh (ADR-0049 D4). Vertices are welded by quantisation to 1e-6 mm
 * (the same grid the 3MF writer uses). An undirected edge used by exactly one triangle is OPEN; one used
 * by more than two, or by two triangles in the same direction (inconsistent winding), is NON-MANIFOLD.
 * Run it per part, never on a merged soup (touching parts share vertices).
 */
object MeshTopologyCheck {
    const val WELD_SCALE: Double = 1e6

    private data class Key(
        val x: Long,
        val y: Long,
        val z: Long,
    )

    fun quantise(v: Double): Long = (v * WELD_SCALE).roundToLong()

    fun check(mesh: TriangleMesh): TopologyReport {
        val c = mesh.coordinates
        val n = mesh.triangleCount
        val index = HashMap<Key, Int>()
        val ids = IntArray(n * 3)
        for (i in 0 until n * 3) {
            val key = Key(quantise(c[3 * i]), quantise(c[3 * i + 1]), quantise(c[3 * i + 2]))
            ids[i] = index.getOrPut(key) { index.size }
        }
        // value: low 16 bits forward count, next bits backward count (both capped)
        val edges = HashMap<Long, Int>()
        var degenerate = 0
        for (t in 0 until n) {
            val a = ids[3 * t]
            val b = ids[3 * t + 1]
            val d = ids[3 * t + 2]
            if (a == b || b == d || a == d) {
                degenerate++
                continue
            }
            addEdge(edges, a, b)
            addEdge(edges, b, d)
            addEdge(edges, d, a)
        }
        var open = 0
        var nonManifold = 0
        for (v in edges.values) {
            val fwd = v and 0xFFFF
            val bwd = v ushr 16
            val total = fwd + bwd
            if (total == 1) {
                open++
            } else if (total > 2 || fwd != 1) {
                nonManifold++
            }
        }
        return TopologyReport(n, degenerate, open, nonManifold)
    }

    private fun addEdge(
        edges: HashMap<Long, Int>,
        from: Int,
        to: Int,
    ) {
        val forward = from < to
        val key = (minOf(from, to).toLong() shl 32) or maxOf(from, to).toLong()
        val old = edges[key] ?: 0
        val updated = if (forward) minOf((old and 0xFFFF) + 1, 0xFFFF) or (old and 0xFFFF.inv()) else old + 0x10000
        edges[key] = updated
    }
}
