package dev.kstep.render.svg

import dev.kstep.render.mesh.ProjectedTriangle
import dev.kstep.render.parseXmlSecurely
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun triangle(
    depth: Double,
    shade: Double = 0.5,
) = ProjectedTriangle(ax = 0.0, ay = 0.0, bx = 10.0, by = 0.0, cx = 5.0, cy = 10.0, shade = shade, depth = depth)

class TriangleSvgWriterTest :
    StringSpec({
        "an empty triangle list still produces a well-formed, empty-of-polygons SVG" {
            val svg = TriangleSvgWriter.render(emptyList(), 200, 100)
            svg shouldContain "<svg"
            svg shouldContain "</svg>"
            svg shouldContain "width=\"200\""
            svg shouldContain "height=\"100\""
            svg.contains("<polygon") shouldBe false
            parseXmlSecurely(svg) // must not throw
        }

        "polygon count matches input triangle count" {
            val triangles = listOf(triangle(depth = 1.0), triangle(depth = 2.0), triangle(depth = 3.0))
            val svg = TriangleSvgWriter.render(triangles, 200, 100)
            (svg.split("<polygon").size - 1) shouldBe 3
            parseXmlSecurely(svg)
        }

        "triangles are painted in input order, not re-sorted by depth" {
            // Deliberately out of depth order -- TriangleSvgWriter must NOT re-sort (painter's
            // algorithm order is IsometricProjection's responsibility, not this writer's).
            val triangles = listOf(triangle(depth = 3.0, shade = 0.1), triangle(depth = 1.0, shade = 0.9))
            val svg = TriangleSvgWriter.render(triangles, 200, 100)
            // gray = (shade * 255.0).toInt(): 0.1 -> 25 (0x19), 0.9 -> 229 (0xe5).
            val firstFillIndex = svg.indexOf("fill=\"#191919\"")
            val secondFillIndex = svg.indexOf("fill=\"#e5e5e5\"")
            (firstFillIndex in 0 until secondFillIndex) shouldBe true
        }

        "rendering the same input twice produces byte-identical output" {
            val triangles = listOf(triangle(depth = 1.0), triangle(depth = 2.5, shade = 0.75))
            val first = TriangleSvgWriter.render(triangles, 400, 300)
            val second = TriangleSvgWriter.render(triangles, 400, 300)
            first shouldBe second
        }

        "each polygon carries a same-colored fill and stroke" {
            val svg = TriangleSvgWriter.render(listOf(triangle(depth = 1.0, shade = 0.5)), 200, 100)
            // gray = (0.5 * 255.0).toInt() == 127 == 0x7f.
            svg shouldContain "fill=\"#7f7f7f\""
            svg shouldContain "stroke=\"#7f7f7f\""
        }
    })
