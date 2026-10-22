package dev.kstep.geometry.select

import dev.kstep.geometry.OcctKernel
import kotlin.math.sqrt

/**
 * A non-zero direction in 3D space, used by edge and face selectors (`Edges.parallelTo`,
 * `Edges.atMax`, ...). The vector need not be normalised, but must be finite and at least
 * [OcctKernel.MIN_DIRECTION_LENGTH] long -- the same bound the kernel applies to its own direction
 * parameters.
 */
data class Direction3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "direction must be finite, got ($x, $y, $z)" }
        require(length() >= OcctKernel.MIN_DIRECTION_LENGTH) {
            "direction length must be at least ${OcctKernel.MIN_DIRECTION_LENGTH}, got ($x, $y, $z)"
        }
    }

    private fun length(): Double = sqrt(x * x + y * y + z * z)

    /** This direction scaled to unit length. */
    fun unit(): Direction3 {
        val l = length()
        return Direction3(x / l, y / l, z / l)
    }

    companion object {
        val X = Direction3(1.0, 0.0, 0.0)
        val Y = Direction3(0.0, 1.0, 0.0)
        val Z = Direction3(0.0, 0.0, 1.0)
        val NEG_X = Direction3(-1.0, 0.0, 0.0)
        val NEG_Y = Direction3(0.0, -1.0, 0.0)
        val NEG_Z = Direction3(0.0, 0.0, -1.0)
    }
}

/** A point in 3D space. */
data class Point3(
    val x: Double,
    val y: Double,
    val z: Double,
)
