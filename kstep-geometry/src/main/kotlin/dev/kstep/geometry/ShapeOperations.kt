package dev.kstep.geometry

import dev.kstep.geometry.select.Point3

/*
 * Script-level shape operations (Welle P, ADR-0036). `dev.kstep.geometry.*` is a default import of `.kstep.kts`
 * scripts, so these are available there as plain `mirror(...)` / `scale(...)`. They return COPIES; the argument is
 * neither modified nor consumed. A placement never mirrors or scales (place() stays rigid): use these instead.
 */

/** A mirrored copy of [shape], reflected in [plane]. See [OcctKernel.mirror]. */
fun mirror(
    shape: OcctShape,
    plane: MirrorPlane,
): OcctShape = OcctKernel.mirror(shape, plane)

/** A copy of [shape] uniformly scaled by [factor] about [about]. See [OcctKernel.scale]. */
fun scale(
    shape: OcctShape,
    factor: Double,
    about: Point3 = Point3(0.0, 0.0, 0.0),
): OcctShape = OcctKernel.scale(shape, factor, about.x, about.y, about.z)
