package dev.kstep.desktop

import dev.kstep.desktop.pick.TestScenes
import dev.kstep.desktop.run.DependencyListParser
import dev.kstep.desktop.run.DependencyReport
import dev.kstep.desktop.run.ErrorParser
import dev.kstep.desktop.run.PartListParser
import dev.kstep.desktop.run.PartListResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** The worker's `dependencies` are hostile input (ADR-0080): fail closed, the same rules as the script helper. */
class DependencyListParserTest :
    StringSpec({
        fun strings(vararg s: String): JsonElement = JsonArray(s.map { JsonPrimitive(it) })

        fun withScript(block: (Path, Path) -> Unit) {
            val dir = TestSupport.newDir()
            try {
                block(dir, TestSupport.script(dir, "a", "x"))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "no field (an older worker) is Absent" {
            withScript { _, script -> DependencyListParser.parse(null, script) shouldBe DependencyReport.Absent }
        }

        "an honest list is resolved below the real script folder" {
            withScript { dir, script ->
                val report = DependencyListParser.parse(strings("data.txt", "sub/b.txt"), script)
                report.shouldBeInstanceOf<DependencyReport.Valid>().paths shouldBe
                    listOf(dir.resolve("data.txt"), dir.resolve("sub/b.txt"))
                DependencyListParser.parse(JsonArray(emptyList()), script) shouldBe
                    DependencyReport.Valid(emptyList())
            }
        }

        "the script itself is removed from the set" {
            withScript { dir, script ->
                val report = DependencyListParser.parse(strings(script.fileName.toString(), "x.txt"), script)
                report.shouldBeInstanceOf<DependencyReport.Valid>().paths shouldBe listOf(dir.resolve("x.txt"))
            }
        }

        "every kind of hostile list is Invalid and nothing throws" {
            withScript { dir, script ->
                Files.createDirectories(dir.resolve("real"))
                val hostile =
                    listOf<JsonElement?>(
                        JsonPrimitive("a.txt"), // not an array
                        JsonNull,
                        JsonObject(emptyMap()),
                        JsonArray((0..64).map { JsonPrimitive("f$it.txt") }), // 65 entries
                        JsonArray(listOf(JsonPrimitive(1))), // not a string
                        JsonArray(listOf(JsonNull)),
                        strings("a.txt", "a.txt"), // duplicate
                        strings("/etc/passwd"),
                        strings("../x"),
                        strings("a/../../x"),
                        strings("a\\b"),
                        strings("a\u0000b"),
                        strings("x".repeat(1025)),
                        strings(""),
                        strings("C:x"),
                        JsonArray(listOf(JsonObject(emptyMap()))),
                    )
                for (h in hostile) {
                    DependencyListParser.parse(h, script) shouldBe DependencyReport.Invalid
                }
                val linked =
                    try {
                        Files.createSymbolicLink(dir.resolve("link.txt"), dir.resolve("real"))
                        true
                    } catch (_: Exception) {
                        false
                    }
                if (linked) DependencyListParser.parse(strings("link.txt"), script) shouldBe DependencyReport.Invalid
            }
        }

        "a script path without a folder never throws" {
            DependencyListParser.parse(strings("a.txt"), Path.of("nowhere-at-all", "x.kstep.kts")) shouldBe
                DependencyReport.Invalid
        }

        "the new field changes nothing for the other parsers (forward compatibility)" {
            val a = TestScenes.triangle(0.0, 0.0, 0.0)
            val mesh = TestScenes.mesh(a)
            val plain = TestScenes.honestJson(a)
            val withDeps = JsonObject(plain + ("dependencies" to strings("data.txt")))
            val one = PartListParser.parse(plain, mesh).shouldBeInstanceOf<PartListResult.Valid>()
            val two = PartListParser.parse(withDeps, mesh).shouldBeInstanceOf<PartListResult.Valid>()
            two.table.parts.map { it.name } shouldBe one.table.parts.map { it.name }

            val success = """{"status":"success","content":"summary""""
            ErrorParser.parseRenderJson("$success}") shouldBe
                ErrorParser.parseRenderJson("$success,\"dependencies\":[\"data.txt\"]}")
            val failure = """{"status":"error","errorKind":"runtime_error","code":"KSTEP-S-017","message":"m""""
            ErrorParser.parseRenderJson("$failure}") shouldBe
                ErrorParser.parseRenderJson("$failure,\"dependencies\":[\"data.txt\"]}")
        }
    })
