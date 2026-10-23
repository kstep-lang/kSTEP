package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.select.FaceKind
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImporter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Freeform (B-spline) faces and curved solids through the import path (ADR-0038).
 *
 * kSTEP has no operation that creates NURBS surfaces (no sweep, loft or spline), so the only true B-spline input is
 * a file written by a foreign tool (`freecad-loft-1.1.4.step`). The kSTEP-authored round trips below cover the
 * curved surfaces kSTEP CAN create (fillets: cylinders, tori, spheres; revolution: cylinders) and do not claim
 * to cover B-splines.
 */
class OcctStepImportFreeformTest :
    StringSpec({
        val workDir = File("build/step-import-freeform").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun bytes(name: String): ByteArray =
            requireNotNull(OcctStepImportFreeformTest::class.java.getResourceAsStream("/import/$name")) { name }
                .use { it.readBytes() }

        "the FreeCAD loft imports with its exact volume, two planes and one B-spline face".config(enabled = available) {
            val result = StepImporter.read(bytes("freecad-loft-1.1.4.step"))
            (result is StepImportResult.Success) shouldBe true
            (result as StepImportResult.Success).model.use { model ->
                val part = model.parts[0]
                // 3987.0608 is FreeCAD's own Shape.Volume of the loft (see the fixture README).
                part.volume!! shouldBe (3987.0608334 plusOrMinus 3987.0608334 * 1e-3)
                part.topology!!.faces shouldBe 3
                part.surfaceTypes shouldBe mapOf("plane" to 2, "bsplineSurface" to 1)
                // FaceKind is deliberately not widened: a B-spline face is OTHER there.
                part.shape!!.faces.count { it.kind == FaceKind.PLANE } shouldBe 2
                part.shape!!.faces.count { it.kind == FaceKind.OTHER } shouldBe 1
                part.shape!!.triangulate().triangleCount shouldBeGreaterThan 0
                part.shape!!.edges.size shouldBeGreaterThan 0
            }
        }

        "kstep import --render of the FreeCAD loft succeeds and writes a GLB".config(enabled = available) {
            File(workDir, "loft.step").writeBytes(bytes("freecad-loft-1.1.4.step"))
            val result = runner.run("import", "loft.step", "--render", "loft.glb", "--output", "json")
            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["parts"]!!
                .jsonArray[0]
                .jsonObject["surfaceTypes"]!!
                .jsonObject["bsplineSurface"]!!
                .jsonPrimitive.content shouldBe "1"
            (File(workDir, "loft.glb").length() > 0) shouldBe true
        }

        "a box with all twelve edges rounded round-trips with volume, face count and a mesh".config(
            enabled = available,
        ) {
            val file = File(workDir, "rounded-box.step")
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                val edges = (0 until box.topology.edges).toList()
                OcctKernel.fillet(box, edges, 2.0).use { rounded ->
                    val volume = rounded.volume
                    val faces = rounded.topology.faces
                    rounded.writeStepFile(file.toPath())
                    val result = StepImporter.read(file.readBytes())
                    (result as StepImportResult.Success).model.use { model ->
                        val part = model.parts[0]
                        part.volume!! shouldBe (volume plusOrMinus volume * 1e-6)
                        part.topology!!.faces shouldBe faces
                        // Fillets of a box give planes, cylinders and spheres; kSTEP cannot make B-splines.
                        part.surfaceTypes!!.keys.containsAll(listOf("plane", "cylinder", "sphere")) shouldBe true
                        part.shape!!.triangulate().triangleCount shouldBeGreaterThan 0
                    }
                }
            }
        }

        "a revolved sleeve round-trips with volume and mesh".config(enabled = available) {
            val file = File(workDir, "sleeve.step")
            val profile =
                listOf(
                    ProfilePoint(5.0, 0.0),
                    ProfilePoint(10.0, 0.0),
                    ProfilePoint(10.0, 20.0),
                    ProfilePoint(5.0, 20.0),
                )
            OcctKernel.revolveProfile(profile, 360.0, RevolveAxis.Y).use { sleeve ->
                val volume = sleeve.volume
                sleeve.writeStepFile(file.toPath())
                val result = StepImporter.read(file.readBytes())
                (result as StepImportResult.Success).model.use { model ->
                    model.parts[0].volume!! shouldBe (volume plusOrMinus volume * 1e-6)
                    model.parts[0].surfaceTypes!!["cylinder"]!! shouldBeGreaterThan 0
                    model.parts[0]
                        .shape!!
                        .triangulate()
                        .triangleCount shouldBeGreaterThan 0
                }
            }
        }
    })
