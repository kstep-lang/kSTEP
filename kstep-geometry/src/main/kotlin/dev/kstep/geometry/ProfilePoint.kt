package dev.kstep.geometry

/**
 * One vertex of a 2D extrusion profile, in the XY plane at z = 0.
 *
 * Deliberately 2D, not 3D: a 3D point list would let a caller specify a non-planar polygon, which
 * OCCT's `BRepBuilderAPI_MakeFace` rejects with error code 2 (`NotPlanar`) only *after* the wire
 * has been built -- verified against OCCT 7.9.2. Restricting the profile to the XY plane makes
 * that whole failure mode structurally unreachable rather than merely handled. It also matches the
 * coordinate layout `dev.kstep.constraints.SketchPoint` already uses, which is what a later wave
 * would feed in from the PlaneGCS solver (sketch -> solve -> extrude).
 */
data class ProfilePoint(
    val x: Double,
    val y: Double,
)
