package dev.kstep.geometry

/**
 * Rotation axis for [OcctKernel.revolveProfile]: a coordinate axis through the origin, in the
 * profile's XY plane. [nativeCode] mirrors `nativeRevolveProfile`'s `axis` argument
 * (hand-synchronized pair).
 */
enum class RevolveAxis(
    internal val nativeCode: Int,
) {
    /** Rotate about the X axis (profile y is the radius). */
    X(0),

    /** Rotate about the Y axis (profile x is the radius) -- the lathe convention and the default. */
    Y(1),
}
