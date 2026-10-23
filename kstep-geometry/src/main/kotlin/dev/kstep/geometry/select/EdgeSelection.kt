package dev.kstep.geometry.select

import java.util.Locale

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
) : TopologySelectionException(message)

/** Resolves [EdgeSelector]s against edge/face geometry. Pure Kotlin, testable without OCCT. */
object EdgeSelection {
    const val MAX_SELECTOR_DEPTH: Int = SelectorTree.MAX_DEPTH
    const val MAX_SELECTOR_NODES: Int = SelectorTree.MAX_NODES
    const val MAX_LISTED_CANDIDATES: Int = 32

    /**
     * Resolves [selector] against [edges]/[faces] and returns the matching edge indices, ascending.
     * `Edges.nearest` and `Edges.atVertex` need a shape to measure/query and are therefore only available through
     * [dev.kstep.geometry.OcctKernel.selectEdges]; here they fail with an [IllegalArgumentException].
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
    ): List<Int> = resolve(bareContext(edges, faces), selector, expected, tolerance)

    private fun bareContext(
        edges: List<EdgeInfo>,
        faces: List<FaceInfo>,
    ) = SelectionContext(
        edges,
        faces,
        { throw IllegalArgumentException("vertex selectors need a shape; resolve through OcctKernel.selectEdges") },
        null,
    )

    internal fun resolve(
        ctx: SelectionContext,
        selector: EdgeSelector,
        expected: Int?,
        tolerance: SelectionTolerance,
    ): List<Int> {
        validateTree(selector)
        require(expected == null || expected >= 1) { "expected must be at least 1, got $expected" }
        val edges = ctx.edges
        val matched = SelectorEvaluator(ctx, tolerance).edgeSet(selector).sorted()
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

    /**
     * Resolves a face [selector] against [faces] and returns the matching face indices, ascending (Welle N2,
     * ADR-0034). NOT strict: an empty result is handled by the caller, which lists the candidates (strict resolution
     * is [FaceSelection.resolve]).
     */
    internal fun resolveFaces(
        edges: List<EdgeInfo>,
        faces: List<FaceInfo>,
        selector: FaceSelector,
        tolerance: SelectionTolerance = SelectionTolerance(),
    ): List<Int> {
        validateTree(selector)
        return SelectorEvaluator(bareContext(edges, faces), tolerance).faceSet(selector).sorted()
    }

    /** Bounds a selector tree over all hierarchies; see [SelectorTree.validate]. */
    internal fun validateTree(root: Any) = SelectorTree.validate(root)

    private fun candidateLines(list: List<EdgeInfo>): String {
        val shown = list.take(MAX_LISTED_CANDIDATES).joinToString("\n") { "  " + describe(it) }
        val more =
            if (list.size > MAX_LISTED_CANDIDATES) "\n  ... and ${list.size - MAX_LISTED_CANDIDATES} more" else ""
        return shown + more
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4g", v)

    private fun pt(p: Point3) = "(${num(p.x)}, ${num(p.y)}, ${num(p.z)})"

    private fun dir(d: Direction3) = "${num(d.x)}, ${num(d.y)}, ${num(d.z)}"

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
            is ParallelToSelector -> "parallelTo(${dir(s.direction)})"
            is EdgeTypeSelector -> "ofType(${s.kind}${s.radius?.let { ", radius=${num(it)}" } ?: ""})"
            is EdgeExtremeSelector -> "${if (s.max) "atMax" else "atMin"}(${dir(s.direction)})"
            is EdgesOfFaceSelector -> "ofFace(${describe(s.face)})"
            is EdgeLengthSelector -> if (s.longest) "longest()" else "shortest()"
            SeamEdgesSelector -> "seams()"
            is NearestEdgeSelector -> "nearest(${pt(s.point)}${s.within?.let { ", within=${describe(it)}" } ?: ""})"
            is EdgesAtVertexSelector -> "atVertex(${describe(s.vertex)})"
            is AndSelector -> "(${describe(s.left)} and ${describe(s.right)})"
            is OrSelector -> "(${describe(s.left)} or ${describe(s.right)})"
            is MinusSelector -> "(${describe(s.left)} minus ${describe(s.right)})"
        }

    internal fun describe(s: FaceSelector): String =
        when (s) {
            is FaceNormalSelector -> "normal(${dir(s.direction)})"
            is FaceExtremeSelector -> "${if (s.max) "atMax" else "atMin"}(${dir(s.direction)})"
            is FaceTypeSelector -> "ofType(${s.kind}${s.radius?.let { ", radius=${num(it)}" } ?: ""})"
            AllFacesSelector -> "all()"
            is NearestFaceSelector -> "nearest(${pt(s.point)}${s.within?.let { ", within=${describe(it)}" } ?: ""})"
            is FaceAndSelector -> "(${describe(s.left)} and ${describe(s.right)})"
            is FaceOrSelector -> "(${describe(s.left)} or ${describe(s.right)})"
            is FaceMinusSelector -> "(${describe(s.left)} minus ${describe(s.right)})"
        }

    internal fun describe(s: VertexSelector): String =
        when (s) {
            AllVerticesSelector -> "all()"
            is VertexExtremeSelector -> "${if (s.max) "atMax" else "atMin"}(${dir(s.direction)})"
            is NearestVertexSelector -> "nearest(${pt(s.point)}${s.within?.let { ", within=${describe(it)}" } ?: ""})"
            is VerticesOfEdgeSelector -> "ofEdge(${describe(s.edge)})"
            is VertexAndSelector -> "(${describe(s.left)} and ${describe(s.right)})"
            is VertexOrSelector -> "(${describe(s.left)} or ${describe(s.right)})"
            is VertexMinusSelector -> "(${describe(s.left)} minus ${describe(s.right)})"
        }

    internal fun faceCandidateLines(faces: List<FaceInfo>): String {
        val shown =
            faces.take(MAX_LISTED_CANDIDATES).joinToString("\n") { f ->
                val normal = f.normal?.let { " normal=(${dir(it)})" } ?: ""
                "  #${f.index} ${f.kind} area=${num(f.area)} centroid=${pt(f.centroid)}$normal"
            }
        val more =
            if (faces.size > MAX_LISTED_CANDIDATES) "\n  ... and ${faces.size - MAX_LISTED_CANDIDATES} more" else ""
        return shown + more
    }

    internal fun vertexCandidateLines(vertices: List<VertexInfo>): String {
        val shown =
            vertices.take(MAX_LISTED_CANDIDATES).joinToString("\n") { v ->
                "  #${v.index} ${pt(v.point)} edges=${v.edgeIndices}"
            }
        val more =
            if (vertices.size > MAX_LISTED_CANDIDATES) {
                "\n  ... and ${vertices.size - MAX_LISTED_CANDIDATES} more"
            } else {
                ""
            }
        return shown + more
    }
}
