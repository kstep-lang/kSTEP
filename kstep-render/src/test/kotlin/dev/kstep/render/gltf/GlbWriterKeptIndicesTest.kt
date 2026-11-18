package dev.kstep.render.gltf

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** ADR-0053: the GLB writer reports which source triangles it wrote (it drops degenerate / non-finite ones). */
class GlbWriterKeptIndicesTest :
    StringSpec({
        val good = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val degenerate = doubleArrayOf(1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 3.0, 3.0, 3.0)
        val nan = doubleArrayOf(Double.NaN, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val mesh = TriangleMesh(good + degenerate + nan + good)

        "dropped triangles do not appear in the kept indices" {
            val result = GlbWriter.write(mesh)
            result.triangleCount shouldBe 2
            result.droppedTriangleCount shouldBe 2
            result.keptSourceTriangles.toList() shouldBe listOf(0, 3)
        }

        "the coloured overload reports the same indices" {
            val colored = MeshComposition.mergeColored(listOf(PlacedMesh(mesh, color = MeshColor(0.2, 0.4, 0.6))))
            GlbWriter.write(colored).keptSourceTriangles.toList() shouldBe listOf(0, 3)
        }

        "the all-neutral delegation path reports the same indices" {
            val colored = MeshComposition.mergeColored(listOf(PlacedMesh(mesh)))
            GlbWriter.write(colored).keptSourceTriangles.toList() shouldBe listOf(0, 3)
        }

        "a mesh without dropped triangles keeps every index" {
            GlbWriter.write(TriangleMesh(good + good + good)).keptSourceTriangles.toList() shouldBe listOf(0, 1, 2)
        }

        "the empty scene and a fully dropped mesh have no kept indices" {
            GlbWriter.writeEmptyScene().keptSourceTriangles.size shouldBe 0
            GlbWriter.write(TriangleMesh(degenerate)).keptSourceTriangles.size shouldBe 0
        }
    })
