package dev.kstep.geometry

import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Point3

/*
 * Value types of the Welle-Q2 face operations (ADR-0040): pocket / hole from a face, draft, thicken.
 *
 * Pocket profiles live in the LOCAL coordinate system of the target face: origin = the centroid of the face, N = the
 * outward normal, U = global +X projected into the face plane (global +Y when the normal is parallel to X), V = N x U.
 * Profile coordinates (u, v) are relative to that origin.
 */

/** The 2D contour of a pocket, in the local coordinate system of the target face (see the file comment). */
sealed interface PocketProfile {
    /** An axis-aligned rectangle of [width] (along U) by [length] (along V) centred on ([u], [v]). */
    data class Rectangle(
        val width: Double,
        val length: Double,
        val u: Double = 0.0,
        val v: Double = 0.0,
    ) : PocketProfile

    /** A circle of [diameter] centred on ([u], [v]); a blind or through [PocketDepth] makes it a hole. */
    data class Circle(
        val diameter: Double,
        val u: Double = 0.0,
        val v: Double = 0.0,
    ) : PocketProfile

    /** A simple (non-self-intersecting) polygon of (u, v) points, 3 to [OcctKernel.MAX_POCKET_PROFILE_POINTS]. */
    data class Polygon(
        val points: List<ProfilePoint>,
    ) : PocketProfile
}

/** How deep a pocket goes. */
sealed interface PocketDepth {
    /** A blind pocket of [depth] (> 0) below the face; it must be shallower than the body is thick. */
    data class Blind(
        val depth: Double,
    ) : PocketDepth

    /** Cuts through the whole body along the face normal. */
    data object Through : PocketDepth
}

/**
 * The neutral plane of a draft: the plane through [origin] perpendicular to [pullDirection]. Faces keep their size on
 * this plane; a positive draft angle narrows the material along [pullDirection] (ADR-0040, D1).
 */
data class NeutralPlane(
    val origin: Point3,
    val pullDirection: Direction3,
)
