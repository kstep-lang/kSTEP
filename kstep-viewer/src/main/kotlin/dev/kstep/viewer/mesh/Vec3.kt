package dev.kstep.viewer.mesh

import kotlin.math.sqrt

/**
 * A plain 3D vector. `internal`: this package's public surface is [IsometricProjection] and
 * [ProjectedTriangle] only -- see [dev.kstep.viewer.mesh]'s package-boundary test
 * (`MeshPackageComposeBoundaryTest`) in `kstep-viewer`'s test source set.
 */
internal data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)

    infix fun cross(o: Vec3): Vec3 =
        Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x,
        )

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    fun length(): Double = sqrt(x * x + y * y + z * z)

    /** Returns [Vec3.ZERO] for a zero-length vector rather than throwing or producing NaN --
     *  callers that care distinguish that case themselves (see [IsometricProjection]'s
     *  degenerate-triangle handling). */
    fun normalized(): Vec3 {
        val len = length()
        return if (len <= 1e-12) ZERO else Vec3(x / len, y / len, z / len)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}
