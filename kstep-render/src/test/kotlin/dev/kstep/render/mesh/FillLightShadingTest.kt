package dev.kstep.render.mesh

import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.image.TriangleRasterizer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val CANVAS_WIDTH = 800.0
private const val CANVAS_HEIGHT = 600.0

/**
 * Pins the additive fill-light contribution (see
 * `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`) against a hand-built triangle
 * whose normal was deliberately chosen (by a small offline calculation, not eyeballed) to be
 * EXACTLY orthogonal to [MeshProjection]'s key-light direction -- so this triangle's key-only
 * contribution is zero and its ISOMETRIC shade would be exactly [MeshProjection.AMBIENT] (0.25)
 * under the pre-this-wave, key-light-only formula. Its normal is ALSO chosen to point close to
 * directly at the fill light (maximal alignment, computed by projecting the fill direction into
 * the plane orthogonal to the key light) -- the most favorable case for observing the fill light's
 * effect, which is exactly the case worth pinning: if this normal's shade did not rise measurably
 * above [MeshProjection.AMBIENT], no normal's would.
 *
 * The vertex coordinates below are NOT arbitrary -- they were derived so that
 * `cross(v1-v0, v2-v0)`, normalized, equals this chosen normal (to 6 decimal places). See this
 * wave's ADR for the derivation.
 */
class FillLightShadingTest :
    StringSpec({
        // normal ~= (0.816110, 0.461501, -0.347823) -- orthogonal to MeshProjection's key-light
        // direction, near-maximally aligned with its fill-light direction.
        fun orthogonalToKeyTriangleMesh(): TriangleMesh =
            TriangleMesh(
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    0.492236,
                    -0.870462,
                    0.0,
                    -0.302766,
                    -0.171211,
                    -0.93756,
                ),
            )

        "a triangle with zero key-light contribution is still shaded well above AMBIENT by the fill light" {
            val triangles = MeshProjection.project(orthogonalToKeyTriangleMesh(), CANVAS_WIDTH, CANVAS_HEIGHT)
            triangles.size shouldBe 1
            val shade = triangles.single().shade
            // Computed offline: AMBIENT + FILL_WEIGHT * (~0.99996) = ~0.42999. A key-only render
            // (this codebase's behavior before this wave) would have left this EXACT triangle at
            // AMBIENT (0.25) -- 0.38 is a safe floor well clear of both AMBIENT and measurement
            // noise, while still well above it.
            (shade >= 0.38) shouldBe true
            shade shouldBe (0.42999 plusOrMinus 0.01)
        }

        "the clamp caps shade at 1.0 even though key+fill can nominally exceed it" {
            // A triangle facing directly into BOTH negated lights at once is not geometrically
            // achievable (the two lights are not antiparallel -- see FILL_DIRECTION_HOME's own
            // KDoc), but AMBIENT + KEY_WEIGHT + FILL_WEIGHT = 1.18 on paper, so every real shade
            // value this pipeline can produce must still respect the [0, 1] contract regardless.
            val triangles = MeshProjection.project(orthogonalToKeyTriangleMesh(), CANVAS_WIDTH, CANVAS_HEIGHT)
            triangles.forEach { t -> (t.shade in MeshProjection.AMBIENT..1.0) shouldBe true }
        }

        "the fill-light-brightened triangle rasterizes to a visibly lighter gray than a key-only AMBIENT floor would" {
            val triangles = MeshProjection.project(orthogonalToKeyTriangleMesh(), CANVAS_WIDTH, CANVAS_HEIGHT)
            val image = TriangleRasterizer.render(triangles, CANVAS_WIDTH.toInt(), CANVAS_HEIGHT.toInt())
            // Sample the triangle's own centroid in screen space -- guaranteed inside the filled
            // path for a triangle this size relative to the canvas.
            val t = triangles.single()
            val centroidX = ((t.ax + t.bx + t.cx) / 3.0).toInt().coerceIn(0, image.width - 1)
            val centroidY = ((t.ay + t.by + t.cy) / 3.0).toInt().coerceIn(0, image.height - 1)
            val rgb = image.getRGB(centroidX, centroidY)
            val gray = rgb and 0xFF
            // Old key-only formula would have rasterized this exact triangle to gray = 63
            // (AMBIENT * 255, truncated). 96 is a safe floor comfortably above that.
            (gray >= 96) shouldBe true
        }
    })
