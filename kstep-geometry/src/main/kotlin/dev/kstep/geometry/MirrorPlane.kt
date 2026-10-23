package dev.kstep.geometry

import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Point3

/**
 * A mirror plane for [OcctKernel.mirror]: the plane through [origin] with the normal [normal] (not necessarily
 * unit length). Welle P, ADR-0036.
 *
 * ```
 * mirror(shape, MirrorPlane.YZ)          // reflect in the plane x = 0
 * mirror(shape, MirrorPlane.x(-5.0))     // reflect in the plane x = -5
 * ```
 */
data class MirrorPlane(
    val origin: Point3,
    val normal: Direction3,
) {
    companion object {
        /** The plane x = 0 (normal +X). */
        val YZ = MirrorPlane(Point3(0.0, 0.0, 0.0), Direction3.X)

        /** The plane y = 0 (normal +Y). */
        val XZ = MirrorPlane(Point3(0.0, 0.0, 0.0), Direction3.Y)

        /** The plane z = 0 (normal +Z). */
        val XY = MirrorPlane(Point3(0.0, 0.0, 0.0), Direction3.Z)

        /** The plane x = [offset]. */
        fun x(offset: Double) = MirrorPlane(Point3(offset, 0.0, 0.0), Direction3.X)

        /** The plane y = [offset]. */
        fun y(offset: Double) = MirrorPlane(Point3(0.0, offset, 0.0), Direction3.Y)

        /** The plane z = [offset]. */
        fun z(offset: Double) = MirrorPlane(Point3(0.0, 0.0, offset), Direction3.Z)
    }
}
