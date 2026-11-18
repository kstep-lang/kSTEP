package dev.kstep.render.mesh

/**
 * The exact screen mapping one [MeshProjection] call used: world point `p` lands at
 * `((p . right - centerX) * scale + originX, (-(p . trueUp) - centerY) * scale + originY)`.
 * Orthographic. Exposed so a picker can invert the mapping without rebuilding (and drifting from) it.
 * Not a data class (array members).
 */
class ScreenFrame internal constructor(
    val right: DoubleArray,
    val trueUp: DoubleArray,
    val view: DoubleArray,
    val centerX: Double,
    val centerY: Double,
    val scale: Double,
    val originX: Double,
    val originY: Double,
) {
    /** Whether [other] maps pixels to world points identically (same pose, scale, origin and centre). */
    fun sameMappingAs(other: ScreenFrame): Boolean =
        centerX == other.centerX &&
            centerY == other.centerY &&
            scale == other.scale &&
            originX == other.originX &&
            originY == other.originY &&
            right.contentEquals(other.right) &&
            trueUp.contentEquals(other.trueUp) &&
            view.contentEquals(other.view)

    /** Pixel to the world point on the plane through the origin perpendicular to [view]. */
    fun worldPointAt(
        px: Double,
        py: Double,
    ): DoubleArray {
        val u = (px - originX) / scale + centerX
        val v = -((py - originY) / scale + centerY)
        return DoubleArray(3) { right[it] * u + trueUp[it] * v }
    }
}

/** Projected triangles plus the frame they were drawn with; [frame] is null for an empty/fully culled mesh. */
class FramedProjection(
    val triangles: List<ProjectedTriangle>,
    val frame: ScreenFrame?,
)
