package dev.kstep.tests

import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.script.ScriptExportFormat
import dev.kstep.script.ScriptExportTarget
import dev.kstep.script.stepFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** The `export { }` block of the script DSL (ADR-0063): a pure declaration with strict name validation. */
class KStepExportBlockTest :
    StringSpec({
        fun declared(block: dev.kstep.script.ExportBlockBuilder.() -> Unit) =
            stepFile(fileName = "x.step") { export(block) }

        "one target is declared" {
            declared { step("bracket.step") }.exportTargets shouldBe
                listOf(ScriptExportTarget(ScriptExportFormat.STEP, "bracket.step"))
        }

        "four targets keep their order" {
            val targets =
                declared {
                    step("a.stp")
                    glb("a.glb")
                    stl("a.STL")
                    threeMf("a.3mf")
                }.exportTargets
            targets.map { it.format } shouldBe ScriptExportFormat.entries.toList()
            targets.map { it.fileName } shouldBe listOf("a.stp", "a.glb", "a.STL", "a.3mf")
        }

        "a script without a block equals the reference model and declares nothing" {
            val plain = stepFile(fileName = "x.step") { }
            plain.exportTargets.shouldBeEmpty()
            plain shouldBe stepFile(fileName = "x.step") { }
        }

        val rejected =
            listOf(
                "../a.step",
                "a/b.step",
                "a\\b.step",
                "a\u202Eb.step",
                "a\u0007b.step",
                "CON.step",
                "nul.step",
                "a.glb",
                ".step",
                "." + "hidden.step",
                "a.step.",
                "a.step ",
                "",
                "a".repeat(117) + ".step",
                "\u00E4".repeat(120) + ".step",
                "a\u0308.step",
                "a:b.step",
            )
        for (name in rejected) {
            "the name '${name.take(12).filter { it.code >= 32 }}' (${name.length} chars) is rejected" {
                shouldThrow<IllegalArgumentException> { declared { step(name) } }
            }
        }

        "an overlong name in bytes is rejected" {
            // 100 two-byte characters plus ".step" is 205 bytes: fine; 64 four-byte ones are 261 bytes at only 69 points
            val twoByte = "\u00E4".repeat(100) + ".step"
            declared { step(twoByte) }.exportTargets.size shouldBe 1
            val fourByte = "\uD83D\uDE00".repeat(64) + ".step"
            shouldThrow<IllegalArgumentException> { declared { step(fourByte) } }
        }

        "five targets, a repeated format, a second block and an empty block are rejected" {
            shouldThrow<IllegalArgumentException> {
                declared {
                    step("a.step")
                    glb("a.glb")
                    stl("a.stl")
                    threeMf("a.3mf")
                    step("b.step")
                }
            }
            shouldThrow<IllegalArgumentException> {
                declared {
                    glb("a.glb")
                    glb("b.glb")
                }
            }
            shouldThrow<IllegalArgumentException> {
                stepFile(fileName = "x.step") {
                    export { step("a.step") }
                    export { glb("a.glb") }
                }
            }
            shouldThrow<IllegalArgumentException> { declared { } }
        }

        "through the script host an invalid name is a runtime error, a valid block reaches the model" {
            val good =
                KStepScriptHost.eval(
                    """stepFile(fileName = "x.step") { export { step("part.step"); glb("part.glb") } }""",
                    "good.kstep.kts",
                )
            good
                .shouldBeInstanceOf<KStepScriptOutcome.Success>()
                .model.exportTargets.size shouldBe 2
            val bad =
                KStepScriptHost.eval(
                    """stepFile(fileName = "x.step") { export { step("../evil.step") } }""",
                    "bad.kstep.kts",
                )
            bad.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
        }
    })
