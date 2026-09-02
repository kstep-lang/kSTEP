package dev.kstep.viewer.mesh

import dev.kstep.geometry.TriangleMesh
import kotlin.math.max
import kotlin.math.min

/**
 * Pure data transformation `TriangleMesh -> List<ProjectedTriangle>`. Knows nothing about
 * Compose (see this file's package -- `dev.kstep.viewer.mesh` -- and
 * `MeshPackageComposeBoundaryTest`'s automated import check) -- which is exactly why it is
 * testable without a window, and why both the Compose canvas AND the headless AWT test
 * rasterizer consume the SAME list from THIS function, instead of two similar paths.
 *
 * [project] is a pure, stateless function -- no object-level mutable state -- so it is safe to
 * call concurrently (e.g. from a Compose recomposition on one thread while a test calls it on
 * another).
 */
object IsometricProjection {
    /**
     * Fixed view direction (from the viewer, into the scene), `normalize(-1, -1, -1)`.
     *
     * `internal`, not public: [Vec3] itself is internal (an implementation detail this package
     * does not expose), so a public `val` of this type here would be a "public property exposes
     * its internal type" compile error.
     */
    internal val VIEW_DIRECTION: Vec3 = Vec3(-1.0, -1.0, -1.0).normalized()

    /**
     * Fixed key-light direction (from the light, into the scene) -- DELIBERATELY NOT
     * [VIEW_DIRECTION]. Verified by actually rendering a box and looking at the PNG (see
     * `docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc`'s Stolperfallen): a light shining
     * exactly along the camera's own view direction makes every visible face of an
     * axis-aligned solid IDENTICALLY shaded -- the three visible faces' normals `(1,0,0)`,
     * `(0,1,0)`, `(0,0,1)` are each exactly `1/sqrt(3)` from `-VIEW_DIRECTION`, so a box rendered
     * that way is a flat, uniform-gray hexagon with no visible face separation at all (the
     * PNG this bug produced is preserved in this wave's implementation notes). Offsetting the
     * light from the camera -- a standard technique in isometric CAD/game rendering -- gives the
     * three faces genuinely different shades instead.
     */
    internal val LIGHT_DIRECTION: Vec3 = Vec3(-0.2, -0.4, -1.0).normalized()

    /** World-space "up" for the screen basis. */
    internal val UP: Vec3 = Vec3(0.0, 0.0, 1.0)

    const val MARGIN_FRACTION: Double = 0.05
    const val AMBIENT: Double = 0.25

    private const val DEGENERATE_LENGTH: Double = 1e-12

    private data class RawTriangle(
        val ax: Double,
        val ay: Double,
        val bx: Double,
        val by: Double,
        val cx: Double,
        val cy: Double,
        val shade: Double,
        val depth: Double,
    )

    fun project(
        mesh: TriangleMesh,
        canvasWidth: Double,
        canvasHeight: Double,
    ): List<ProjectedTriangle> {
        require(canvasWidth > 0.0 && canvasHeight > 0.0) {
            "canvasWidth/canvasHeight must be positive, got ($canvasWidth, $canvasHeight)"
        }
        if (mesh.triangleCount == 0) return emptyList()

        // UP is not parallel to VIEW_DIRECTION for the fixed isometric direction above, so this
        // cross product is never zero in practice -- the Vec3.ZERO fallbacks below make a future
        // change to VIEW_DIRECTION/UP fail safe (a degenerate, but still finite, screen basis)
        // instead of propagating NaNs downstream.
        val right = (UP cross VIEW_DIRECTION).normalized().let { if (it == Vec3.ZERO) Vec3(1.0, 0.0, 0.0) else it }
        val trueUp = (VIEW_DIRECTION cross right).normalized().let { if (it == Vec3.ZERO) Vec3(0.0, 1.0, 0.0) else it }
        val negLightDirection = Vec3(-LIGHT_DIRECTION.x, -LIGHT_DIRECTION.y, -LIGHT_DIRECTION.z)

        val raw = ArrayList<RawTriangle>(mesh.triangleCount)
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        val c = mesh.coordinates
        var i = 0
        while (i < c.size) {
            val v0 = Vec3(c[i], c[i + 1], c[i + 2])
            val v1 = Vec3(c[i + 3], c[i + 4], c[i + 5])
            val v2 = Vec3(c[i + 6], c[i + 7], c[i + 8])
            i += 9

            val normal = (v1 - v0) cross (v2 - v0)
            val normalLength = normal.length()
            if (normalLength <= DEGENERATE_LENGTH) {
                continue // degenerate (zero-area) triangle
            }

            // Backface culling: a normal pointing away from the viewer has a non-negative
            // component along VIEW_DIRECTION (which points FROM the viewer INTO the scene).
            if ((normal dot VIEW_DIRECTION) >= 0.0) continue

            val unitNormal = Vec3(normal.x / normalLength, normal.y / normalLength, normal.z / normalLength)
            val shade = AMBIENT + (1.0 - AMBIENT) * max(0.0, unitNormal dot negLightDirection)

            val ax = v0 dot right
            val ay = -(v0 dot trueUp)
            val bx = v1 dot right
            val by = -(v1 dot trueUp)
            val cx = v2 dot right
            val cy = -(v2 dot trueUp)

            minX = min(minX, min(ax, min(bx, cx)))
            maxX = max(maxX, max(ax, max(bx, cx)))
            minY = min(minY, min(ay, min(by, cy)))
            maxY = max(maxY, max(ay, max(by, cy)))

            val centroid = Vec3((v0.x + v1.x + v2.x) / 3.0, (v0.y + v1.y + v2.y) / 3.0, (v0.z + v1.z + v2.z) / 3.0)
            val depth = centroid dot VIEW_DIRECTION

            raw.add(RawTriangle(ax, ay, bx, by, cx, cy, shade, depth))
        }

        if (raw.isEmpty()) return emptyList()

        val bboxWidth = maxX - minX
        val bboxHeight = maxY - minY
        val widthFits = bboxWidth > DEGENERATE_LENGTH
        val heightFits = bboxHeight > DEGENERATE_LENGTH
        val scale =
            when {
                !widthFits && !heightFits -> 1.0
                !heightFits -> (canvasWidth * (1.0 - 2.0 * MARGIN_FRACTION)) / bboxWidth
                !widthFits -> (canvasHeight * (1.0 - 2.0 * MARGIN_FRACTION)) / bboxHeight
                else ->
                    min(
                        (canvasWidth * (1.0 - 2.0 * MARGIN_FRACTION)) / bboxWidth,
                        (canvasHeight * (1.0 - 2.0 * MARGIN_FRACTION)) / bboxHeight,
                    )
            }
        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0
        val halfW = canvasWidth / 2.0
        val halfH = canvasHeight / 2.0

        return raw
            .map { t ->
                ProjectedTriangle(
                    ax = (t.ax - centerX) * scale + halfW,
                    ay = (t.ay - centerY) * scale + halfH,
                    bx = (t.bx - centerX) * scale + halfW,
                    by = (t.by - centerY) * scale + halfH,
                    cx = (t.cx - centerX) * scale + halfW,
                    cy = (t.cy - centerY) * scale + halfH,
                    shade = t.shade,
                    depth = t.depth,
                )
            }.sortedByDescending { it.depth }
    }
}
