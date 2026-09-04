package dev.kstep.render.mesh

import dev.kstep.geometry.TriangleMesh
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val CANVAS_WIDTH = 800.0
private const val CANVAS_HEIGHT = 600.0
private const val TOLERANCE = 1e-12

// One up-facing triangle, face normal (0,0,1), visible under Camera.ISOMETRIC (view direction
// normalize(-1,-1,-1); normal . view = -1/sqrt(3) < 0, so it survives backface culling).
private fun upFacingTriangleCoords(): DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

/**
 * Pins [MeshProjection.projectInternal]'s per-vertex ("smooth"/Gouraud) shading -- added in a
 * later wave, see docs/adr/ADR-0018-smooth-vertex-normals.adoc -- against [ProjectedTriangle.shade]
 * (the pre-existing flat, face-normal shade), which this wave's whole design REQUIRES stay
 * byte-for-byte untouched by [ProjectedTriangle.vertexShades]'s presence (Jobs' condition 6, see
 * that ADR's Design-Team-Sitzung section).
 */
class SmoothShadingTest :
    StringSpec({
        // p: no vertexNormals at all -> vertexShades stays null, shade computed exactly as before
        // this wave existed.
        "a mesh with no vertexNormals produces triangles with null vertexShades" {
            val mesh = TriangleMesh(upFacingTriangleCoords())
            val triangles = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            triangles.size shouldBe 1
            triangles.single().vertexShades shouldBe null
        }

        // q: Jobs' condition 6 as a test -- the SAME mesh, projected once with and once without
        // vertexNormals, must produce IDENTICAL shade/depth/screen-coordinates/color/order; only
        // vertexShades may differ.
        "adding vertexNormals to a mesh never changes its flat shade, depth, screen coordinates, color, or order" {
            val coords = upFacingTriangleCoords()
            val faceNormal = doubleArrayOf(0.0, 0.0, 1.0)
            val vertexNormals = faceNormal + faceNormal + faceNormal // replicated 3x, see test r below
            val withoutNormals = TriangleMesh(coords)
            val withNormals = TriangleMesh(coords, vertexNormals)

            val a = MeshProjection.project(withoutNormals, CANVAS_WIDTH, CANVAS_HEIGHT)
            val b = MeshProjection.project(withNormals, CANVAS_WIDTH, CANVAS_HEIGHT)

            a.size shouldBe b.size
            a.indices.forEach { i ->
                a[i].ax shouldBe b[i].ax
                a[i].ay shouldBe b[i].ay
                a[i].bx shouldBe b[i].bx
                a[i].by shouldBe b[i].by
                a[i].cx shouldBe b[i].cx
                a[i].cy shouldBe b[i].cy
                a[i].shade shouldBe b[i].shade
                a[i].depth shouldBe b[i].depth
                a[i].color shouldBe b[i].color
            }
            a.single().vertexShades shouldBe null
            (b.single().vertexShades != null) shouldBe true
        }

        // r: planar vertex normals (all three corners equal to the flat face normal) must
        // reproduce the flat shade exactly at every corner.
        "vertex normals equal to the flat face normal produce vertexShades identical to shade" {
            val coords = upFacingTriangleCoords()
            val faceNormal = doubleArrayOf(0.0, 0.0, 1.0)
            val mesh = TriangleMesh(coords, faceNormal + faceNormal + faceNormal)
            val t = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT).single()
            val vs = t.vertexShades!!
            vs.a shouldBe (t.shade plusOrMinus TOLERANCE)
            vs.b shouldBe (t.shade plusOrMinus TOLERANCE)
            vs.c shouldBe (t.shade plusOrMinus TOLERANCE)
        }

        // s: divergent per-vertex normals produce genuinely different, still-valid shades.
        "divergent vertex normals produce pairwise-different vertexShades, all within [0, 1]" {
            val coords = upFacingTriangleCoords()
            // Three visibly different normals, all still roughly "up-ish" so none flips into
            // territory the flat culling rule (computed from the FACE normal, unaffected by
            // these) would treat differently.
            val normals =
                doubleArrayOf(
                    0.0,
                    0.0,
                    1.0,
                    0.3,
                    0.3,
                    0.9,
                    -0.3,
                    0.2,
                    0.93,
                )
            val mesh = TriangleMesh(coords, normals)
            val t = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT).single()
            val vs = t.vertexShades!!
            (vs.a != vs.b) shouldBe true
            (vs.b != vs.c) shouldBe true
            (vs.a != vs.c) shouldBe true
            listOf(vs.a, vs.b, vs.c).forEach { (it in 0.0..1.0) shouldBe true }
        }

        // t: a degenerate (zero-length) vertex normal falls back to the triangle's flat shade,
        // never NaN.
        "a degenerate vertex normal falls back to the flat shade instead of producing NaN" {
            val coords = upFacingTriangleCoords()
            val normals =
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0, // degenerate
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    1.0,
                )
            val mesh = TriangleMesh(coords, normals)
            val t = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT).single()
            val vs = t.vertexShades!!
            vs.a.isNaN() shouldBe false
            vs.a shouldBe (t.shade plusOrMinus TOLERANCE)
        }

        // u: fitScale must be completely unaffected by vertexNormals' presence -- it only ever
        // reads ProjectionResult.scale.
        "fitScale is identical whether or not the mesh carries vertexNormals" {
            val coords = upFacingTriangleCoords()
            val faceNormal = doubleArrayOf(0.0, 0.0, 1.0)
            val withoutNormals = TriangleMesh(coords)
            val withNormals = TriangleMesh(coords, faceNormal + faceNormal + faceNormal)
            val scaleA = MeshProjection.fitScale(withoutNormals, CANVAS_WIDTH, CANVAS_HEIGHT)
            val scaleB = MeshProjection.fitScale(withNormals, CANVAS_WIDTH, CANVAS_HEIGHT)
            scaleA shouldBe scaleB
        }
    })
