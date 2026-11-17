package dev.kstep.render.export

import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun tri(vararg v: Double) = TriangleMesh(v)

class MeshExportHostileInputTest :
    StringSpec({
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

        "non-finite coordinates fail closed in both writers" {
            for (v in bad) {
                val scene = MeshExportScene("h", listOf(part("h", tri(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, v, 0.0))))
                shouldThrow<IllegalArgumentException> { StlWriter.write(scene) }.message shouldContain
                    "mesh export refused"
                shouldThrow<IllegalArgumentException> { ThreeMfWriter.write(scene) }.message shouldContain
                    "mesh export refused"
            }
        }

        "a coordinate beyond float32 fails STL, beyond 1e9 mm fails 3MF" {
            val scene = MeshExportScene("h", listOf(part("h", tri(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1e300, 0.0))))
            shouldThrow<IllegalArgumentException> { StlWriter.write(scene) }
            shouldThrow<IllegalArgumentException> { ThreeMfWriter.write(scene) }
        }

        "more than the triangle cap is rejected before any output is produced" {
            val big = TriangleMesh(DoubleArray((RENDER_LIMITS_CAP + 1) * 9) { (it % 7).toDouble() })
            val scene = MeshExportScene("big", listOf(part("big", big)))
            shouldThrow<IllegalArgumentException> { StlWriter.write(scene) }.message shouldContain "more than"
            shouldThrow<IllegalArgumentException> { ThreeMfWriter.write(scene) }.message shouldContain "more than"
        }

        "instancing past the triangle cap is rejected too" {
            val twentyBoxes = TriangleMesh((0 until 20).flatMap { boxMesh().coordinates.toList() }.toDoubleArray())
            val scene = MeshExportScene("many", listOf(part("m", twentyBoxes, *Array(600) { Placement.IDENTITY })))
            shouldThrow<IllegalArgumentException> { StlWriter.write(scene) }
        }

        "too many parts or build items are rejected at scene construction" {
            val parts = (0..1000).map { part("p$it", tri(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)) }
            shouldThrow<IllegalArgumentException> { MeshExportScene("x", parts) }
            val items = Array(10_001) { Placement.IDENTITY }
            shouldThrow<IllegalArgumentException> { MeshExportScene("x", listOf(part("p", boxMesh(), *items))) }
        }

        "an empty scene and an all-degenerate mesh export nothing" {
            shouldThrow<IllegalArgumentException> { MeshExportScene("x", emptyList()) }
            val degenerate = tri(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0)
            shouldThrow<IllegalArgumentException> {
                StlWriter.write(
                    MeshExportScene("d", listOf(part("d", degenerate))),
                )
            }
            shouldThrow<IllegalArgumentException> {
                ThreeMfWriter.write(
                    MeshExportScene("d", listOf(part("d", degenerate))),
                )
            }
        }

        "a degenerate triangle next to a real one is dropped and counted" {
            val m = boxMesh()
            val withDegenerate =
                TriangleMesh(m.coordinates + doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0))
            val scene = MeshExportScene("d", listOf(part("d", withDegenerate)))
            val stl = StlWriter.write(scene)
            stl.droppedTriangleCount shouldBe 1
            stl.triangleCount shouldBe 12
            val mf = ThreeMfWriter.write(scene)
            mf.droppedTriangleCount shouldBe 1
            mf.triangleCount shouldBe 12
        }

        "hostile part names are sanitised and the 3MF stays well-formed" {
            val names =
                listOf(
                    "<script>&\"'",
                    "a\u0000b\u0007c",
                    "x".repeat(10_000),
                    "../../etc/passwd",
                    "\u202Eevil\uD83D\uDE00",
                    "",
                )
            for (n in names) {
                val scene = MeshExportScene(n, listOf(part(n, boxMesh())))
                val doc =
                    javax.xml.parsers.DocumentBuilderFactory
                        .newInstance()
                        .apply { isNamespaceAware = true }
                        .newDocumentBuilder()
                        .parse(
                            java.util.zip
                                .ZipInputStream(
                                    java.io.ByteArrayInputStream(ThreeMfWriter.write(scene).bytes),
                                ).let { z ->
                                    z.nextEntry
                                    z.nextEntry
                                    z.nextEntry
                                    java.io.ByteArrayInputStream(z.readBytes())
                                },
                        )
                val name =
                    (
                        doc
                            .getElementsByTagNameNS(
                                "*",
                                "object",
                            ).item(0) as org.w3c.dom.Element
                    ).getAttribute("name")
                (name.length <= 64) shouldBe true
                name.all { it.isLetterOrDigit() || it in " _.-" } shouldBe true
            }
        }

        "ExportNames.sanitize keeps the allowed set only and falls back" {
            ExportNames.sanitize("Bracket 1_a.b-c", "f") shouldBe "Bracket 1_a.b-c"
            ExportNames.sanitize("<>&", "f") shouldBe "f"
            ExportNames.sanitize("a/b\\c", "f") shouldBe "abc"
            ExportNames.sanitize("y".repeat(100), "f").length shouldBe 64
        }
    })

private const val RENDER_LIMITS_CAP = dev.kstep.render.RenderLimits.EXPORT_MAX_TRIANGLES
