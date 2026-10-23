package dev.kstep.geometry.select

import kotlin.math.sqrt

/** The sub-shape kinds a [DistanceOracle] can measure to. [nativeCode] is the code `nativeShapeDistances` takes. */
internal enum class SubShapeKind(
    val nativeCode: Int,
) {
    EDGE(1),
    FACE(2),
}

/**
 * Measures the geometric distance from a point to every edge or face of one shape, one value per sub-shape in
 * index order (seam and degenerated edges included -- the caller ignores them). In production this is one native
 * `BRepExtrema_DistShapeShape` sweep per call.
 */
internal fun interface DistanceOracle {
    fun distances(
        kind: SubShapeKind,
        point: Point3,
    ): DoubleArray
}

/**
 * Everything a selector needs to be evaluated against one shape: edges, faces, (lazily) vertices and an optional
 * [DistanceOracle]. Without an oracle `nearest` on edges and faces fails with a clear message; the distance to a
 * vertex is computed here, euclidean, without any native call.
 */
internal class SelectionContext(
    val edges: List<EdgeInfo>,
    val faces: List<FaceInfo>,
    vertices: () -> List<VertexInfo>,
    private val oracle: DistanceOracle?,
) {
    /** Vertices, queried only when a vertex selector (or `Edges.atVertex`) actually runs. */
    val vertices: List<VertexInfo> by lazy(vertices)

    private val cache =
        object : LinkedHashMap<Pair<SubShapeKind, Point3>, DoubleArray>(MAX_CACHED_QUERIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<SubShapeKind, Point3>, DoubleArray>?) =
                size > MAX_CACHED_QUERIES
        }

    /** Distances from [point] to every edge or face (see [DistanceOracle]); validated, cached per (kind, point). */
    fun distances(
        kind: SubShapeKind,
        point: Point3,
    ): DoubleArray {
        val source =
            requireNotNull(oracle) {
                "nearest() on edges or faces needs a shape to measure against; resolve the selector through " +
                    "OcctKernel.selectEdges/selectFaces (a bare edge/face list has no geometry to measure)"
            }
        cache[kind to point]?.let { return it }
        val expected = if (kind == SubShapeKind.EDGE) edges.size else faces.size
        val values = source.distances(kind, point)
        check(values.size == expected) {
            "malformed distance payload: ${values.size} values for $expected ${kind.name.lowercase()}s"
        }
        values.forEachIndexed { i, v ->
            check(v.isFinite() && v >= 0.0) {
                "malformed distance payload: ${kind.name.lowercase()} $i has distance $v"
            }
        }
        cache[kind to point] = values
        return values
    }

    /** Euclidean distance from [point] to vertex [v]. */
    fun vertexDistance(
        point: Point3,
        v: VertexInfo,
    ): Double {
        val dx = point.x - v.point.x
        val dy = point.y - v.point.y
        val dz = point.z - v.point.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        /** Distinct (kind, point) queries kept; a selector tree has at most 256 nodes, so 16 is ample. */
        const val MAX_CACHED_QUERIES: Int = 16
    }
}
