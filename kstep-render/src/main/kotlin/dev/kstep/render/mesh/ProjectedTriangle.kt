package dev.kstep.render.mesh

import dev.kstep.geometry.MeshColor

/**
 * The three per-corner shade values (see [ProjectedTriangle.vertexShades]) for one triangle, in
 * the same `a, b, c` corner order as [ProjectedTriangle]'s own `ax/ay`, `bx/by`, `cx/cy`
 * properties.
 *
 * A dedicated `data class` -- NOT a `DoubleArray` field directly on [ProjectedTriangle] --
 * deliberately: [ProjectedTriangle] is itself a `data class`, and a `DoubleArray` property on a
 * `data class` gets the compiler-generated reference-based `equals()`/`hashCode()` (the same trap
 * [dev.kstep.geometry.TriangleMesh]'s own KDoc documents and avoids by NOT being a `data class`
 * at all). [ProjectedTriangle] stays a `data class` for its many existing simple `Double`
 * properties, so this wrapper closes the gap structurally instead of requiring a hand-written
 * `equals()`/`hashCode()` override on [ProjectedTriangle] itself.
 */
data class VertexShades(
    val a: Double,
    val b: Double,
    val c: Double,
)

/**
 * A projected, visible triangle in canvas coordinates (origin top-left, y grows downward --
 * both Compose's and AWT's convention).
 *
 * @property shade 0.0 (dark) .. 1.0 (light), a grayscale value for flat shading -- ALWAYS derived
 *   from the triangle's flat face normal, exactly as before [vertexShades] existed; never averaged
 *   or otherwise redefined by the presence of per-vertex normals (see [vertexShades]'s own KDoc).
 *   [dev.kstep.render.image.TriangleRasterizer] and [dev.kstep.render.svg.TriangleSvgWriter] read
 *   only this (via [litR]/[litG]/[litB]) and are therefore completely unaffected by
 *   [vertexShades]'s presence or absence -- see `PngSvgBitIdentityTest`.
 * @property depth Centroid depth along the view direction; LARGER means farther away. The list
 *   [MeshProjection.project] returns is already sorted descending by [depth] -- consumers
 *   draw it in list order (a painter's algorithm) without re-sorting.
 * @property color Per-triangle albedo multiplier, added in kSTEP's viewer-pan-and-material-colors
 *   wave (see docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc). Defaults to [MeshColor.NEUTRAL]
 *   as the LAST constructor parameter -- every pre-wave positional/named call site keeps compiling
 *   unchanged. Combine with [shade] via [litR]/[litG]/[litB], never by re-deriving the lighting
 *   formula at a consumer call site.
 * @property vertexShades Three per-corner shade values (`a, b, c`, matching
 *   `ax/ay`/`bx/by`/`cx/cy`), derived from the source [dev.kstep.geometry.TriangleMesh]'s
 *   per-vertex normals -- or `null` if that mesh carries none. Added in a later wave (see
 *   docs/adr/ADR-0018-smooth-vertex-normals.adoc); a `null` default keeps every pre-wave call site
 *   compiling unchanged. Consumers that can interpolate per-corner brightness (currently only
 *   `kstep-viewer`'s `ShapeCanvas`, via Skia's `drawVertices`) read this for smooth (Gouraud)
 *   shading; every other consumer keeps reading [shade]/[litR]/[litG]/[litB] and is unaffected.
 */
data class ProjectedTriangle(
    val ax: Double,
    val ay: Double,
    val bx: Double,
    val by: Double,
    val cx: Double,
    val cy: Double,
    val shade: Double,
    val depth: Double,
    val color: MeshColor = MeshColor.NEUTRAL,
    val vertexShades: VertexShades? = null,
) {
    /** [shade] multiplied by [color]'s red channel -- the lighting formula itself lives entirely
     *  in [MeshProjection] and is untouched by [color]; this is just the multiplicative combine
     *  every consumer (rasterizer, SVG writer, Compose draw loop) must apply identically. At
     *  [MeshColor.NEUTRAL] (`r = g = b = 1.0`) this is bit-identical to [shade] itself. */
    val litR: Double get() = shade * color.r

    /** See [litR] -- green channel. */
    val litG: Double get() = shade * color.g

    /** See [litR] -- blue channel. */
    val litB: Double get() = shade * color.b

    /**
     * `(red, green, blue)` at one corner (`0` = a, `1` = b, `2` = c), combining [color] with
     * either the matching entry of [vertexShades] (smooth shading) or plain [shade] (flat
     * fallback, when [vertexShades] is `null`) -- the per-corner analogue of [litR]/[litG]/[litB].
     *
     * @throws IllegalArgumentException if [corner] is not `0`, `1`, or `2`.
     */
    fun litRgbAt(corner: Int): DoubleArray {
        val cornerShade =
            when (corner) {
                0 -> vertexShades?.a ?: shade
                1 -> vertexShades?.b ?: shade
                2 -> vertexShades?.c ?: shade
                else -> throw IllegalArgumentException("corner must be 0, 1, or 2, got $corner")
            }
        return doubleArrayOf(cornerShade * color.r, cornerShade * color.g, cornerShade * color.b)
    }
}
