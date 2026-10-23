package dev.kstep.geometry.select

import dev.kstep.geometry.OcctKernel
import kotlin.math.abs

/**
 * A value-only description of "which edges of a shape" -- geometric, not positional. Unlike the
 * raw edge indices `OcctKernel.fillet`/`chamfer` accept (opaque, construction-order dependent), a
 * selector is re-resolved against the shape every time it is applied, so it survives parameter
 * changes that keep the geometric predicate true (e.g. widening a box keeps its four vertical
 * edges "parallel to Z").
 *
 * Build selectors with [Edges] and combine them with [and], [or] and [minus]. Selectors are plain
 * data (structural `equals`), so they can live inside a feature history.
 *
 * Seam edges of periodic surfaces and degenerated edges are never matched, except by
 * [Edges.seams] -- a seam is a B-Rep artefact, not a geometric edge a designer means.
 */
sealed interface EdgeSelector

/**
 * Selects faces: [Edges.ofFace] takes one to pick edges, `OcctKernel.shell` takes one to pick the faces to open.
 * Build with [Faces] and combine with [and], [or] and [minus]. Plain data with structural `equals`, like
 * [EdgeSelector]. Welle Q, ADR-0037.
 */
sealed interface FaceSelector

internal data object AllEdgesSelector : EdgeSelector

internal data class ParallelToSelector(
    val direction: Direction3,
) : EdgeSelector

internal data class EdgeTypeSelector(
    val kind: EdgeKind,
    val radius: Double?,
) : EdgeSelector

internal data class EdgeExtremeSelector(
    val direction: Direction3,
    val max: Boolean,
) : EdgeSelector

internal data class EdgesOfFaceSelector(
    val face: FaceSelector,
) : EdgeSelector

internal data class EdgeLengthSelector(
    val longest: Boolean,
) : EdgeSelector

internal data object SeamEdgesSelector : EdgeSelector

internal data class AndSelector(
    val left: EdgeSelector,
    val right: EdgeSelector,
) : EdgeSelector

internal data class OrSelector(
    val left: EdgeSelector,
    val right: EdgeSelector,
) : EdgeSelector

internal data class MinusSelector(
    val left: EdgeSelector,
    val right: EdgeSelector,
) : EdgeSelector

internal data class NearestEdgeSelector(
    val point: Point3,
    val within: EdgeSelector?,
) : EdgeSelector

internal data class EdgesAtVertexSelector(
    val vertex: VertexSelector,
) : EdgeSelector

internal data object AllFacesSelector : FaceSelector

internal data class NearestFaceSelector(
    val point: Point3,
    val within: FaceSelector?,
) : FaceSelector

internal data class FaceAndSelector(
    val left: FaceSelector,
    val right: FaceSelector,
) : FaceSelector

internal data class FaceOrSelector(
    val left: FaceSelector,
    val right: FaceSelector,
) : FaceSelector

internal data class FaceMinusSelector(
    val left: FaceSelector,
    val right: FaceSelector,
) : FaceSelector

internal data class FaceNormalSelector(
    val direction: Direction3,
) : FaceSelector

internal data class FaceExtremeSelector(
    val direction: Direction3,
    val max: Boolean,
) : FaceSelector

internal data class FaceTypeSelector(
    val kind: FaceKind,
    val radius: Double?,
) : FaceSelector

private fun requireRadius(
    kind: Enum<*>,
    circleKind: Enum<*>,
    radius: Double?,
) {
    if (radius != null) {
        require(kind == circleKind) { "radius may only be given for ${circleKind.name}, not ${kind.name}" }
        require(radius.isFinite() && radius > 0.0) { "radius must be finite and positive, got $radius" }
    }
}

/**
 * Validates the reference point of a `nearest(...)` factory at construction, so a bad point can never sit inside a
 * stored selector tree: every coordinate must be finite and within +/-[OcctKernel.MAX_ABS_COORDINATE].
 */
internal fun requirePoint(point: Point3) {
    for (c in doubleArrayOf(point.x, point.y, point.z)) {
        require(c.isFinite() && abs(c) <= OcctKernel.MAX_ABS_COORDINATE) {
            "nearest() point must be finite and within +/-${OcctKernel.MAX_ABS_COORDINATE}, got " +
                "(${point.x}, ${point.y}, ${point.z})"
        }
    }
}

/** Factory for [EdgeSelector]s. */
object Edges {
    /** Every regular (non-seam, non-degenerated) edge. */
    fun all(): EdgeSelector = AllEdgesSelector

    /** Straight edges parallel (or anti-parallel) to [direction], within the angular tolerance. */
    fun parallelTo(direction: Direction3): EdgeSelector = ParallelToSelector(direction)

