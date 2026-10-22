package dev.kstep.geometry.select

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * Tolerances used when evaluating selectors against real geometry.
 *
 * @property linear length tolerance (model units) for plane membership, radius comparison and
 *   "equally long" ties; must lie in `[1e-9, 1.0]`.
 * @property angularRadians angular tolerance for "parallel" and "same normal"; must lie in
 *   `[1e-9, 0.1]`.
 */
data class SelectionTolerance(
    val linear: Double = 1e-6,
    val angularRadians: Double = 1e-6,
) {
    init {
        require(linear.isFinite() && linear in MIN_LINEAR..MAX_LINEAR) {
            "linear tolerance must be within [$MIN_LINEAR, $MAX_LINEAR], got $linear"
        }
        require(angularRadians.isFinite() && angularRadians in MIN_ANGULAR..MAX_ANGULAR) {
            "angular tolerance must be within [$MIN_ANGULAR, $MAX_ANGULAR], got $angularRadians"
        }
    }

    companion object {
        const val MIN_LINEAR: Double = 1e-9
        const val MAX_LINEAR: Double = 1.0
        const val MIN_ANGULAR: Double = 1e-9
        const val MAX_ANGULAR: Double = 0.1
    }
}

/**
 * Raised when a selector does not resolve to exactly what the caller expects. Never silently
 * guesses: an empty result, or a result whose size differs from `expected`, is always an error.
 *
 * @property matched the indices the selector did match (empty for "matched nothing").
 * @property candidates the edges the caller should look at: the matches when the count was
 *   ambiguous, all regular edges when nothing matched. Complete (the message lists at most
 *   [EdgeSelection.MAX_LISTED_CANDIDATES] of them).
 */
class EdgeSelectionException(
    message: String,
    val selector: EdgeSelector,
    val expected: Int?,
    val matched: List<Int>,
    val candidates: List<EdgeInfo>,
) : IllegalArgumentException(message)

/** Resolves [EdgeSelector]s against edge/face geometry. Pure Kotlin, testable without OCCT. */
object EdgeSelection {
    const val MAX_SELECTOR_DEPTH: Int = 32
    const val MAX_SELECTOR_NODES: Int = 256
    const val MAX_LISTED_CANDIDATES: Int = 32

    /**
     * Resolves [selector] against [edges]/[faces] and returns the matching edge indices, ascending.
     *
     * @param expected if non-null, the result must have exactly this many edges.
     * @throws IllegalArgumentException if the selector tree exceeds [MAX_SELECTOR_DEPTH] /
     *   [MAX_SELECTOR_NODES], or a radius/direction inside it is invalid.
     * @throws EdgeSelectionException if nothing matches, or the count differs from [expected].
     */
    fun resolve(
        edges: List<EdgeInfo>,
        faces: List<FaceInfo>,
        selector: EdgeSelector,
        expected: Int? = null,
        tolerance: SelectionTolerance = SelectionTolerance(),
    ): List<Int> {
        validateTree(selector)
        require(expected == null || expected >= 1) { "expected must be at least 1, got $expected" }
        val matched = Evaluator(edges, faces, tolerance).edgeSet(selector).sorted()
        if (matched.isEmpty()) {
            val regular = edges.filter { !it.seam && !it.degenerated }
            throw EdgeSelectionException(
                "edge selection ${describe(selector)} matched no edge. Candidates:\n" + candidateLines(regular),
                selector,
                expected,
                matched,
                regular,
            )
        }
        if (expected != null && matched.size != expected) {
            val chosen = matched.map { edges[it] }
            throw EdgeSelectionException(
                "edge selection ${describe(selector)} is ambiguous: expected $expected edge(s) but matched " +
                    "${matched.size}. Matched:\n" + candidateLines(chosen),
                selector,
                expected,
                matched,
                chosen,
            )
        }
        return matched
    }

