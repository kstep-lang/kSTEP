package dev.kstep.geometry

import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeInfo
import dev.kstep.geometry.select.EdgeKind
import dev.kstep.geometry.select.EdgeNamingException
import dev.kstep.geometry.select.EdgeSelection
import dev.kstep.geometry.select.FaceInfo
import dev.kstep.geometry.select.FaceKind
import dev.kstep.geometry.select.Point3
import kotlin.math.abs
import kotlin.math.sqrt

/** Operand kinds of the native derived query triples; hand-synchronized with `kDerivedKindEdge/Face`. */
internal const val DERIVED_KIND_EDGE = 0
internal const val DERIVED_KIND_FACE = 1

/** Which family of operation a derived naming request targets. */
internal enum class DerivedOperation { BLEND, BOOLEAN, HOLE }

/**
 * The `(slot, kind, index)` triples sent to the native side, and the position (in the source list) of the
 * source each one belongs to; [EXTRA_OWNER] marks the implicit queries that cover everything the operation acted on.
 */
internal class DerivedQueries(
    val triples: IntArray,
    val owner: IntArray,
) {
    val count: Int get() = owner.size

    companion object {
        const val EXTRA_OWNER: Int = -1
    }
}

internal class DerivedQueryBuilder {
    private val triples = ArrayList<Int>()
    private val owner = ArrayList<Int>()

    fun add(
        ownerIndex: Int,
        slot: Int,
        kind: Int,
        index: Int,
    ) {
        triples.add(slot)
        triples.add(kind)
        triples.add(index)
        owner.add(ownerIndex)
        require(owner.size <= OcctKernel.MAX_DERIVED_QUERIES) {
            "a derived naming request needs more than ${OcctKernel.MAX_DERIVED_QUERIES} queries"
        }
    }

    fun build(): DerivedQueries = DerivedQueries(triples.toIntArray(), owner.toIntArray())
}

/**
 * The edges of the blend faces [sourceFaces] that border a face which is NOT in [allBlendFaces] (everything the
 * operation generated), without seam and degenerated edges: the outline of the blend surface towards the rest of
 * the body. Pure Kotlin; indices are positions in [edges] / [faces] of the result.
 */
internal fun boundaryEdges(
    sourceFaces: Set<Int>,
    allBlendFaces: Set<Int>,
    faces: List<FaceInfo>,
    edges: List<EdgeInfo>,
): List<Int> {
    val out = java.util.TreeSet<Int>()
    for (face in sourceFaces) {
        require(face in faces.indices) { "blend face $face is outside the result" }
        for (edgeIndex in faces[face].edgeIndices) {
            val edge = edges[edgeIndex]
            if (edge.seam || edge.degenerated) continue
            if (edge.faceIndices.any { it !in allBlendFaces }) out.add(edgeIndex)
        }
    }
    return out.toList()
}

private const val MAX_RIM_EDGES = 4096

/** cos(45 degrees): a rim face must be at most this inclined to the drill. */
private const val MIN_RIM_FACE_COS = 0.7071

/**
 * Groups the hole-wall [section] edges into rings (connected components over end points that coincide within
 * [tol]) and orders them by their mean distance from [start] along [dir]. The first ring is the one the drill
 * enters through, the last (if there are at least two) the one it leaves through.
 *
 * A component whose extent along the drill exceeds [maxRingSpan] is no rim ring but an outline that joins entry,
 * side face and exit (the drill broke out sideways through a plane inclined to the drill or a curved face; the
 * section then holds ellipse or BSpline arcs and no straight edge). When [faces] is given, every component must in
 * addition lie on exactly one non-wall face (the hole wall is the cylinder of radius [wallRadius] along the drill), a plane face must have its
 * normal at most 45 degrees off the drill, and a curved face is accepted only if at least two rings lie on that same face (a
 * lone closed outline on a curved face cannot be told from a side breakout, even if other rings exist elsewhere). Everything else is rejected instead
 * of guessed; SECTION stays available.
 *
 * @return the rings, nearest first, as edge indices.
 * @throws EdgeNamingException if a component is no rim ring (side breakout) or two rings are closer than [tol] along the drill (ambiguous order).
 * @throws IllegalArgumentException if there are more than 4096 edges.
 */
