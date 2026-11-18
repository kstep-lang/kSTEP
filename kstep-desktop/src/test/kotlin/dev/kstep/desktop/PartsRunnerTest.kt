package dev.kstep.desktop

import dev.kstep.desktop.pick.TestScenes
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.Severity
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.render.gltf.GlbWriter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

/**
 * A hostile or broken part list never fails the run (ADR-0053): the model is shown, selection is off, and one
 * KSTEP-D-012 warning says why. Runs without OCCT: the GLB is written here, the worker's JSON is simulated.
 */
class PartsRunnerTest :
    StringSpec({
        val a = TestScenes.triangle(0.0, 0.0, 0.0) + TestScenes.triangle(2.0, 0.0, 0.0)
        val b = TestScenes.triangle(40.0, 0.0, 0.0)
        val mesh = TestScenes.mesh(a, b)

        fun readGlb(partsJson: JsonObject?): RunResult.Success {
            val dir = TestSupport.newDir()
            try {
                val glb = dir.resolve("m.glb").also { Files.write(it, GlbWriter.write(mesh).bytes) }
                return WorkerRunner(dir)
                    .readGlb(glb, 1, 0, emptyList(), partsJson)
                    .shouldBeInstanceOf<RunResult.Success>()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "an honest list is attached to the model without a warning" {
            val model = readGlb(TestScenes.honestJson(a, b)).model
            model.parts!!.parts.map { it.name } shouldBe listOf("P0", "P1")
            model.warnings.shouldBeEmpty()
        }

        "a list that does not cover the mesh keeps the model, switches selection off and warns once" {
            val tampered = TestScenes.result(TestScenes.part(JsonPrimitive("P0"), JsonPrimitive(0), JsonPrimitive(2)))
            val model = readGlb(tampered).model
            (model.mesh != null) shouldBe true
            model.triangles shouldBe 3
            model.parts shouldBe null
            model.warnings.size shouldBe 1
            val warning = model.warnings.single()
            warning.code shouldBe DesktopErrorCodes.PARTS_INVALID
            warning.severity shouldBe Severity.WARNING
            warning.message shouldContain "selection is off"
        }

        "a result without a part list has neither parts nor a warning" {
            val model = readGlb(JsonObject(emptyMap())).model
            model.parts shouldBe null
            model.warnings.shouldBeEmpty()
            readGlb(null).model.warnings.shouldBeEmpty()
        }

        "a hostile name is sanitised and a hostile colour turns the whole list off" {
            val hostileName =
                TestScenes.result(
                    TestScenes.part(
                        JsonPrimitive("\u001B[2Jevil"),
                        JsonPrimitive(0),
                        JsonPrimitive(2),
                        color = JsonPrimitive("#0A0000"),
                    ),
                    TestScenes.part(
                        JsonPrimitive("B"),
                        JsonPrimitive(2),
                        JsonPrimitive(1),
                        color = JsonPrimitive("#140000"),
                    ),
                )
            readGlb(hostileName)
                .model.parts!!
                .parts[0]
                .name shouldBe "evil"
            val lyingColour =
                TestScenes.result(
                    TestScenes.part(
                        JsonPrimitive("A"),
                        JsonPrimitive(0),
                        JsonPrimitive(2),
                        color = JsonPrimitive("#00FF00"),
                    ),
                    TestScenes.part(
                        JsonPrimitive("B"),
                        JsonPrimitive(2),
                        JsonPrimitive(1),
                        color = JsonPrimitive("#140000"),
                    ),
                )
            val model = readGlb(lyingColour).model
            model.parts shouldBe null
            model.warnings.single().code shouldBe DesktopErrorCodes.PARTS_INVALID
        }
    })
