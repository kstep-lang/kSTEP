package dev.kstep.render.export

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.sqrt

class StlWriterTest :
    StringSpec({
        "a box exports as 12 records with a non-solid 80 byte header and zero attributes" {
            val r = StlWriter.write(MeshExportScene("box", listOf(part("box", boxMesh()))))
            r.byteLength shouldBe 84 + 12 * 50
            val read = readStl(r.bytes)
            read.count shouldBe 12
            String(read.header, Charsets.US_ASCII).startsWith("solid") shouldBe false
            String(read.header, Charsets.US_ASCII).trimEnd('\u0000') shouldBe "kSTEP binary STL, unit mm"
            read.attributes.all { it == 0 } shouldBe true
            r.triangleCount shouldBe 12
            r.watertight shouldBe true
            r.colorsDropped shouldBe false
            r.format shouldBe "stl"
        }

        "the volume recomputed from the read-back file is 6000 mm3 and normals are unit and outward" {
            val read = readStl(StlWriter.write(MeshExportScene("box", listOf(part("box", boxMesh())))).bytes)
            signedVolume(read.coords) shouldBe (6000.0 plusOrMinus 1e-9)
            for (t in 0 until read.count) {
                val n = read.normals
                sqrt(n[3 * t] * n[3 * t] + n[3 * t + 1] * n[3 * t + 1] + n[3 * t + 2] * n[3 * t + 2]) shouldBe
                    (1.0 plusOrMinus 1e-6)
            }
            // outward: the normal points away from the box centre (5,10,15)
            for (t in 0 until read.count) {
                val c = read.coords
                val mx = (c[9 * t] + c[9 * t + 3] + c[9 * t + 6]) / 3 - 5
                val my = (c[9 * t + 1] + c[9 * t + 4] + c[9 * t + 7]) / 3 - 10
                val mz = (c[9 * t + 2] + c[9 * t + 5] + c[9 * t + 8]) / 3 - 15
                (mx * read.normals[3 * t] + my * read.normals[3 * t + 1] + mz * read.normals[3 * t + 2] > 0) shouldBe
                    true
            }
        }

        "a tetrahedron with non-integer coordinates round-trips within float32 tolerance" {
            val a = doubleArrayOf(0.1, 0.2, 0.3)
            val b = doubleArrayOf(5.7, 0.2, 0.3)
            val c = doubleArrayOf(0.1, 4.9, 0.3)
            val d = doubleArrayOf(0.1, 0.2, 6.1)
            val tris = listOf(listOf(a, c, b), listOf(a, b, d), listOf(b, c, d), listOf(a, d, c))
            val mesh = TriangleMesh(tris.flatMap { t -> t.flatMap { it.toList() } }.toDoubleArray())
            val read = readStl(StlWriter.write(MeshExportScene("tet", listOf(part("tet", mesh)))).bytes)
            signedVolume(read.coords) shouldBe (signedVolume(mesh.coordinates) plusOrMinus 1e-4)
        }

        "two identical writes produce identical bytes" {
            val scene = MeshExportScene("box", listOf(part("box", boxMesh(), Placement.rotationZ(30.0))))
            StlWriter.write(scene).bytes.contentEquals(StlWriter.write(scene).bytes) shouldBe true
        }

        "an assembly is merged with placements applied and carries a merge warning" {
            val scene =
                MeshExportScene(
                    "asm",
                    listOf(part("box", boxMesh(), Placement.IDENTITY, Placement.translation(100.0, 0.0, 0.0))),
                )
            val r = StlWriter.write(scene)
            r.triangleCount shouldBe 24
            r.buildItemCount shouldBe 2
            val read = readStl(r.bytes)
            var maxX = -1e9
            for (i in 0 until read.count * 3) maxX = maxOf(maxX, read.coords[3 * i])
            maxX shouldBe (110.0 plusOrMinus 1e-6)
            r.warnings.any { it.contains("merges 2 placed instance") } shouldBe true
        }

        "colours are dropped explicitly" {
            val coloured = part("box", boxMesh(), color = MeshColor(1.0, 0.0, 0.0))
            val r = StlWriter.write(MeshExportScene("c", listOf(coloured)))
            r.colorsDropped shouldBe true
            r.warnings.joinToString() shouldContain "stl carries no colours; 1 colour(s) dropped"
            readStl(r.bytes).attributes.all { it == 0 } shouldBe true
        }

        "an open mesh is still written but reported not watertight" {
            val m = boxMesh()
            val open = TriangleMesh(m.coordinates.copyOfRange(9, m.coordinates.size))
            val r = StlWriter.write(MeshExportScene("open", listOf(part("open", open))))
            r.watertight shouldBe false
            r.openEdgeCount shouldBe 3
            r.warnings.joinToString() shouldContain "not watertight"
        }
    })