internal fun classifyRims(
    section: List<EdgeInfo>,
    start: Point3,
    dir: Direction3,
    tol: Double,
    maxRingSpan: Double = Double.POSITIVE_INFINITY,
    faces: List<FaceInfo>? = null,
    wallRadius: Double = 0.0,
): List<List<Int>> {
    require(section.size <= MAX_RIM_EDGES) { "a hole wall with more than $MAX_RIM_EDGES edges cannot be classified" }
    if (section.isEmpty()) return emptyList()
    val parent = IntArray(section.size) { it }

    fun find(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        var c = i
        while (parent[c] != r) {
            val n = parent[c]
            parent[c] = r
            c = n
        }
        return r
    }

    fun close(
        a: Point3,
        b: Point3,
    ) = abs(a.x - b.x) <= tol && abs(a.y - b.y) <= tol && abs(a.z - b.z) <= tol
    for (i in section.indices) {
        for (j in i + 1 until section.size) {
            val a = section[i]
            val b = section[j]
            if (close(a.start, b.start) || close(a.start, b.end) || close(a.end, b.start) || close(a.end, b.end)) {
                parent[find(i)] = find(j)
            }
        }
    }
    val u = dir.unit()

    fun along(p: Point3) = (p.x - start.x) * u.x + (p.y - start.y) * u.y + (p.z - start.z) * u.z
    val groups = LinkedHashMap<Int, MutableList<Int>>()
    section.indices.forEach { groups.getOrPut(find(it)) { ArrayList() }.add(it) }
    // A rim ring is a closed curve on a face the drill enters or leaves through. A straight edge in the component
    // is a section line on a side face: the drill broke out sideways and rim and wall outline merged. Never guess.
    val curvedFaceUse = HashMap<Int, Int>()
    if (faces != null) {
        for (members in groups.values) {
            val single =
                members
                    .flatMap { section[it].faceIndices }
                    .toSortedSet()
                    .filter { f ->
                        val fi = faces.firstOrNull { it.index == f }
                        !(
                            fi != null &&
                                fi.kind == FaceKind.CYLINDER &&
                                fi.radius != null &&
                                fi.axis != null &&
                                abs(fi.radius!! - wallRadius) <= 1e-6 * maxOf(1.0, wallRadius) &&
                                abs(fi.axis!!.unit().let { a -> a.x * u.x + a.y * u.y + a.z * u.z }) > 1.0 - 1e-6
                        )
                    }.singleOrNull()
            if (single != null) curvedFaceUse.merge(single, 1, Int::plus)
        }
    }
    for (members in groups.values) {
        val lines = members.filter { section[it].kind == EdgeKind.LINE }.map { section[it].index }
        val positions =
            members.flatMap { listOf(along(section[it].start), along(section[it].end), along(section[it].mid)) }
        val span = positions.max() - positions.min()
        var why: String? =
            when {
                lines.isNotEmpty() -> "straight section edges $lines"
                span > maxRingSpan -> "one outline spanning $span along the drill"
                else -> null
            }
        if (why == null && faces != null) {
            // A real rim ring lies on exactly one planar face the drill enters or leaves through, not too inclined
            // to the drill. Edge sampling cannot see the extent of a closed edge, so the faces decide.
            val isWall = { f: Int ->
                val fi = faces.firstOrNull { it.index == f }
                val axis = fi?.axis?.unit()
                val radius = fi?.radius
                if (fi == null || fi.kind != FaceKind.CYLINDER || axis == null || radius == null) {
                    false
                } else {
                    abs(radius - wallRadius) <= 1e-6 * maxOf(1.0, wallRadius) &&
                        abs(axis.x * u.x + axis.y * u.y + axis.z * u.z) > 1.0 - 1e-6
                }
            }
            val others = members.flatMap { section[it].faceIndices }.toSortedSet().filter { !isWall(it) }
            val face = others.singleOrNull()?.let { f -> faces.firstOrNull { it.index == f } }
            val normal = face?.normal?.takeIf { face.kind == FaceKind.PLANE }?.unit()
            val cos = normal?.let { abs(it.x * u.x + it.y * u.y + it.z * u.z) }
            why =
                when {
                    others.size != 1 -> "an outline on the faces $others"
                    face == null -> "an outline on an unknown face ${others.single()}"
                    cos == null && (curvedFaceUse[face.index] ?: 0) < 2 ->
                        "a single outline on the curved face ${face.index} (no second ring shares it)"
                    cos != null && cos < MIN_RIM_FACE_COS ->
                        "an outline on the face ${face.index}, inclined more than 45 degrees to the drill"
                    else -> null
                }
        }
        if (why != null) {
            throw EdgeNamingException(
                "hole rim classification is ambiguous: the hole wall breaks out through a side face, so its rims " +
                    "are joined by $why; RIM_ENTRY and RIM_EXIT cannot be told apart " +
                    "(use SECTION and select by geometry)",
                null,
                listOf(HoleWall),
                emptyList(),
                mapOf("HoleWall" to section.map { it.index }),
                section,
            )
        }
    }
    val rings =
        groups.values
            .map { members ->
                val position = members.sumOf { along(section[it].mid) } / members.size
                position to members.map { section[it].index }.sorted()
            }.sortedBy { it.first }
    for (k in 1 until rings.size) {
        if (rings[k].first - rings[k - 1].first < tol) {
            throw EdgeNamingException(
                "hole rim classification is ambiguous: two rings of the hole wall lie within $tol along the drill " +
                    "direction (edges ${rings[k - 1].second} and ${rings[k].second})",
                null,
                listOf(HoleWall),
                emptyList(),
                mapOf("HoleWall" to section.map { it.index }),
                section,
            )
        }
    }
    return rings.map { it.second }
}

/** Unit-length check helper for hole directions. */
internal fun unitDirection(
    x: Double,
    y: Double,
    z: Double,
): Direction3 {
    val l = sqrt(x * x + y * y + z * z)
    return Direction3(x / l, y / l, z / l)
}

/** Lists up to [EdgeSelection.MAX_LISTED_CANDIDATES] faces for an error message. */
internal fun describeFaces(faces: List<FaceInfo>): String {
    val shown =
        faces.take(EdgeSelection.MAX_LISTED_CANDIDATES).joinToString("\n") {
            "  face ${it.index}: ${it.kind} area=${String.format(java.util.Locale.ROOT, "%.4g", it.area)}"
        }
    val more =
        if (faces.size > EdgeSelection.MAX_LISTED_CANDIDATES) {
            "\n  ... and ${faces.size - EdgeSelection.MAX_LISTED_CANDIDATES} more"
        } else {
            ""
        }
    return shown + more
}
