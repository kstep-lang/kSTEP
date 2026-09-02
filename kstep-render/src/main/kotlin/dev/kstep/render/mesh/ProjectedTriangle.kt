package dev.kstep.render.mesh

/**
 * A projected, visible triangle in canvas coordinates (origin top-left, y grows downward --
 * both Compose's and AWT's convention).
 *
 * @property shade 0.0 (dark) .. 1.0 (light), a grayscale value for flat shading.
 * @property depth Centroid depth along the view direction; LARGER means farther away. The list
 *   [IsometricProjection.project] returns is already sorted descending by [depth] -- consumers
 *   draw it in list order (a painter's algorithm) without re-sorting.
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
)
