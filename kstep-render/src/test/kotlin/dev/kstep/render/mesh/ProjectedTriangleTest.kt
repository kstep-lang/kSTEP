package dev.kstep.render.mesh

import dev.kstep.geometry.MeshColor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val TOLERANCE = 1e-12

/** An arbitrary, non-degenerate triangle -- the screen-space coordinates and [ProjectedTriangle.depth]
 *  are irrelevant to [ProjectedTriangle.litRgbAt], so they are fixed placeholders throughout. */
private fun triangle(
    shade: Double = 0.5,
    color: MeshColor = MeshColor(0.8, 0.6, 0.4),
    vertexShades: VertexShades? = null,
): ProjectedTriangle =
    ProjectedTriangle(
        ax = 0.0,
        ay = 0.0,
        bx = 1.0,
        by = 0.0,
        cx = 0.0,
        cy = 1.0,
        shade = shade,
        depth = 1.0,
        color = color,
        vertexShades = vertexShades,
    )

/**
 * Direct unit coverage for [ProjectedTriangle.litRgbAt] -- flagged as untested during
 * ADR-0018's (smooth vertex normals) review: its only call site
 * ([dev.kstep.viewer.ui.ViewerCanvas]'s `ShapeCanvas`) is always in the smooth path (a mesh with
 * `vertexShades != null`), so neither the flat (`vertexShades == null`) fallback branch nor the
 * out-of-range [IllegalArgumentException] were exercised anywhere in the repo.
 */
class ProjectedTriangleTest :
    StringSpec({
        "litRgbAt combines each corner's vertexShades value with color, per corner" {
            val vs = VertexShades(a = 0.2, b = 0.5, c = 0.9)
            val color = MeshColor(0.8, 0.6, 0.4)
            val t = triangle(shade = 0.5, color = color, vertexShades = vs)

            val rgbA = t.litRgbAt(0)
            rgbA[0] shouldBe (vs.a * color.r plusOrMinus TOLERANCE)
            rgbA[1] shouldBe (vs.a * color.g plusOrMinus TOLERANCE)
            rgbA[2] shouldBe (vs.a * color.b plusOrMinus TOLERANCE)

            val rgbB = t.litRgbAt(1)
            rgbB[0] shouldBe (vs.b * color.r plusOrMinus TOLERANCE)
            rgbB[1] shouldBe (vs.b * color.g plusOrMinus TOLERANCE)
            rgbB[2] shouldBe (vs.b * color.b plusOrMinus TOLERANCE)

            val rgbC = t.litRgbAt(2)
            rgbC[0] shouldBe (vs.c * color.r plusOrMinus TOLERANCE)
            rgbC[1] shouldBe (vs.c * color.g plusOrMinus TOLERANCE)
            rgbC[2] shouldBe (vs.c * color.b plusOrMinus TOLERANCE)
        }

        "litRgbAt falls back to the flat shade at every corner when vertexShades is null" {
            val color = MeshColor(0.8, 0.6, 0.4)
            val t = triangle(shade = 0.5, color = color, vertexShades = null)

            for (corner in 0..2) {
                val rgb = t.litRgbAt(corner)
                rgb[0] shouldBe (t.shade * color.r plusOrMinus TOLERANCE)
                rgb[1] shouldBe (t.shade * color.g plusOrMinus TOLERANCE)
                rgb[2] shouldBe (t.shade * color.b plusOrMinus TOLERANCE)
            }
        }

        "litRgbAt throws IllegalArgumentException for a negative corner" {
            val t = triangle()
            shouldThrow<IllegalArgumentException> { t.litRgbAt(-1) }
        }

        "litRgbAt throws IllegalArgumentException for a corner beyond 2" {
            val t = triangle()
            shouldThrow<IllegalArgumentException> { t.litRgbAt(3) }
        }
    })
