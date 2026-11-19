package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ScriptExportTarget
import dev.kstep.desktop.pick.TestScenes
import dev.kstep.render.gltf.GlbWriter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

/** The declared export names come from a script's output: everything unusual drops the whole list (ADR-0063). */
class ExportTargetsParserTest :
    StringSpec({
        fun entry(
            format: String,
            name: String,
        ) = buildJsonObject {
            put("format", format)
            put("fileName", name)
        }

        fun list(vararg e: JsonElement): JsonArray = buildJsonArray { e.forEach { add(it) } }

        "no field is absent, an empty list too" {
            ExportTargetsParser.parse(null) shouldBe ExportTargetsResult.Absent
            ExportTargetsParser.parse(JsonArray(emptyList())) shouldBe ExportTargetsResult.Absent
        }

        "honest entries are read in order" {
            val parsed = ExportTargetsParser.parse(list(entry("step", "a.stp"), entry("3mf", "a.3mf")))
            parsed shouldBe
                ExportTargetsResult.Valid(
                    listOf(
                        ScriptExportTarget(ExportFormat.STEP, "a.stp"),
                        ScriptExportTarget(ExportFormat.THREE_MF, "a.3mf"),
                    ),
                )
        }

        val hostile =
            mapOf(
                "traversal" to list(entry("step", "../a.step")),
                "separator" to list(entry("step", "a/b.step")),
                "backslash" to list(entry("step", "a\\b.step")),
                "bidi" to list(entry("step", "a\u202Eb.step")),
                "control" to list(entry("step", "a\u0000b.step")),
                "reserved" to list(entry("step", "CON.step")),
                "wrong extension" to list(entry("glb", "a.step")),
                "unknown format" to list(entry("obj", "a.obj")),
                "not nfc" to list(entry("step", "a\u0308.step")),
                "too long" to list(entry("step", "a".repeat(130) + ".step")),
                "five entries" to
                    list(
                        entry("step", "a.step"),
                        entry("glb", "a.glb"),
                        entry("stl", "a.stl"),
                        entry("3mf", "a.3mf"),
                        entry("step", "b.step"),
                    ),
                "repeated format" to list(entry("glb", "a.glb"), entry("glb", "b.glb")),
                "one bad entry among good ones" to list(entry("step", "a.step"), entry("glb", "../x.glb")),
                "not an array" to JsonPrimitive("step"),
                "entry is not an object" to list(JsonPrimitive("a.step")),
                "name is not a string" to
                    list(
                        buildJsonObject {
                            put("format", "step")
                            put("fileName", 5)
                        },
                    ),
                "name is null" to
                    list(
                        buildJsonObject {
                            put("format", "step")
                            put("fileName", JsonNull)
                        },
                    ),
                "missing name" to list(buildJsonObject { put("format", "step") }),
            )
        for ((label, json) in hostile) {
            "$label drops the whole list" {
                ExportTargetsParser.parse(json) shouldBe ExportTargetsResult.Invalid
            }
        }

        "an invalid list through the runner keeps the model and adds the notice" {
            val dir = TestSupport.newDir()
            try {
                val mesh = TestScenes.mesh(TestScenes.triangle(0.0, 0.0, 0.0))
                val glb = dir.resolve("m.glb").also { Files.write(it, GlbWriter.write(mesh).bytes) }
                val json =
                    buildJsonObject {
                        put("exportTargets", list(entry("step", "../evil.step")))
                    }
                val model =
                    (WorkerRunner(dir).readGlb(glb, 1, 0, emptyList(), json) as RunResult.Success).model
                model.scriptExportTargets shouldBe emptyList()
                model.notes shouldBe listOf(ExportTargetsParser.IGNORED_NOTICE)
                val ok = buildJsonObject { put("exportTargets", list(entry("step", "good.step"))) }
                (WorkerRunner(dir).readGlb(glb, 1, 0, emptyList(), ok) as RunResult.Success)
                    .model.scriptExportTargets shouldBe listOf(ScriptExportTarget(ExportFormat.STEP, "good.step"))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
