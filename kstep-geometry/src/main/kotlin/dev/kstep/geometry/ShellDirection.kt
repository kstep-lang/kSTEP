package dev.kstep.geometry

/**
 * Which way the wall of a [OcctKernel.shell] grows from the original surface. The thickness is always a positive
 * number; the direction is this enum, so a sign can never be confused (ADR-0037, D5).
 *
 * @property nativeCode the code `nativeShellShape` takes; mirrored by `kShellInward`/`kShellOutward`.
 */
enum class ShellDirection(
    internal val nativeCode: Int,
) {
    /** The outer surface stays where it is, the wall grows into the material (the usual "hollow out"). */
    INWARD(0),

    /** The original surface becomes the inner wall of the cavity, the wall grows outward. */
    OUTWARD(1),
}
