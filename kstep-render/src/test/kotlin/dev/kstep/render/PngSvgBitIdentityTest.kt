package dev.kstep.render

import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.image.TriangleRasterizer
import dev.kstep.render.mesh.MeshProjection
import dev.kstep.render.svg.TriangleSvgWriter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private const val CANVAS_WIDTH = 800.0
private const val CANVAS_HEIGHT = 600.0
private const val CANVAS_WIDTH_INT = 800
private const val CANVAS_HEIGHT_INT = 600

/**
 * Jobs' condition 6 (see docs/adr/ADR-0018-smooth-vertex-normals.adoc's Design-Team-Sitzung):
 * [dev.kstep.render.image.TriangleRasterizer] and [dev.kstep.render.svg.TriangleSvgWriter] read
 * ONLY [dev.kstep.render.mesh.ProjectedTriangle.litR]/`litG`/`litB` (which derive from `shade`,
 * never `vertexShades`) -- so PNG/SVG output for the SAME mesh, with and without
 * [TriangleMesh.vertexNormals], must be BYTE-IDENTICAL. `SmoothShadingTest` already pins `shade`
 * itself as unaffected; this suite is the end-to-end proof at the actual output-format level.
 */
class PngSvgBitIdentityTest :
    StringSpec({
        fun upFacingTriangleCoords(): DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        fun pngBytes(mesh: TriangleMesh): ByteArray {
            val triangles = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            val image = TriangleRasterizer.render(triangles, CANVAS_WIDTH_INT, CANVAS_HEIGHT_INT)
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            return out.toByteArray()
        }

        fun svgString(mesh: TriangleMesh): String {
            val triangles = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            return TriangleSvgWriter.render(triangles, CANVAS_WIDTH_INT, CANVAS_HEIGHT_INT)
        }

        "PNG output is byte-identical whether or not the mesh carries vertexNormals" {
            val coords = upFacingTriangleCoords()
            val faceNormal = doubleArrayOf(0.0, 0.0, 1.0)
            val withoutNormals = TriangleMesh(coords)
            val withNormals = TriangleMesh(coords, faceNormal + faceNormal + faceNormal)
            pngBytes(withoutNormals).contentEquals(pngBytes(withNormals)) shouldBe true
        }

        "PNG output is byte-identical even with genuinely divergent vertexNormals" {
            val coords = upFacingTriangleCoords()
            val faceNormal = doubleArrayOf(0.0, 0.0, 1.0)
            val divergentNormals = doubleArrayOf(0.0, 0.0, 1.0, 0.3, 0.3, 0.9, -0.3, 0.2, 0.93)
            val withoutNormals = TriangleMesh(coords)
            val withDivergentNormals = TriangleMesh(coords, divergentNormals)
            pngBytes(withoutNormals).contentEquals(pngBytes(withDivergentNormals)) shouldBe true
            // Sanity: divergentNormals really is a different normal set from a flat replication --
            // this rules out a vacuously-true byte-identity from an accidentally-degenerate case.
            (divergentNormals.toList() != (faceNormal + faceNormal + faceNormal).toList()) shouldBe true
        }

        "SVG output is byte-identical whether or not the mesh carries vertexNormals" {
            val coords = upFacingTriangleCoords()
            val faceNormal = doubleArrayOf(0.0, 0.0, 1.0)
            val withoutNormals = TriangleMesh(coords)
            val withNormals = TriangleMesh(coords, faceNormal + faceNormal + faceNormal)
            svgString(withoutNormals) shouldBe svgString(withNormals)
        }

        "SVG output is byte-identical even with genuinely divergent vertexNormals" {
            val coords = upFacingTriangleCoords()
            val divergentNormals = doubleArrayOf(0.0, 0.0, 1.0, 0.3, 0.3, 0.9, -0.3, 0.2, 0.93)
            val withoutNormals = TriangleMesh(coords)
            val withDivergentNormals = TriangleMesh(coords, divergentNormals)
            svgString(withoutNormals) shouldBe svgString(withDivergentNormals)
        }
    })
