package dev.kstep.geometry

/**
 * An albedo multiplier (0..1 per channel) for one [PlacedMesh]/triangle, combined MULTIPLICATIVELY
 * with `dev.kstep.render.mesh.MeshProjection`'s two-light shading (see
 * `dev.kstep.render.mesh.ProjectedTriangle.litR`/`litG`/`litB`) -- not additive, not a separate
 * HSV/Lab tint pass. Added in kSTEP's viewer-pan-and-material-colors wave, see
 * docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc.
 *
 * [NEUTRAL] (`1.0, 1.0, 1.0`) reproduces BIT-IDENTICALLY the plain grayscale rendering this
 * codebase used before this wave (`shade * 1.0 == shade` for every channel) -- this is the
 * default for every pre-wave call site ([PlacedMesh.color], [ProjectedTriangle.color]), so no
 * existing render regresses.
 *
 * Recommended per-channel band: `0.40..0.95`. `MeshProjection.AMBIENT` is `0.25` -- an albedo
 * below roughly `0.40` goes nearly black on a triangle's shadow side (`shade` near `AMBIENT`),
 * making differently-colored parts hard to tell apart in a multi-part scene.
 *
 * @throws IllegalArgumentException if any channel is non-finite or outside `0.0..1.0`.
 */
data class MeshColor(
    val r: Double,
    val g: Double,
    val b: Double,
) {
    init {
        require(r.isFinite() && g.isFinite() && b.isFinite()) {
            "MeshColor components must be finite, got ($r, $g, $b)"
        }
        require(r in 0.0..1.0 && g in 0.0..1.0 && b in 0.0..1.0) {
            "MeshColor components must be in 0.0..1.0, got ($r, $g, $b)"
        }
    }

    companion object {
        /** Bit-identical to this codebase's pre-wave, colorless grayscale rendering -- see this
         *  class's own KDoc. Deliberately NOT a mid-gray: `shade * gray != shade` would regress
         *  every existing shade-based test/fixture. */
        val NEUTRAL = MeshColor(1.0, 1.0, 1.0)
    }
}