    /** Edges of a geometric [kind]; [radius] (circles only) narrows to that radius. */
    fun ofType(
        kind: EdgeKind,
        radius: Double? = null,
    ): EdgeSelector {
        requireRadius(kind, EdgeKind.CIRCLE, radius)
        return EdgeTypeSelector(kind, radius)
    }

    /** Edges lying entirely in the extreme plane perpendicular to [direction] at its maximum. */
    fun atMax(direction: Direction3): EdgeSelector = EdgeExtremeSelector(direction, max = true)

    /** Edges lying entirely in the extreme plane perpendicular to [direction] at its minimum. */
    fun atMin(direction: Direction3): EdgeSelector = EdgeExtremeSelector(direction, max = false)

    /** Every edge of the faces matched by [face]. */
    fun ofFace(face: FaceSelector): EdgeSelector = EdgesOfFaceSelector(face)

    /** The longest edge(s): ties within the linear tolerance are ALL returned, never guessed. */
    fun longest(): EdgeSelector = EdgeLengthSelector(longest = true)

    /** The shortest edge(s): ties within the linear tolerance are ALL returned, never guessed. */
    fun shortest(): EdgeSelector = EdgeLengthSelector(longest = false)

    /** Seam edges of periodic surfaces (excluded from every other selector). */
    fun seams(): EdgeSelector = SeamEdgesSelector

    /**
     * The edge(s) with the smallest GEOMETRIC distance to [point] (point to curve, not to the edge midpoint). Ties
     * within the linear tolerance are ALL returned, never guessed. [within] limits the candidates BEFORE the minimum
     * is taken ("the nearest edge among these"); `nearest(p) and X` instead intersects afterwards, which is a
     * different (and usually empty) result.
     */
    fun nearest(
        point: Point3,
        within: EdgeSelector? = null,
    ): EdgeSelector {
        requirePoint(point)
        return NearestEdgeSelector(point, within)
    }

    /** [nearest] to the point ([x], [y], [z]). */
    fun nearest(
        x: Double,
        y: Double,
        z: Double,
    ): EdgeSelector = nearest(Point3(x, y, z))

    /** Every regular edge that ends in one of the vertices [vertex] selects (ADR-0037, D3). */
    fun atVertex(vertex: VertexSelector): EdgeSelector = EdgesAtVertexSelector(vertex)
}

/** Edges matched by both selectors. */
infix fun EdgeSelector.and(other: EdgeSelector): EdgeSelector = AndSelector(this, other)

/** Edges matched by either selector. */
infix fun EdgeSelector.or(other: EdgeSelector): EdgeSelector = OrSelector(this, other)

/** Edges matched by this selector but not by [other]. */
infix fun EdgeSelector.minus(other: EdgeSelector): EdgeSelector = MinusSelector(this, other)

/** Factory for [FaceSelector]s. */
object Faces {
    /** Every face. */
    fun all(): FaceSelector = AllFacesSelector

    /**
     * The face(s) with the smallest GEOMETRIC distance to [point] (point to the bounded face, `0` on it). Ties within
     * the linear tolerance are all returned. [within] limits the candidates BEFORE the minimum is taken; see
     * [Edges.nearest] for the difference to `and`.
     */
    fun nearest(
        point: Point3,
        within: FaceSelector? = null,
    ): FaceSelector {
        requirePoint(point)
        return NearestFaceSelector(point, within)
    }

    /** [nearest] to the point ([x], [y], [z]). */
    fun nearest(
        x: Double,
        y: Double,
        z: Double,
    ): FaceSelector = nearest(Point3(x, y, z))

    /** Planar faces whose outward normal points along [direction] (within the angular tolerance). */
    fun normal(direction: Direction3): FaceSelector = FaceNormalSelector(direction)

    /** Faces whose centroid lies furthest along [direction]. */
    fun atMax(direction: Direction3): FaceSelector = FaceExtremeSelector(direction, max = true)

    /** Faces whose centroid lies least far along [direction]. */
    fun atMin(direction: Direction3): FaceSelector = FaceExtremeSelector(direction, max = false)

    /** Faces of a geometric [kind]; [radius] (cylinders only) narrows to that radius. */
    fun ofType(
        kind: FaceKind,
        radius: Double? = null,
    ): FaceSelector {
        requireRadius(kind, FaceKind.CYLINDER, radius)
        return FaceTypeSelector(kind, radius)
    }
}

/** Faces matched by both selectors. */
infix fun FaceSelector.and(other: FaceSelector): FaceSelector = FaceAndSelector(this, other)

/** Faces matched by either selector. */
infix fun FaceSelector.or(other: FaceSelector): FaceSelector = FaceOrSelector(this, other)

/** Faces matched by this selector but not by [other]. */
infix fun FaceSelector.minus(other: FaceSelector): FaceSelector = FaceMinusSelector(this, other)
