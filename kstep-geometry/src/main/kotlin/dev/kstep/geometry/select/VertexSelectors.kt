package dev.kstep.geometry.select

/**
 * Selects vertices (corners) of a shape. Like [EdgeSelector] and [FaceSelector] a selector is plain data that is
 * re-resolved against the shape every time. No feature consumes a vertex directly yet; a vertex selection steers
 * edge operations through [Edges.atVertex], and `OcctKernel.selectVertices` resolves it to indices (ADR-0037, D2/D3).
 */
sealed interface VertexSelector

internal data object AllVerticesSelector : VertexSelector

internal data class VertexExtremeSelector(
    val direction: Direction3,
    val max: Boolean,
) : VertexSelector

internal data class NearestVertexSelector(
    val point: Point3,
    val within: VertexSelector?,
) : VertexSelector

internal data class VerticesOfEdgeSelector(
    val edge: EdgeSelector,
) : VertexSelector

internal data class VertexAndSelector(
    val left: VertexSelector,
    val right: VertexSelector,
) : VertexSelector

internal data class VertexOrSelector(
    val left: VertexSelector,
    val right: VertexSelector,
) : VertexSelector

internal data class VertexMinusSelector(
    val left: VertexSelector,
    val right: VertexSelector,
) : VertexSelector

/** Factory for [VertexSelector]s. */
object Vertices {
    /** Every vertex. */
    fun all(): VertexSelector = AllVerticesSelector

    /** Vertices furthest along [direction] (ties within the linear tolerance are all returned). */
    fun atMax(direction: Direction3): VertexSelector = VertexExtremeSelector(direction, max = true)

    /** Vertices least far along [direction] (ties within the linear tolerance are all returned). */
    fun atMin(direction: Direction3): VertexSelector = VertexExtremeSelector(direction, max = false)

    /**
     * The vertex/vertices closest to [point] (euclidean). Ties within the linear tolerance are ALL returned.
     * [within] limits the candidates before the minimum is taken; see [Edges.nearest].
     */
    fun nearest(
        point: Point3,
        within: VertexSelector? = null,
    ): VertexSelector {
        requirePoint(point)
        return NearestVertexSelector(point, within)
    }

    /** [nearest] to the point ([x], [y], [z]). */
    fun nearest(
        x: Double,
        y: Double,
        z: Double,
    ): VertexSelector = nearest(Point3(x, y, z))

    /** Both end vertices of every edge [edge] selects. */
    fun ofEdge(edge: EdgeSelector): VertexSelector = VerticesOfEdgeSelector(edge)
}

/** Vertices matched by both selectors. */
infix fun VertexSelector.and(other: VertexSelector): VertexSelector = VertexAndSelector(this, other)

/** Vertices matched by either selector. */
infix fun VertexSelector.or(other: VertexSelector): VertexSelector = VertexOrSelector(this, other)

/** Vertices matched by this selector but not by [other]. */
infix fun VertexSelector.minus(other: VertexSelector): VertexSelector = VertexMinusSelector(this, other)
