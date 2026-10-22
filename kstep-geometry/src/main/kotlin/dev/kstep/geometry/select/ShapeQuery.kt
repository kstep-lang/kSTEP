package dev.kstep.geometry.select

import dev.kstep.geometry.OcctKernel

/** Geometric kind of an edge as reported by OCCT's curve adaptor. */
enum class EdgeKind { LINE, CIRCLE, OTHER }

/** Geometric kind of a face as reported by OCCT's surface adaptor. */
enum class FaceKind { PLANE, CYLINDER, OTHER }

/**
 * Geometry of one edge of an `OcctShape`. [index] is the same 0-based position
 * `OcctKernel.fillet`/`chamfer` accept (`TopExp::MapShapes(TopAbs_EDGE)` order).
 *
 * @property direction unit direction for a [EdgeKind.LINE], the axis for a [EdgeKind.CIRCLE]
 *   (sign is arbitrary); `null` otherwise.
 * @property seam `true` for the seam edge of a periodic surface (e.g. the vertical line of a
 *   cylinder): present in the B-Rep but not a visible geometric edge.
 * @property faceIndices the faces that bound this edge, in ascending order.
 */
data class EdgeInfo(
    val index: Int,
    val kind: EdgeKind,
    val length: Double,
    val start: Point3,
    val end: Point3,
    val mid: Point3,
    val direction: Direction3?,
    val circleCenter: Point3?,
    val radius: Double?,
    val seam: Boolean,
    val degenerated: Boolean,
    val faceIndices: List<Int>,
)

/**
 * Geometry of one face of an `OcctShape`. [normal] is the outward normal for a [FaceKind.PLANE]
 * and `null` otherwise; [axis]/[radius] describe a [FaceKind.CYLINDER].
 */
data class FaceInfo(
    val index: Int,
    val kind: FaceKind,
    val area: Double,
    val centroid: Point3,
    val normal: Direction3?,
    val axis: Direction3?,
    val radius: Double?,
    val edgeIndices: List<Int>,
)

internal const val EDGE_RECORD = 20
internal const val FACE_RECORD = 12

/** Parsed CSR face-to-edge adjacency. */
internal class FaceEdgeAdjacency(
    val faceCount: Int,
    val edgesOfFace: List<List<Int>>,
)

internal fun parseFaceEdges(
    faceEdges: IntArray,
    edgeCount: Int,
): FaceEdgeAdjacency {
    check(faceEdges.isNotEmpty()) { "native face-edge payload must carry a face count header" }
    val faceCount = faceEdges[0]
    check(faceCount in 0..OcctKernel.MAX_QUERY_FACES) {
        "malformed face-edge payload: face count $faceCount outside [0, ${OcctKernel.MAX_QUERY_FACES}]"
    }
    val offsetsEnd = 1 + faceCount + 1
    check(faceEdges.size >= offsetsEnd) {
        "malformed face-edge payload: size ${faceEdges.size} too small for $faceCount faces"
    }
    val indexCount = faceEdges.size - offsetsEnd
    check(faceEdges[1] == 0) { "malformed face-edge payload: first offset must be 0" }
    check(faceEdges[offsetsEnd - 1] == indexCount) {
        "malformed face-edge payload: last offset ${faceEdges[offsetsEnd - 1]} != index count $indexCount"
    }
    val result = ArrayList<List<Int>>(faceCount)
    for (f in 0 until faceCount) {
        val from = faceEdges[1 + f]
        val to = faceEdges[2 + f]
        check(from in 0..to && to <= indexCount) { "malformed face-edge payload: bad offsets for face $f" }
        val edges = ArrayList<Int>(to - from)
        for (i in from until to) {
            val e = faceEdges[offsetsEnd + i]
            check(
                e in 0 until edgeCount,
            ) { "malformed face-edge payload: edge index $e out of range for $edgeCount edges" }
            edges.add(e)
        }
        result.add(edges)
    }
    return FaceEdgeAdjacency(faceCount, result)
}

private fun finiteAt(
    raw: DoubleArray,
    pos: Int,
): Double {
    val v = raw[pos]
    check(v.isFinite()) { "malformed native payload: non-finite value at position $pos" }
    return v
}

private fun point(
    raw: DoubleArray,
    pos: Int,
) = Point3(finiteAt(raw, pos), finiteAt(raw, pos + 1), finiteAt(raw, pos + 2))

private fun directionOrNull(
    raw: DoubleArray,
    pos: Int,
): Direction3? {
    val x = finiteAt(raw, pos)
    val y = finiteAt(raw, pos + 1)
    val z = finiteAt(raw, pos + 2)
    return if (x * x + y * y + z * z >= OcctKernel.MIN_DIRECTION_LENGTH * OcctKernel.MIN_DIRECTION_LENGTH) {
        Direction3(x, y, z)
    } else {
        null
    }
}