    private fun validateTree(root: EdgeSelector) {
        var nodes = 0
        val stack = ArrayDeque<Pair<EdgeSelector, Int>>()
        stack.addLast(root to 1)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            nodes++
            require(depth <= MAX_SELECTOR_DEPTH) { "selector nesting exceeds $MAX_SELECTOR_DEPTH levels" }
            require(nodes <= MAX_SELECTOR_NODES) { "selector has more than $MAX_SELECTOR_NODES nodes" }
            when (node) {
                is AndSelector -> {
                    stack.addLast(node.left to depth + 1)
                    stack.addLast(node.right to depth + 1)
                }
                is OrSelector -> {
                    stack.addLast(node.left to depth + 1)
                    stack.addLast(node.right to depth + 1)
                }
                is MinusSelector -> {
                    stack.addLast(node.left to depth + 1)
                    stack.addLast(node.right to depth + 1)
                }
                is EdgesOfFaceSelector -> nodes++
                else -> Unit
            }
        }
    }

    private fun candidateLines(list: List<EdgeInfo>): String {
        val shown = list.take(MAX_LISTED_CANDIDATES).joinToString("\n") { "  " + describe(it) }
        val more =
            if (list.size >
                MAX_LISTED_CANDIDATES
            ) {
                "\n  ... and ${list.size - MAX_LISTED_CANDIDATES} more"
            } else {
                ""
            }
        return shown + more
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4g", v)

    private fun pt(p: Point3) = "(${num(p.x)}, ${num(p.y)}, ${num(p.z)})"

    private fun describe(e: EdgeInfo): String {
        val geometry =
            when (e.kind) {
                EdgeKind.LINE -> "dir=" + (e.direction?.let { "(${num(it.x)}, ${num(it.y)}, ${num(it.z)})" } ?: "?")
                EdgeKind.CIRCLE -> "radius=${e.radius?.let { num(it) } ?: "?"}"
                EdgeKind.OTHER -> ""
            }
        val flags = (if (e.seam) " seam" else "") + (if (e.degenerated) " degenerated" else "")
        return "#${e.index} ${e.kind} length=${num(e.length)} mid=${pt(e.mid)} $geometry$flags".trimEnd()
    }

    internal fun describe(s: EdgeSelector): String =
        when (s) {
            AllEdgesSelector -> "all()"
            is ParallelToSelector -> "parallelTo(${num(s.direction.x)}, ${num(s.direction.y)}, ${num(s.direction.z)})"
            is EdgeTypeSelector -> "ofType(${s.kind}${s.radius?.let { ", radius=${num(it)}" } ?: ""})"
            is EdgeExtremeSelector ->
                "${if (s.max) "atMax" else "atMin"}(${num(
                    s.direction.x,
                )}, ${num(s.direction.y)}, ${num(s.direction.z)})"
            is EdgesOfFaceSelector -> "ofFace(${describe(s.face)})"
            is EdgeLengthSelector -> if (s.longest) "longest()" else "shortest()"
            SeamEdgesSelector -> "seams()"
            is AndSelector -> "(${describe(s.left)} and ${describe(s.right)})"
            is OrSelector -> "(${describe(s.left)} or ${describe(s.right)})"
            is MinusSelector -> "(${describe(s.left)} minus ${describe(s.right)})"
        }

    private fun describe(s: FaceSelector): String =
        when (s) {
            is FaceNormalSelector -> "normal(${num(s.direction.x)}, ${num(s.direction.y)}, ${num(s.direction.z)})"
            is FaceExtremeSelector ->
                "${if (s.max) "atMax" else "atMin"}(${num(
                    s.direction.x,
                )}, ${num(s.direction.y)}, ${num(s.direction.z)})"
            is FaceTypeSelector -> "ofType(${s.kind}${s.radius?.let { ", radius=${num(it)}" } ?: ""})"
        }

    private class Evaluator(
        val edges: List<EdgeInfo>,
        val faces: List<FaceInfo>,
        val tol: SelectionTolerance,
    ) {
        private val regular: List<EdgeInfo> = edges.filter { !it.seam && !it.degenerated }

        fun edgeSet(s: EdgeSelector): Set<Int> =
            when (s) {
                AllEdgesSelector -> regular.map { it.index }.toSet()
                is ParallelToSelector -> {
                    val d = s.direction.unit()
                    regular
                        .filter { e ->
                            val ed = e.direction
                            e.kind == EdgeKind.LINE &&
                                ed != null &&
                                crossMagnitude(d, ed.unit()) <= sin(tol.angularRadians)
                        }.map { it.index }
                        .toSet()
                }
                is EdgeTypeSelector ->
                    regular
                        .filter { e ->
                            e.kind == s.kind &&
                                (s.radius == null || (e.radius != null && abs(e.radius - s.radius) <= tol.linear))
                        }.map { it.index }
                        .toSet()
                is EdgeExtremeSelector -> extremeEdges(s)
                is EdgesOfFaceSelector -> {
                    val matchedFaces = faceSet(s.face)
                    val regularIndices = regular.map { it.index }.toSet()
                    faces
                        .filter { it.index in matchedFaces }
                        .flatMap { it.edgeIndices }
                        .filter { it in regularIndices }
                        .toSet()
                }
                is EdgeLengthSelector -> {
                    if (regular.isEmpty()) {
                        emptySet()
                    } else {
                        val target = if (s.longest) regular.maxOf { it.length } else regular.minOf { it.length }
                        regular.filter { abs(it.length - target) <= tol.linear }.map { it.index }.toSet()
                    }
                }
                SeamEdgesSelector -> edges.filter { it.seam && !it.degenerated }.map { it.index }.toSet()
                is AndSelector -> edgeSet(s.left).intersect(edgeSet(s.right))
                is OrSelector -> edgeSet(s.left).union(edgeSet(s.right))
                is MinusSelector -> edgeSet(s.left).subtract(edgeSet(s.right))
            }

        private fun extremeEdges(s: EdgeExtremeSelector): Set<Int> {
            if (regular.isEmpty()) return emptySet()
            val d = s.direction.unit()

            // Per edge: the projection range of its three sampled points.
            fun low(e: EdgeInfo) = minOf(proj(d, e.start), proj(d, e.mid), proj(d, e.end))

            fun high(e: EdgeInfo) = maxOf(proj(d, e.start), proj(d, e.mid), proj(d, e.end))
            return if (s.max) {
                val m = regular.maxOf { high(it) }
                regular.filter { low(it) >= m - tol.linear }.map { it.index }.toSet()
            } else {
                val m = regular.minOf { low(it) }
                regular.filter { high(it) <= m + tol.linear }.map { it.index }.toSet()
            }
        }

        fun faceSet(s: FaceSelector): Set<Int> =
            when (s) {
                is FaceNormalSelector -> {
                    val d = s.direction.unit()
                    faces
                        .filter { f ->
                            val n = f.normal?.unit()
                            f.kind == FaceKind.PLANE &&
                                n != null &&
                                dot(d, n) > 0.0 &&
                                crossMagnitude(d, n) <= sin(tol.angularRadians)
                        }.map { it.index }
                        .toSet()
                }
                is FaceExtremeSelector -> {
                    if (faces.isEmpty()) {
                        emptySet()
                    } else {
                        val d = s.direction.unit()
                        val values = faces.map { proj(d, it.centroid) }
                        val target = if (s.max) values.max() else values.min()
                        faces.filter { abs(proj(d, it.centroid) - target) <= tol.linear }.map { it.index }.toSet()
                    }
                }
                is FaceTypeSelector ->
                    faces
                        .filter { f ->
                            f.kind == s.kind &&
                                (s.radius == null || (f.radius != null && abs(f.radius - s.radius) <= tol.linear))
                        }.map { it.index }
                        .toSet()
            }

        private fun proj(
            d: Direction3,
            p: Point3,
        ) = d.x * p.x + d.y * p.y + d.z * p.z

        private fun dot(
            a: Direction3,
            b: Direction3,
        ) = a.x * b.x + a.y * b.y + a.z * b.z

        private fun crossMagnitude(
            a: Direction3,
            b: Direction3,
        ): Double {
            val cx = a.y * b.z - a.z * b.y
            val cy = a.z * b.x - a.x * b.z
            val cz = a.x * b.y - a.y * b.x
            return kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
        }
    }
}
