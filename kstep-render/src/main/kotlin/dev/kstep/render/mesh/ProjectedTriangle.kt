package dev.kstep.render.mesh

import dev.kstep.geometry.MeshColor

/**
 * A projected, visible triangle in canvas coordinates (origin top-left, y grows downward --
 * both Compose's and AWT's convention).
 *
 * @property shade 0.0 (dark) .. 1.0 (light), a grayscale value for flat shading.
 * @property depth Centroid depth along the view direction; LARGER means farther away. The list
 *   [MeshProjection.project] returns is already sorted descending by [depth] -- consumers
 *   draw it in list order (a painter's algorithm) without re-sorting.
 * @property color Per-triangle albedo multiplier, added in kSTEP's viewer-pan-and-material-colors
 *   wave (see docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc). Defaults to [MeshColor.NEUTRAL]
 *   as the LAST constructor parameter -- every pre-wave positional/named call site keeps compiling
 *   unchanged. Combine with [shade] via [litR]/[litG]/[litB], never by re-deriving the lighting
 *   formula at a consumer call site.
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
}