private fun flag(
    raw: DoubleArray,
    pos: Int,
): Boolean {
    val v = finiteAt(raw, pos)
    check(v == 0.0 || v == 1.0) { "malformed native payload: flag at position $pos must be 0 or 1, got $v" }
    return v == 1.0
}

/**
 * Decodes `nativeShapeEdgeInfo` (20 doubles per edge) together with the face-edge adjacency into
 * [EdgeInfo]s. Pure and OCCT-free so it is unit-tested against hand-built arrays.
 *
 * @throws IllegalStateException on any inconsistency (length, count bound, kind code, flag value,
 *   non-finite number, adjacency index out of range).
 */
internal fun decodeEdgeInfo(
    raw: DoubleArray,
    faceEdges: IntArray,
): List<EdgeInfo> {
    check(raw.size % EDGE_RECORD == 0) { "malformed edge payload: size ${raw.size} is not a multiple of $EDGE_RECORD" }
    val edgeCount = raw.size / EDGE_RECORD
    check(edgeCount <= OcctKernel.MAX_QUERY_EDGES) {
        "malformed edge payload: $edgeCount edges exceed ${OcctKernel.MAX_QUERY_EDGES}"
    }
    val adjacency = parseFaceEdges(faceEdges, edgeCount)
    val facesOfEdge = List(edgeCount) { ArrayList<Int>(2) }
    adjacency.edgesOfFace.forEachIndexed { face, edges ->
        edges.forEach { e -> if (facesOfEdge[e].lastOrNull() != face) facesOfEdge[e].add(face) }
    }
    return List(edgeCount) { i ->
        val base = i * EDGE_RECORD
        val kindCode = finiteAt(raw, base)
        val kind =
            when (kindCode) {
                0.0 -> EdgeKind.LINE
                1.0 -> EdgeKind.CIRCLE
                2.0 -> EdgeKind.OTHER
                else -> error("malformed edge payload: unknown edge kind $kindCode")
            }
        val length = finiteAt(raw, base + 1)
        check(length >= 0.0) { "malformed edge payload: negative length $length" }
        val axisOrDirection = directionOrNull(raw, base + 11)
        EdgeInfo(
            index = i,
            kind = kind,
            length = length,
            start = point(raw, base + 2),
            end = point(raw, base + 5),
            mid = point(raw, base + 8),
            direction = if (kind == EdgeKind.OTHER) null else axisOrDirection,
            circleCenter = if (kind == EdgeKind.CIRCLE) point(raw, base + 14) else null,
            radius = if (kind == EdgeKind.CIRCLE) finiteAt(raw, base + 17) else null,
            seam = flag(raw, base + 18),
            degenerated = flag(raw, base + 19),
            faceIndices = facesOfEdge[i].toList(),
        )
    }
}

/** Decodes `nativeShapeFaceInfo` (12 doubles per face); see [decodeEdgeInfo] for the contract. */
internal fun decodeFaceInfo(
    raw: DoubleArray,
    faceEdges: IntArray,
    edgeCount: Int,
): List<FaceInfo> {
    check(raw.size % FACE_RECORD == 0) { "malformed face payload: size ${raw.size} is not a multiple of $FACE_RECORD" }
    val faceCount = raw.size / FACE_RECORD
    check(faceCount <= OcctKernel.MAX_QUERY_FACES) {
        "malformed face payload: $faceCount faces exceed ${OcctKernel.MAX_QUERY_FACES}"
    }
    val adjacency = parseFaceEdges(faceEdges, edgeCount)
    check(adjacency.faceCount == faceCount) {
        "malformed native payload: face info has $faceCount faces but adjacency has ${adjacency.faceCount}"
    }
    return List(faceCount) { i ->
        val base = i * FACE_RECORD
        val kindCode = finiteAt(raw, base)
        val kind =
            when (kindCode) {
                0.0 -> FaceKind.PLANE
                1.0 -> FaceKind.CYLINDER
                2.0 -> FaceKind.OTHER
                else -> error("malformed face payload: unknown face kind $kindCode")
            }
        val area = finiteAt(raw, base + 1)
        check(area >= 0.0) { "malformed face payload: negative area $area" }
        val dir = directionOrNull(raw, base + 5)
        FaceInfo(
            index = i,
            kind = kind,
            area = area,
            centroid = point(raw, base + 2),
            normal = if (kind == FaceKind.PLANE) dir else null,
            axis = if (kind == FaceKind.CYLINDER) dir else null,
            radius = if (kind == FaceKind.CYLINDER) finiteAt(raw, base + 11) else null,
            edgeIndices = adjacency.edgesOfFace[i],
        )
    }
}
