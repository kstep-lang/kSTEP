package dev.kstep.geometry

import dev.kstep.geometry.select.FaceSelector
import dev.kstep.geometry.select.Point3

/*
 * Script-level shape operations (Welle P, ADR-0036). `dev.kstep.geometry.*` is a default import of `.kstep.kts`
 * scripts, so these are available there as plain `mirror(...)` / `scale(...)` / `shell(...)`. They return COPIES;
 * the argument is neither modified nor consumed. A placement never mirrors or scales (place() stays rigid): use these instead.
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

/**
 * A hollowed copy of [shape]: the faces [openFaces] selects are removed and the wall grows [thickness] inward (the
 * default, the outer surface stays) or outward. Matches exactly one or more faces; use `Faces.atMax(Direction3.Z)`,
 * `Faces.nearest(x, y, z)` etc. and `expected = 1` through [OcctKernel.shell] when a tie must be an error. See
 * [OcctKernel.shell] and ADR-0037.
 */
fun shell(
    shape: OcctShape,
    openFaces: FaceSelector,
    thickness: Double,
    direction: ShellDirection = ShellDirection.INWARD,
): OcctShape = OcctKernel.shell(shape, openFaces, thickness, direction)

/**
 * A copy of [shape] with a pocket (or hole) cut into the ONE planar face [face] selects. The [profile] is in the local
 * coordinate system of that face (origin = its centroid, U = +X projected into the face, V = N x U) and must lie
 * completely inside it. Several matching faces are an error, never a silent first pick. See [OcctKernel.pocket].
 */
fun pocket(
    shape: OcctShape,
    face: FaceSelector,
    profile: PocketProfile,
    depth: PocketDepth,
): OcctShape = OcctKernel.pocket(shape, face, profile, depth)

/** A blind [pocket] of [depth] (> 0). A hole is `pocket(shape, face, PocketProfile.Circle(d), depth)`. */
fun pocket(
    shape: OcctShape,
    face: FaceSelector,
    profile: PocketProfile,
    depth: Double,
): OcctShape = OcctKernel.pocket(shape, face, profile, PocketDepth.Blind(depth))

/**
 * A drafted copy of [shape]: the planar faces [faces] select lean by [angleDegrees] about [neutralPlane]; positive
 * narrows the material along the pull direction. See [OcctKernel.draft] and ADR-0040.
 */
fun draft(
    shape: OcctShape,
    faces: FaceSelector,
    angleDegrees: Double,
    neutralPlane: NeutralPlane,
): OcctShape = OcctKernel.draft(shape, faces, angleDegrees, neutralPlane)

/**
 * A copy of [shape] with the planar faces [faces] select pushed outward by [distance] (> 0); their neighbours are
 * extended. See [OcctKernel.thicken] and ADR-0040.
 */
fun thicken(
    shape: OcctShape,
    faces: FaceSelector,
    distance: Double,
): OcctShape = OcctKernel.thicken(shape, faces, distance)
