package dev.kstep.render.mesh

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** ADR-0053: source indices on projected triangles, and the screen frame that makes picking exact. */
class ProjectFramedTest :
    StringSpec({
        // 0: visible (normal +Z), 1: degenerate, 2: back-facing (normal -Z), 3: visible, shifted in x
        val coords =
            doubleArrayOf(
                0.0,
                0.0,
                0.0,
                10.0,
                0.0,
                0.0,
                0.0,
                10.0,
                0.0,
                1.0,
                1.0,
                1.0,
                2.0,
                2.0,
                2.0,
                3.0,
                3.0,
                3.0,
                0.0,
                0.0,
                5.0,
                0.0,
                10.0,
                5.0,
                10.0,
                0.0,
                5.0,
                20.0,
                0.0,
                0.0,
                30.0,
                0.0,
                0.0,
                20.0,
                10.0,
                0.0,
            )
        val mesh = TriangleMesh(coords)

        "the source index is the index in the mesh, also after sorting and culling" {
            val sources = MeshProjection.project(mesh, 400.0, 300.0).map { it.sourceIndex }
            sources.sorted() shouldBe listOf(0, 3)
        }

        "a hand-built triangle has source index -1" {
            ProjectedTriangle(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0).sourceIndex shouldBe -1
        }

        "projectFramed returns exactly the triangles of project" {
            val camera = Camera.of(30.0, 20.0)
            val a = MeshProjection.project(mesh, 400.0, 300.0, camera, scale = 7.5, panX = 12.0, panY = -4.0)
            val b = MeshProjection.projectFramed(mesh, 400.0, 300.0, camera, scale = 7.5, panX = 12.0, panY = -4.0)
            b.triangles shouldBe a
        }

        "projectFramed of a coloured mesh keeps source indices and colours" {
            val colored = MeshComposition.mergeColored(listOf(PlacedMesh(mesh, color = MeshColor(1.0, 0.0, 0.0))))
            val framed = MeshProjection.projectFramed(colored, 400.0, 300.0)
            framed.triangles.map { it.sourceIndex }.sorted() shouldBe listOf(0, 3)
            framed.triangles.all { it.color == MeshColor(1.0, 0.0, 0.0) } shouldBe true
        }

        "worldPointAt inverts the projection of a vertex, with pan and zoom" {
            for (camera in listOf(Camera.ISOMETRIC, Camera.of(80.0, 10.0), Camera.of(-120.0, 55.0))) {
                val framed =
                    MeshProjection.projectFramed(mesh, 640.0, 480.0, camera, scale = 11.0, panX = 33.0, panY = -21.0)
                val frame = framed.frame.shouldNotBeNull()
                val view = frame.view
                for (t in framed.triangles) {
                    val o = t.sourceIndex * 9
                    val vertices = listOf(t.ax to t.ay, t.bx to t.by, t.cx to t.cy)
                    for ((k, px) in vertices.withIndex()) {
                        val w = DoubleArray(3) { coords[o + k * 3 + it] }
                        val along = w[0] * view[0] + w[1] * view[1] + w[2] * view[2]
                        val expected = DoubleArray(3) { w[it] - along * view[it] }
                        val got = frame.worldPointAt(px.first, px.second)
                        for (axis in 0 until 3) got[axis].shouldBe(expected[axis].plusOrMinus(1e-9))
                    }
                }
            }
        }

        "an empty mesh and a fully culled mesh have no frame" {
            MeshProjection.projectFramed(TriangleMesh(DoubleArray(0)), 100.0, 100.0).frame.shouldBeNull()
            val backFacing = TriangleMesh(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 10.0, 0.0, 0.0))
            MeshProjection.projectFramed(backFacing, 100.0, 100.0).frame.shouldBeNull()
        }

        "sameMappingAs is true for equal projections and false after a pan" {
            val a = MeshProjection.projectFramed(mesh, 400.0, 300.0).frame.shouldNotBeNull()
            val b = MeshProjection.projectFramed(mesh, 400.0, 300.0).frame.shouldNotBeNull()
            val c = MeshProjection.projectFramed(mesh, 400.0, 300.0, panX = 5.0).frame.shouldNotBeNull()
            a.sameMappingAs(b) shouldBe true
            a.sameMappingAs(c) shouldBe false
        }
    })
