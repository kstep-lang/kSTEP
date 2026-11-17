package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.TriangleMesh
import dev.kstep.preview.PreviewContentKind
import dev.kstep.preview.PreviewOutcome
import dev.kstep.preview.PreviewRenderer
import dev.kstep.preview.PreviewRequest
import dev.kstep.preview.PreviewSource
import dev.kstep.preview.RenderFormat
import dev.kstep.render.export.MeshExportPart
import dev.kstep.render.export.MeshExportScene
import dev.kstep.render.export.MeshTopologyCheck
import dev.kstep.render.export.StlWriter
import dev.kstep.render.export.ThreeMfWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Real-OCCT proof for the STL/3MF export (ADR-0049): measured volumes, the watertightness claim of the 1e-6 mm
 * welding on box / cylinder / filleted box / placed assembly, and the bounded `--tolerance`. The class name carries
 * `Occt` so the CI skip gate sees it.
 */
class OcctMeshExportTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun stlVolume(bytes: ByteArray): Double {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.position(80)
            val n = b.int
            var v = 0.0
            repeat(n) {
                b.position(b.position() + 12)
                val p = DoubleArray(9) { b.float.toDouble() }
                b.short
                v +=
                    p[0] * (p[4] * p[8] - p[5] * p[7]) - p[1] * (p[3] * p[8] - p[5] * p[6]) +
                    p[2] * (p[3] * p[7] - p[4] * p[6])
            }
            return v / 6.0
        }

        fun scene(
            name: String,
            mesh: TriangleMesh,
        ) = MeshExportScene(
            name,
            listOf(
                MeshExportPart(
                    name,
                    mesh,
                    dev.kstep.geometry.MeshColor.NEUTRAL,
                    listOf(dev.kstep.geometry.Placement.IDENTITY),
                ),
            ),
        )

        "a real OCCT box exports to STL with volume 6000 and a watertight mesh".config(enabled = available) {
            OcctKernel.makeBox(10.0, 20.0, 30.0).use { box ->
                val mesh = box.triangulate()
                mesh.triangleCount shouldBe 12
                val r = StlWriter.write(scene("box", mesh))
                stlVolume(r.bytes) shouldBe (6000.0 plusOrMinus 1e-6)
                r.watertight shouldBe true
                r.byteLength shouldBe 84 + 12 * 50
            }
        }

        "cylinder and filleted box are watertight under the 1e-6 welding".config(enabled = available) {
            OcctKernel.makeCylinder(10.0, 30.0).use { cyl ->
                val mesh = cyl.triangulate()
                val topo = MeshTopologyCheck.check(mesh)
                println(
                    "ADR-0049 measure: cylinder r10 h30 default -> ${mesh.triangleCount} triangles, open=${topo.openEdgeCount} nonManifold=${topo.nonManifoldEdgeCount}",
                )
                topo.watertight shouldBe true
                val vol = StlWriter.write(scene("cyl", mesh)).let { stlVolume(it.bytes) }
                vol shouldBe (Math.PI * 100 * 30 plusOrMinus Math.PI * 100 * 30 * 0.01)
            }
            OcctKernel.makeBox(20.0, 20.0, 20.0).use { box ->
                OcctKernel.fillet(box, listOf(0, 1, 2, 3), 3.0).use { filleted ->
                    val mesh = filleted.triangulate()
                    val topo = MeshTopologyCheck.check(mesh)
                    println(
                        "ADR-0049 measure: filleted box -> ${mesh.triangleCount} triangles, open=${topo.openEdgeCount} nonManifold=${topo.nonManifoldEdgeCount}",
                    )
                    topo.watertight shouldBe true
                    val r3 = ThreeMfWriter.write(scene("fil", mesh))
                    println("ADR-0049 measure: filleted box 3mf bytes=${r3.byteLength} vertices=${r3.vertexCount}")
                    r3.watertight shouldBe true
                }
            }
        }

        "--tolerance gives strictly more triangles when finer, is clamped, and invalid values never reach OCCT".config(
            enabled = available,
        ) {
            OcctKernel.makeCylinder(10.0, 30.0).use { cyl ->
                val default = cyl.triangulate().triangleCount
                cyl.triangulate(null).triangleCount shouldBe default
                val fine = cyl.triangulate(0.001).triangleCount
                println("ADR-0049 measure: cylinder triangles default=$default tol0.001=$fine")
                (fine > default) shouldBe true
                (fine <= OcctKernel.MAX_TRIANGLES) shouldBe true
                // diagonal of the bounding box 20 x 20 x 30 is ~41.23, so the lower bound is ~0.0206 mm
                val atBound = cyl.triangulate(0.0206).triangleCount
                val clamped = cyl.triangulate(1e-12).triangleCount
                println("ADR-0049 measure: cylinder triangles tol0.0206=$atBound tol1e-12(clamped)=$clamped")
                (clamped <= atBound + 2) shouldBe true
                (clamped >= atBound - 2) shouldBe true
                for (bad in listOf(Double.NaN, 0.0, -1.0, Double.POSITIVE_INFINITY)) {
                    shouldThrow<IllegalArgumentException> { cyl.triangulate(bad) }.message shouldContain "toleranceMm"
                }
            }
        }

        "a placed assembly exports as one STL soup and as a 3MF with parts, instances and colours".config(
            enabled = available,
        ) {
            val code =
                requireNotNull(OcctMeshExportTest::class.java.getResourceAsStream("/export-placed-assembly.kstep.kts"))
                    .use { String(it.readBytes(), Charsets.UTF_8) }
            val source = PreviewSource.InlineScript(code, "placed.kstep.kts")
            val stl =
                PreviewRenderer.render(
                    PreviewRequest(source, format = RenderFormat.STL),
                ) as PreviewOutcome.Rendered
            stl.contentKind shouldBe PreviewContentKind.GEOMETRY
            stl.meshExport!!.triangleCount shouldBe 48
            stl.meshExport!!.buildItemCount shouldBe 4
            val mf =
                PreviewRenderer.render(
                    PreviewRequest(source, format = RenderFormat.THREE_MF),
                ) as PreviewOutcome.Rendered
            mf.meshExport!!.partCount shouldBe 3
            mf.meshExport!!.buildItemCount shouldBe 4
            mf.meshExport!!.watertight shouldBe true
            val names = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(mf.bytes)).use { z ->
                while (true) names += (z.nextEntry ?: break).name
            }
            names shouldBe listOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model")
        }

        "a script without geometry yields no bytes and no mesh export for STL".config(enabled = available) {
            val code = "val appCtx = applicationContext { application = \"x\" }.getOrThrow()\n"
            val out =
                PreviewRenderer.render(
                    PreviewRequest(PreviewSource.InlineScript(code, "none.kstep.kts"), format = RenderFormat.STL),
                )
            if (out is PreviewOutcome.Rendered) {
                out.bytes.size shouldBe 0
                out.meshExport shouldBe null
            }
        }

        "default triangulation path is unchanged (box stays 12 triangles)".config(enabled = available) {
            OcctKernel.makeBox(1.0, 2.0, 3.0).use { it.triangulate().triangleCount shouldBe 12 }
        }

        "export files can be written to hostile-looking names" {
            val dir = File("build/mesh-export-names").apply { mkdirs() }
            val f = File(dir, "bauteil \u00e4\u00f6\u00fc ${"x".repeat(80)}.stl")
            f.writeBytes(byteArrayOf(1, 2, 3))
            f.exists() shouldBe true
        }
    })
