package dev.kstep.tests

import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.script.KStepScriptOutcomeCodes
import dev.kstep.script.ScriptInputErrorKind
import dev.kstep.script.input.InputPathRules
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

// A minimal valid model; the body of each test script is placed before it.
private const val MODEL_TAIL =
    """
val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
val bracket = product("BRK-001") { name = "Bracket"; frameOfReference = setOf(prodCtx) }.getOrThrow()
stepFile(fileName = "t.step") { root(bracket) }
"""

private fun scriptIn(
    dir: Path,
    body: String,
    name: String = "t.kstep.kts",
): File {
    val file = dir.resolve(name).toFile()
    file.writeText(body + "\n" + MODEL_TAIL)
    return file
}

private fun tempDir(): Path = Files.createTempDirectory("kstep-inputs").toRealPath()

private fun runtimeError(outcome: KStepScriptOutcome): KStepScriptOutcome.RuntimeError =
    outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()

/** Input-file helpers (ADR-0080): recording through the real [KStepScriptHost], and the pure path rules. */
class ScriptInputsTest :
    StringSpec({
        "records paths through KStepScriptHost.eval(file), sorted and without duplicates" {
            val dir = tempDir()
            Files.writeString(dir.resolve("b.txt"), "hello")
            Files.createDirectories(dir.resolve("sub"))
            Files.writeString(dir.resolve("sub/a.txt"), "x")
            val file =
                scriptIn(
                    dir,
                    """
                    val text = readInput("b.txt")
                    check(text == "hello")
                    readInput("./sub//a.txt")
                    readInput("b.txt")
                    val p = inputFile("sub/a.txt")
                    check(p.isAbsolute)
                    """.trimIndent(),
                )
            val evaluation = KStepScriptHost.evalRecording(file)
            evaluation.outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            evaluation.dependencies shouldBe listOf("b.txt", "sub/a.txt")
        }

        "refused paths fail with KSTEP-S-016" {
            val dir = tempDir()
            val bad =
                listOf(
                    "../x",
                    "a/../b",
                    "/etc/passwd",
                    "C:x",
                    "a\\\\b",
                    "",
                    "a\\u0000b",
                    "a\\tb",
                    "x".repeat(1025),
                )
            for (path in bad) {
                val outcome = KStepScriptHost.evalRecording(scriptIn(dir, "inputFile(\"$path\")")).outcome
                val error = runtimeError(outcome)
                error.code shouldBe KStepScriptOutcomeCodes.INPUT_FILE_REFUSED
                error.message shouldContain "KSTEP-S-016"
            }
        }

        "the path rules map to the right kinds, also for a Windows drive path" {
            val base = tempDir()

            fun kind(path: String) = (InputPathRules.resolve(base, path) as? InputPathRules.Resolution.Refused)?.kind
            kind("..") shouldBe ScriptInputErrorKind.ESCAPE
            kind("a/../b") shouldBe ScriptInputErrorKind.ESCAPE
            kind("/x") shouldBe ScriptInputErrorKind.ABSOLUTE
            kind("C:x") shouldBe ScriptInputErrorKind.ABSOLUTE
            kind("a\\b") shouldBe ScriptInputErrorKind.INVALID
            kind("") shouldBe ScriptInputErrorKind.INVALID
            kind(".") shouldBe ScriptInputErrorKind.INVALID
            kind("a\u0000b") shouldBe ScriptInputErrorKind.INVALID
            kind("x".repeat(1025)) shouldBe ScriptInputErrorKind.INVALID
            kind("fine/name.txt") shouldBe null
        }

        "a symbolic link below the script folder is refused as LINK" {
            val dir = tempDir()
            Files.writeString(dir.resolve("real.txt"), "x")
            Files.createDirectories(dir.resolve("realdir"))
            val linked =
                try {
                    Files.createSymbolicLink(dir.resolve("link.txt"), dir.resolve("real.txt"))
                    Files.createSymbolicLink(dir.resolve("linkdir"), dir.resolve("realdir"))
                    true
                } catch (_: Exception) {
                    false
                }
            if (linked) {
                (InputPathRules.resolve(dir, "link.txt") as InputPathRules.Resolution.Refused).kind shouldBe
                    ScriptInputErrorKind.LINK
                (InputPathRules.resolve(dir, "linkdir/x.txt") as InputPathRules.Resolution.Refused).kind shouldBe
                    ScriptInputErrorKind.LINK
                val error =
                    runtimeError(KStepScriptHost.evalRecording(scriptIn(dir, "readInput(\"link.txt\")")).outcome)
                error.code shouldBe KStepScriptOutcomeCodes.INPUT_FILE_REFUSED
            }
        }

        "a folder as input is refused as NOT_A_FILE" {
            val dir = tempDir()
            Files.createDirectories(dir.resolve("folder"))
            (InputPathRules.resolve(dir, "folder") as InputPathRules.Resolution.Refused).kind shouldBe
                ScriptInputErrorKind.NOT_A_FILE
        }

        "64 distinct files are fine, the 65th fails with LIMIT, repeated reads count once" {
            val dir = tempDir()
            val ok =
                scriptIn(
                    dir,
                    "repeat(64) { inputFile(\"f\$it.txt\") }\nrepeat(100) { inputFile(\"f0.txt\") }",
                    "ok.kstep.kts",
                )
            val evaluation = KStepScriptHost.evalRecording(ok)
            evaluation.outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            evaluation.dependencies.size shouldBe 64

            val over = scriptIn(dir, "repeat(65) { inputFile(\"f\$it.txt\") }", "over.kstep.kts")
            val error = runtimeError(KStepScriptHost.evalRecording(over).outcome)
            error.code shouldBe KStepScriptOutcomeCodes.INPUT_FILE_REFUSED
            error.message shouldContain "at most 64"
        }

        "a missing file: inputFile is fine and recorded, readInput fails with S-017 but the path is still reported" {
            val dir = tempDir()
            val fine = KStepScriptHost.evalRecording(scriptIn(dir, "inputFile(\"later.txt\")", "a.kstep.kts"))
            fine.outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            fine.dependencies shouldBe listOf("later.txt")

            val failing = KStepScriptHost.evalRecording(scriptIn(dir, "readInput(\"later.txt\")", "b.kstep.kts"))
            val error = runtimeError(failing.outcome)
            error.code shouldBe KStepScriptOutcomeCodes.INPUT_FILE_UNREADABLE
            error.message shouldContain "KSTEP-S-017"
            failing.dependencies shouldBe listOf("later.txt")
        }

        "a file above maxBytes fails with S-017" {
            val dir = tempDir()
            Files.writeString(dir.resolve("big.txt"), "0123456789")
            val error = runtimeError(KStepScriptHost.evalRecording(scriptIn(dir, "readInput(\"big.txt\", 5)")).outcome)
            error.code shouldBe KStepScriptOutcomeCodes.INPUT_FILE_UNREADABLE
        }

        "inline source has no script folder, and nothing leaks after a file evaluation" {
            val dir = tempDir()
            Files.writeString(dir.resolve("x.txt"), "x")
            KStepScriptHost
                .evalRecording(scriptIn(dir, "readInput(\"x.txt\")"))
                .outcome
                .shouldBeInstanceOf<KStepScriptOutcome.Success>()
            val inline = KStepScriptHost.eval("inputFile(\"x.txt\")\n$MODEL_TAIL", "inline.kstep.kts")
            val error = runtimeError(inline)
            error.code shouldBe KStepScriptOutcomeCodes.INPUT_FILE_REFUSED
            error.message shouldContain "script file"
        }

        "a call from another thread has no recorder" {
            val dir = tempDir()
            val body =
                """
                var failure: String? = null
                val t = Thread { try { inputFile("x.txt") } catch (e: ScriptInputException) { failure = e.kind.name } }
                t.start(); t.join()
                check(failure == "NO_SCRIPT_FOLDER") { "got ${'$'}failure" }
                """.trimIndent()
            val evaluation = KStepScriptHost.evalRecording(scriptIn(dir, body))
            evaluation.outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            evaluation.dependencies shouldBe emptyList()
        }

        "a script folder reached through a link works (the base is resolved to its real path)" {
            val real = tempDir()
            val viaLink = Files.createTempDirectory("kstep-inputs-link").resolve("alias")
            val linked =
                try {
                    Files.createSymbolicLink(viaLink, real)
                    true
                } catch (_: Exception) {
                    false
                }
            if (linked) {
                Files.writeString(real.resolve("d.txt"), "d")
                val file = scriptIn(viaLink, "readInput(\"d.txt\")")
                val evaluation = KStepScriptHost.evalRecording(file)
                evaluation.outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
                evaluation.dependencies shouldBe listOf("d.txt")
            }
        }

        "an unsaved buffer reads the input files of its script folder (ADR-0086)" {
            val dir = tempDir()
            Files.writeString(dir.resolve("in.txt"), "hello")
            val code = "check(readInput(\"in.txt\") == \"hello\")\n" + MODEL_TAIL
            val evaluation = KStepScriptHost.evalRecording(code, "t.kstep.kts", dir.toFile())
            evaluation.outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            evaluation.dependencies shouldBe listOf("in.txt")
        }

        "an unsaved buffer keeps the path rules: a parent segment is refused" {
            val dir = tempDir()
            val code = "inputFile(\"../x\")\n" + MODEL_TAIL
            val error = runtimeError(KStepScriptHost.evalRecording(code, "t.kstep.kts", dir.toFile()).outcome)
            error.code shouldBe KStepScriptOutcomeCodes.INPUT_FILE_REFUSED
        }

        "an unsaved buffer finds a file only in its own folder, not in another" {
            val dir = tempDir()
            val other = tempDir()
            Files.writeString(other.resolve("only-here.txt"), "x")
            val code = "readInput(\"only-here.txt\")\n" + MODEL_TAIL
            val outcome = KStepScriptHost.evalRecording(code, "t.kstep.kts", dir.toFile()).outcome
            runtimeError(outcome).code shouldBe KStepScriptOutcomeCodes.INPUT_FILE_UNREADABLE
        }

        "a compile error of an unsaved buffer reports its position and no dependencies" {
            val dir = tempDir()
            val evaluation = KStepScriptHost.evalRecording("val x: Int = \"a\"\n", "named.kstep.kts", dir.toFile())
            val outcome = evaluation.outcome.shouldBeInstanceOf<KStepScriptOutcome.CompilationError>()
            evaluation.dependencies shouldBe emptyList()
            outcome.diagnostics.any { it.line == 1 } shouldBe true
        }
    })
