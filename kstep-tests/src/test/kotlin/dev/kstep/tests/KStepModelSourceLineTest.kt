package dev.kstep.tests

import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.script.ScriptSourceLimits
import dev.kstep.script.stepFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

private const val PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    fun pd(id: String) = productDefinition("${'$'}id-D") {
        formation = productDefinitionFormation("${'$'}id-F") { ofProduct = product(id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow() }.getOrThrow()
        frameOfReference = defCtx
    }.getOrThrow()
    val a = pd("A")
    val b = pd("B")
    fun usage(id: String) = nextAssemblyUsageOccurrence(id) { name = id; relatingProductDefinition = a; relatedProductDefinition = b }
    """

/** 1-based line of the first line containing [marker] in [text]; the expected values never are hard-coded. */
private fun lineOf(
    text: String,
    marker: String,
): Int = text.lines().indexOfFirst { marker in it } + 1

private fun modelOf(outcome: KStepScriptOutcome) = outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>().model

class KStepModelSourceLineTest :
    StringSpec({
        "two place calls on different lines report two different, correct lines" {
            val code =
                PRELUDE +
                    """
                    val u1 = usage("U1").getOrThrow()
                    val u2 = usage("U2").getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(u1)
                        place(u1) // MARK-ONE
                        place(u2) // MARK-TWO
                    }
                    """
            val model = modelOf(KStepScriptHost.eval(code, "lines.kstep.kts"))
            model.placementSourceLines shouldBe listOf(lineOf(code, "MARK-ONE"), lineOf(code, "MARK-TWO"))
            model.placementSourceLines shouldHaveSize model.placements.size
        }

        "a multi-line call reports the line where the call starts (measured)" {
            val code =
                PRELUDE +
                    """
                    val u1 = usage("U1").getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(u1)
                        place( // MULTI-START
                            u1,
                            translation = vec(1.0, 2.0, 3.0), // MULTI-ARG
                        )
                    }
                    """
            val line = modelOf(KStepScriptHost.eval(code, "multi.kstep.kts")).placementSourceLines.single()
            // Measured (ADR-0091): the line on which the call starts, not an argument line.
            line shouldBe lineOf(code, "MULTI-START")
        }

        "a call from a script function reports the innermost script line" {
            val code =
                PRELUDE +
                    """
                    val u1 = usage("U1").getOrThrow()
                    stepFile(fileName = "x.step") {
                        fun put(id: String) {
                            place(usage(id).getOrThrow()) // INNER
                        }
                        root(u1)
                        put("U1") // OUTER
                    }
                    """
            modelOf(KStepScriptHost.eval(code, "fn.kstep.kts")).placementSourceLines shouldBe
                listOf(lineOf(code, "INNER"))
        }

        "place with a raw ValidationResult reports the script line, not the builder line" {
            val code =
                PRELUDE +
                    """
                    stepFile(fileName = "x.step") {
                        place(usage("U1")) // RAW
                    }
                    """
            modelOf(KStepScriptHost.eval(code, "raw.kstep.kts")).placementSourceLines shouldBe
                listOf(lineOf(code, "RAW"))
        }

        "stepFile outside the host reports no lines" {
            val model = stepFile(fileName = "x.step") {}
            model.placementSourceLines shouldBe emptyList()
            model.shapeSourceLines shouldBe emptyList()
        }

        "a buffer run reports lines against its display name" {
            val code =
                PRELUDE +
                    """
                    val u1 = usage("U1").getOrThrow()
                    stepFile(fileName = "x.step") {
                        place(u1) // BUFFER
                    }
                    """
            val dir = Files.createTempDirectory("kstep-lines").toFile()
            try {
                val model = modelOf(KStepScriptHost.evalRecording(code, "Unsaved.kstep.kts", dir).outcome)
                model.placementSourceLines shouldBe listOf(lineOf(code, "BUFFER"))
            } finally {
                dir.deleteRecursively()
            }
        }

        "file runs report lines for LF, CRLF and lone CR line ends" {
            val body =
                PRELUDE +
                    """
                    val u1 = usage("U1").getOrThrow()
                    stepFile(fileName = "x.step") {
                        place(u1) // FILE
                    }
                    """
            val expected = lineOf(body, "FILE")
            val dir = Files.createTempDirectory("kstep-lines").toFile()
            try {
                for ((tag, ending) in listOf("lf" to "\n", "crlf" to "\r\n", "cr" to "\r")) {
                    val file = dir.resolve("s-$tag.kstep.kts")
                    file.writeText(body.lines().joinToString(ending))
                    val model = modelOf(KStepScriptHost.evalRecording(file).outcome)
                    model.placementSourceLines shouldBe listOf(expected)
                }
            } finally {
                dir.deleteRecursively()
            }
        }

        "the host source limit is the one the part list validator uses" {
            ScriptSourceLimits.MAX_SOURCE_LINE shouldBe 10_000_000
        }
    })
