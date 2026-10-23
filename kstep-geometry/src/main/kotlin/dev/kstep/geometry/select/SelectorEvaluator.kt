package dev.kstep.geometry.select

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Evaluates selectors of all three hierarchies against one [SelectionContext]. Recursion depth is bounded by
 * [SelectorTree.validate], which every public entry point calls first.
 */
internal class SelectorEvaluator(
    private val ctx: SelectionContext,
    private val tol: SelectionTolerance,
) {
    private val edges: List<EdgeInfo> get() = ctx.edges
    private val faces: List<FaceInfo> get() = ctx.faces
    private val regular: List<EdgeInfo> = ctx.edges.filter { !it.seam && !it.degenerated }

    fun edgeSet(s: EdgeSelector): Set<Int> =
        when (s) {
            AllEdgesSelector -> regular.map { it.index }.toSet()
            is ParallelToSelector -> {
                val d = s.direction.unit()
                regular
                    .filter { e ->
                        val ed = e.direction
                        e.kind == EdgeKind.LINE && ed != null && crossMagnitude(d, ed.unit()) <= sin(tol.angularRadians)
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
            is NearestEdgeSelector -> {
                val candidates = s.within?.let { edgeSet(it) } ?: regular.map { it.index }.toSet()
                if (candidates.isEmpty()) {
                    emptySet()
                } else {
                    val d = ctx.distances(SubShapeKind.EDGE, s.point)
                    nearestOf(candidates) { d[it] }
                }
            }
            is EdgesAtVertexSelector -> {
                val matched = vertexSet(s.vertex)
                val regularIndices = regular.map { it.index }.toSet()
                ctx.vertices
                    .filter { it.index in matched }
                    .flatMap { it.edgeIndices }
                    .filter { it in regularIndices }
                    .toSet()
            }
            is AndSelector -> edgeSet(s.left).intersect(edgeSet(s.right))
            is OrSelector -> edgeSet(s.left).union(edgeSet(s.right))
            is MinusSelector -> edgeSet(s.left).subtract(edgeSet(s.right))
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
            AllFacesSelector -> faces.map { it.index }.toSet()
            is NearestFaceSelector -> {
                val candidates = s.within?.let { faceSet(it) } ?: faces.map { it.index }.toSet()
                if (candidates.isEmpty()) {
                    emptySet()
                } else {
                    val d = ctx.distances(SubShapeKind.FACE, s.point)
                    nearestOf(candidates) { d[it] }
                }
            }
            is FaceAndSelector -> faceSet(s.left).intersect(faceSet(s.right))
            is FaceOrSelector -> faceSet(s.left).union(faceSet(s.right))
            is FaceMinusSelector -> faceSet(s.left).subtract(faceSet(s.right))
        }

    fun vertexSet(s: VertexSelector): Set<Int> =
        when (s) {
            AllVerticesSelector -> ctx.vertices.map { it.index }.toSet()
            is VertexExtremeSelector -> {
                val vs = ctx.vertices
                if (vs.isEmpty()) {
                    emptySet()
                } else {
                    val d = s.direction.unit()
                    val values = vs.map { proj(d, it.point) }
                    val target = if (s.max) values.max() else values.min()
                    vs.filter { abs(proj(d, it.point) - target) <= tol.linear }.map { it.index }.toSet()
                }
            }
            is NearestVertexSelector -> {
                val vs = ctx.vertices
                val candidates = s.within?.let { vertexSet(it) } ?: vs.map { it.index }.toSet()
                nearestOf(candidates) { ctx.vertexDistance(s.point, vs[it]) }
            }
            is VerticesOfEdgeSelector -> {
                val matched = edgeSet(s.edge)
                ctx.vertices
                    .filter { v -> v.edgeIndices.any { it in matched } }
                    .map { it.index }
                    .toSet()
            }
            is VertexAndSelector -> vertexSet(s.left).intersect(vertexSet(s.right))
            is VertexOrSelector -> vertexSet(s.left).union(vertexSet(s.right))
            is VertexMinusSelector -> vertexSet(s.left).subtract(vertexSet(s.right))
        }

    /** All candidates whose distance lies within the linear tolerance of the smallest one. */
    private fun nearestOf(
        candidates: Set<Int>,
        distance: (Int) -> Double,
    ): Set<Int> {
        if (candidates.isEmpty()) return emptySet()
        val measured = candidates.associateWith(distance)
        val smallest = measured.values.min()
        return measured.filterValues { it <= smallest + tol.linear }.keys
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
        return sqrt(cx * cx + cy * cy + cz * cz)
    }
}
