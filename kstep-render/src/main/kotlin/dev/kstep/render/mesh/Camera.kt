package dev.kstep.render.mesh

import kotlin.math.cos
import kotlin.math.sin

/**
 * A camera pose expressed as spherical angles around a fixed world-space "up"
 * ([MeshProjection]'s `UP`, unchanged by this class) -- azimuth around that up axis, elevation
 * above/below the horizon plane.
 *
 * `Camera` never holds an invalid or non-finite pose: [of] is the only way to build one, and it
 * normalizes/clamps eagerly so every other place in this codebase (today [MeshProjection], a
 * future `kstep-viewer` drag handler, a future R-2 CLI `--azimuth`/`--elevation` flag) inherits
 * the same guarantees for free instead of re-validating.
 *
 * Public and `data`-like (structural [equals]/[hashCode]) rather than `internal` like [Vec3] --
 * unlike [Vec3], a `Camera` value crosses the `kstep-render`/`kstep-viewer` module boundary (see
 * `MeshProjection.project`'s `camera` parameter), so it cannot be `internal`.
 */
class Camera private constructor(
    val azimuthDeg: Double,
    val elevationDeg: Double,
) {
    override fun equals(other: Any?): Boolean =
        other is Camera && other.azimuthDeg == azimuthDeg && other.elevationDeg == elevationDeg

    override fun hashCode(): Int = 31 * azimuthDeg.hashCode() + elevationDeg.hashCode()

    override fun toString(): String = "Camera(azimuthDeg=$azimuthDeg, elevationDeg=$elevationDeg)"

    /**
     * View direction (from the viewer, into the scene) for this pose, derived from
     * [azimuthDeg]/[elevationDeg] via the standard spherical-to-Cartesian formula.
     *
     * `internal`, not public: [Vec3] itself is internal, so a public function returning it here
     * would be a "public function exposes its internal return type" compile error -- exactly
     * [MeshPackageComposeBoundaryTest]'s sibling problem for a `val`, but for a `fun`. Used only
     * inside this package ([MeshProjection]).
     *
     * Does NOT special-case [ISOMETRIC] -- the bit-identical-to-today literal in [ISOMETRIC] is a
     * property of *that one instance's stored angles*, not of this derivation; a structurally
     * equal but separately constructed `Camera.of(45.0, 35.264389682754654)` derives its view
     * direction through this same trig path and gets a value that is extremely close to, but not
     * necessarily bit-identical to, `Vec3(-1,-1,-1).normalized()` (see `CameraProjectionTest`'s
     * cross-check, tolerance `1e-12`). [MeshProjection] is the one place that special-cases the
     * literal -- by comparing `camera === ISOMETRIC` -- specifically so the headless default path
     * (no explicit `camera` argument anywhere in this codebase today) never drifts by so much as
     * an ULP from what it produced before this wave.
     */
    internal fun viewDirection(): Vec3 {
        val azRad = Math.toRadians(azimuthDeg)
        val elRad = Math.toRadians(elevationDeg)
        val cosEl = cos(elRad)
        return Vec3(
            -(cosEl * cos(azRad)),
            -(cosEl * sin(azRad)),
            -sin(elRad),
        )
    }

    companion object {
        private const val MIN_ELEVATION_DEG = -89.0
        private const val MAX_ELEVATION_DEG = 89.0
        private const val FULL_TURN_DEG = 360.0

        /**
         * Builds a [Camera], normalizing [azimuthDeg] into `[0, 360)` and clamping [elevationDeg]
         * into `[-89, 89]` -- never exactly the poles, where [MeshProjection]'s `UP cross view`
         * screen basis would degenerate (see its `Vec3.ZERO` fallback comment).
         *
         * Throws [IllegalArgumentException] for a non-finite input -- BEFORE normalizing/clamping,
         * so a `NaN`/`Infinity` delta from a caller (e.g. a malformed drag event) fails loudly here
         * instead of silently producing a finite-looking but meaningless pose (`NaN % 360.0` is
         * itself `NaN`, which `coerceIn` on a finite range would otherwise pass through as `NaN`
         * for the elevation half too).
         */
        fun of(
            azimuthDeg: Double,
            elevationDeg: Double,
        ): Camera {
            require(azimuthDeg.isFinite() && elevationDeg.isFinite()) {
                "azimuthDeg/elevationDeg must be finite, got ($azimuthDeg, $elevationDeg)"
            }
            val normalizedAzimuth = ((azimuthDeg % FULL_TURN_DEG) + FULL_TURN_DEG) % FULL_TURN_DEG
            val clampedElevation = elevationDeg.coerceIn(MIN_ELEVATION_DEG, MAX_ELEVATION_DEG)
            return Camera(normalizedAzimuth, clampedElevation)
        }

        /**
         * The fixed isometric "home" pose every call site in this codebase used exclusively before
         * this wave (`MeshProjection`'s former fixed `VIEW_DIRECTION` constant,
         * `normalize(-1, -1, -1)`). Its `elevationDeg` is `asin(1/sqrt(3))` in degrees -- the
         * elevation whose [viewDirection] trig derivation reproduces `normalize(-1,-1,-1)` (see
         * `CameraProjectionTest`'s cross-check, which verifies this to `1e-12`) -- but this `val`
         * itself is a plain literal, not computed from that formula, precisely so it never depends
         * on floating-point trig evaluation order.
         */
        val ISOMETRIC: Camera = of(45.0, 35.264389682754654)
    }
}
