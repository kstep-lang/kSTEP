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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files

/**
 * The assembly tree of the worker's JSON (ADR-0054): a valid one is attached to the model; a broken one never fails the
 * run, leaves the flat part selection alone and adds one KSTEP-D-013 warning. Runs without OCCT.
 */
class TreeRunnerTest :
    StringSpec({
        val a = TestScenes.triangle(0.0, 0.0, 0.0) + TestScenes.triangle(2.0, 0.0, 0.0)
        val b = TestScenes.triangle(40.0, 0.0, 0.0)
        val mesh = TestScenes.mesh(a, b)

        fun node(
            name: String,
            parent: Int,
            part: Int,
        ) = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("parent", JsonPrimitive(parent))
            put("part", JsonPrimitive(part))
        }

        fun withTree(vararg nodes: JsonObject): JsonObject {
            val base = TestScenes.honestJson(a, b)
            return JsonObject(
                base + ("tree" to buildJsonObject { put("nodes", JsonArray(nodes.toList())) }),
            )
        }

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

        "an honest tree is attached to the model without a warning" {
            val model = readGlb(withTree(node("Top", -1, -1), node("P0", 0, 0), node("P1", 0, 1))).model
            model.tree!!.size shouldBe 3
            model.parts!!.parts.size shouldBe 2
            model.warnings.shouldBeEmpty()
        }

        "a result without a tree has neither a tree nor a warning" {
            val model = readGlb(TestScenes.honestJson(a, b)).model
            model.tree shouldBe null
            model.warnings.shouldBeEmpty()
        }

        "a broken tree keeps the part list, adds one D-013 warning and no tree" {
            val model = readGlb(withTree(node("Top", -1, -1), node("P0", 0, 0), node("Wrong", 0, 1))).model
            model.tree shouldBe null
            (model.parts != null) shouldBe true
            val warning = model.warnings.single()
            warning.code shouldBe DesktopErrorCodes.TREE_INVALID
            warning.severity shouldBe Severity.WARNING
            warning.message shouldContain "click selection still works"
        }

        "an omitted tree is reported as D-013" {
            val omitted =
                JsonObject(
                    TestScenes.honestJson(a, b) +
                        (
                            "treeOmitted" to
                                buildJsonObject {
                                    put("reason", JsonPrimitive("too_deep"))
                                    put("count", JsonPrimitive(70))
                                }
                        ),
                )
            val model = readGlb(omitted).model
            model.tree shouldBe null
            model.warnings.single().code shouldBe DesktopErrorCodes.TREE_INVALID
        }

        "with an invalid part list only D-012 is shown and the tree is ignored" {
            val tampered =
                JsonObject(
                    TestScenes.result(TestScenes.part(JsonPrimitive("P0"), JsonPrimitive(0), JsonPrimitive(2))) +
                        ("tree" to buildJsonObject { put("nodes", JsonArray(listOf(node("Top", -1, -1)))) }),
                )
            val model = readGlb(tampered).model
            model.tree shouldBe null
            model.warnings.map { it.code } shouldBe listOf(DesktopErrorCodes.PARTS_INVALID)
        }
    })
