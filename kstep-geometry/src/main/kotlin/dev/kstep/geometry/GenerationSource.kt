package dev.kstep.geometry

/**
 * What a generating (sweep) operation was built from -- the profile definition, not its geometry. A
 * [GenerationSource] stays the same when a parameter changes (width 40 to 60 moves the geometry but
 * keeps "corner 2 of the profile"), which is what makes an edge generated from it addressable across
 * rebuilds. Resolved natively by coordinate matching against the profile face the sweep started from
 * (ADR-0033), so the orientation normalisation of regions is harmless.
 */
sealed interface GenerationSource

/** Point [index] of an extrude/revolve profile (`List<ProfilePoint>`); its image is a SIDE edge. */
data class ProfileVertex(
    val index: Int,
) : GenerationSource

/** Profile edge from point [index] to point `(index + 1) % n`; its images are the START and END copies. */
data class ProfileEdge(
    val index: Int,
) : GenerationSource

/**
 * Start point of segment [segment] of loop [loop] (0 = outer, 1.. = holes) of a [ProfileRegion], as GIVEN
 * (before orientation normalisation). A circle has no corner and is rejected. Image: a SIDE edge.
 */
data class RegionVertex(
    val loop: Int = 0,
    val segment: Int,
) : GenerationSource

/** Segment [segment] of loop [loop] of a [ProfileRegion], as given. Images: the START and END copies. */
data class RegionEdge(
    val loop: Int = 0,
    val segment: Int,
) : GenerationSource

/**
 * The profile vertex at ([x], [y]), matched with a tolerance of 1e-6 (or the vertex tolerance). For
 * resolvers such as sketches that know coordinates but no index. Image: a SIDE edge.
 */
data class PointAt(
    val x: Double,
    val y: Double,
) : GenerationSource

/**
 * The profile edge whose end points are ([startX], [startY]) and ([endX], [endY]), in either direction.
 * Fails as ambiguous if several edges join the same two points. Images: the START and END copies.
 */
data class SegmentAt(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
) : GenerationSource

/**
 * Open extension point, like `ExternalRootFeature`: a generating feature outside this module (for example
 * a sketch, `dev.kstep.sketch.SketchCorner`) defines its own source and translates it to [PointAt] /
 * [SegmentAt] itself. The kernel never accepts one directly.
 */
interface ExternalGenerationSource : GenerationSource {
    /** Short name for diagnostics: at most 64 printable ASCII characters. */
    val description: String
}

/**
 * Which image of a source is meant. For the sweep sources of ADR-0033: A vertex source ([ProfileVertex], [RegionVertex], [PointAt]) has
 * [SIDE] images (the edge swept from the vertex); an edge source ([ProfileEdge], [RegionEdge], [SegmentAt])
 * has [START] and [END] images (its copies at the beginning and the end of the sweep; for an extrusion with
 * negative height START stays at z = 0).
 */
enum class GeneratedRole {
    /** Sweep, vertex source: the edge swept from the vertex. */
    SIDE,

    /** Sweep, edge source: the copy at the beginning of the sweep. */
    START,

    /** Sweep, edge source: the copy at the end of the sweep. */
    END,

    /** Derived (ADR-0034): the edges where a boolean or hole cut the faces named by the source. */
    SECTION,

    /** Derived: the edges of a blend (fillet / chamfer) face that border a face the operation did not create. */
    BOUNDARY,

    /** Derived: the ring at which a hole's drill enters, measured along the drill direction from its start. */
    RIM_ENTRY,

    /** Derived: the ring at which a hole's drill leaves; empty (a naming error) for a blind hole. */
    RIM_EXIT,
}

/** Convenience builders for lists of [GenerationSource]s. */
object GenerationSources {
    /** [ProfileVertex] for every point of an `n`-point profile. */
    fun profileVertices(pointCount: Int): List<GenerationSource> {
        require(pointCount in 0..OcctKernel.MAX_NAMING_SOURCES) {
            "pointCount must be within 0..${OcctKernel.MAX_NAMING_SOURCES}, got $pointCount"
        }
        return List(pointCount) { ProfileVertex(it) }
    }

    /** [RegionVertex] for every corner of loop [loop] of [region] (a circle loop has no corner and is rejected). */
    fun loopVertices(
        region: ProfileRegion,
        loop: Int = 0,
    ): List<GenerationSource> {
        val loops = listOf(region.outer) + region.holes
        require(loop in loops.indices) { "loop $loop is out of range 0..${loops.size - 1}" }
        val segments = loops[loop].segments
        require(segments.none { it is ProfileSegment.Circle }) { "loop $loop is a circle and has no corner" }
        return segments.indices.map { RegionVertex(loop, it) }
    }
}

internal fun GenerationSource.describe(): String =
    when (this) {
        is ProfileVertex -> "ProfileVertex($index)"
        is ProfileEdge -> "ProfileEdge($index)"
        is RegionVertex -> "RegionVertex(loop=$loop, segment=$segment)"
        is RegionEdge -> "RegionEdge(loop=$loop, segment=$segment)"
        is PointAt -> "PointAt($x, $y)"
        is SegmentAt -> "SegmentAt($startX, $startY -> $endX, $endY)"
        is ExternalGenerationSource -> description
        is OperandEdges ->
            "OperandEdges($operand, ${dev.kstep.geometry.select.EdgeSelection.describe(
                selector,
            )})".take(64)
        is OperandFaces ->
            "OperandFaces($operand, ${dev.kstep.geometry.select.EdgeSelection.describe(
                selector,
            )})".take(64)
        BlendedEdges -> "BlendedEdges"
        BooleanSection -> "BooleanSection"
        HoleWall -> "HoleWall"
    }
