package dev.kstep.cli

import dev.kstep.shape.importer.StepImportErrorKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The `kstep import` supervisor (ADR-0038) against real child processes started from [FakeImportWorker]: regular end,
 * crash, truncated output, fatal-error banner, OutOfMemoryError, timeout (the worker and its descendants are gone
 * afterwards), output overflow and a worker that cannot start.
 */
class ImportWorkerSupervisorTest :
    StringSpec({
        val fake = FakeImportWorker::class.java.name

        fun fakeCommand(vararg args: String): List<String> =
            ImportWorkerLaunch.command(args.toList(), 60_000, mainClass = fake, maxHeap = "64m")

        fun supervise(
            vararg args: String,
            timeoutMillis: Long = 60_000,
            maxStdout: Int = ImportWorkerSupervisor.MAX_STDOUT_BYTES,
        ): WorkerOutcome = ImportWorkerSupervisor(timeoutMillis, maxStdoutBytes = maxStdout).run(fakeCommand(*args))

        fun failureOf(
            outcome: WorkerOutcome,
            json: Boolean = true,
        ): WorkerVerdict.Failure = judgeWorkerOutcome(json, 60_000, outcome).shouldBeInstanceOf<WorkerVerdict.Failure>()

        fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

        val okJson = """{"status":"success","command":"import","file":"a.step"}""" + "\n"

        "a regular end is passed through byte for byte" {
            val outcome = supervise("print-exit", "0", okJson)
            val verdict = judgeWorkerOutcome(true, 60_000, outcome).shouldBeInstanceOf<WorkerVerdict.PassThrough>()
            verdict.exitCode shouldBe 0
            String(verdict.stdout, Charsets.UTF_8) shouldBe okJson
        }

        "a regular error report with exit 1 is passed through" {
            val error = """{"status":"error","command":"import","errorKind":"not_step","code":"KSTEP-I-007"}""" + "\n"
            judgeWorkerOutcome(true, 60_000, supervise("print-exit", "1", error))
                .shouldBeInstanceOf<WorkerVerdict.PassThrough>()
                .exitCode shouldBe 1
        }

        "a crash after partial output (exit 132, SIGILL) becomes read_failed" {
            val outcome = supervise("halt", "132", """{"status":"succ""")
            val failure = failureOf(outcome)
            failure.kind shouldBe StepImportErrorKind.READ_FAILED
            failure.message shouldContain "signal 4 (SIGILL)"
        }

        "a segfault without output (exit 139) becomes read_failed, also in text mode" {
            val failure = failureOf(supervise("halt", "139", ""), json = false)
            failure.kind shouldBe StepImportErrorKind.READ_FAILED
            failure.message shouldContain "SIGSEGV"
        }

        "exit 1 with a truncated JSON document is not a regular end" {
            failureOf(supervise("print-exit", "1", """{"status":"error","comm""" + "\n")).kind shouldBe
                StepImportErrorKind.READ_FAILED
        }

        "a JVM fatal-error banner on stdout is never passed through" {
            val banner = "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n"
            failureOf(supervise("print-exit", "1", "Error: x\n$banner"), json = false).kind shouldBe
                StepImportErrorKind.READ_FAILED
        }

        "an OutOfMemoryError in the worker becomes limit_exceeded" {
            val failure = failureOf(supervise("oom"))
            failure.kind shouldBe StepImportErrorKind.LIMIT_EXCEEDED
            failure.message shouldContain "out of memory"
        }

        "the timeout kills the worker and its descendants; nothing is left behind" {
            val pidFile = Files.createTempFile("kstep-fake-grandchild", ".pid").toFile()
            try {
                val start = System.nanoTime()
                val outcome = supervise("spawn-and-sleep", pidFile.absolutePath, timeoutMillis = 3_000)
                val elapsed = (System.nanoTime() - start) / 1e9
                val timedOut = outcome.shouldBeInstanceOf<WorkerOutcome.TimedOut>()
                (elapsed < 30.0) shouldBe true
                alive(timedOut.pid) shouldBe false
                val grandchild = pidFile.readText().trim().toLong()
                alive(grandchild) shouldBe false
                val failure = failureOf(outcome)
                failure.kind shouldBe StepImportErrorKind.LIMIT_EXCEEDED
                failure.message shouldContain "within 60000 ms"
            } finally {
                pidFile.delete()
            }
        }

        "a sleeping worker is ended by a short timeout" {
            val outcome = supervise("sleep", timeoutMillis = 300)
            alive(outcome.shouldBeInstanceOf<WorkerOutcome.TimedOut>().pid) shouldBe false
        }

        "a worker flooding stdout is killed at the output bound" {
            val outcome = supervise("flood", maxStdout = 1 shl 20)
            val overflow = outcome.shouldBeInstanceOf<WorkerOutcome.OutputOverflow>()
            alive(overflow.pid) shouldBe false
            failureOf(outcome).kind shouldBe StepImportErrorKind.LIMIT_EXCEEDED
        }

        "a worker that cannot be started becomes io_error" {
            val outcome = ImportWorkerSupervisor(1_000) { throw IOException("no such program") }.run(listOf("x"))
            val failure = failureOf(outcome)
            failure.kind shouldBe StepImportErrorKind.IO_ERROR
            failure.detail shouldBe "no such program"
        }

        "regular-end rules for text and JSON" {
            isRegularEnd(false, 0, "Imported a.step\n".toByteArray()) shouldBe true
            isRegularEnd(false, 1, "Error: not a STEP file\n".toByteArray()) shouldBe true
            isRegularEnd(false, 0, "Error: x\n".toByteArray()) shouldBe false
            isRegularEnd(false, 0, "Imported a.step".toByteArray()) shouldBe false
            isRegularEnd(false, 2, "Imported a.step\n".toByteArray()) shouldBe false
            isRegularEnd(
                true,
                1,
                """{"status":"validation_failed","command":"import"}""".plus("\n").toByteArray(),
            ) shouldBe
                true
            isRegularEnd(true, 0, """{"status":"error","command":"import"}""".plus("\n").toByteArray()) shouldBe false
            isRegularEnd(true, 0, """{"status":"success","command":"render"}""".plus("\n").toByteArray()) shouldBe false
            isRegularEnd(true, 0, "[]\n".toByteArray()) shouldBe false
        }

        "the worker command uses the running JVM, a heap bound and no fatal-error banner" {
            val command = ImportWorkerLaunch.command(listOf("a.step", "--output", "json"), 1234)
            command.first() shouldBe ImportWorkerLaunch.javaExecutable()
            File(command.first()).canExecute() shouldBe true
            command shouldContainAll
                listOf(
                    "-Xmx2g",
                    "-XX:+SuppressFatalErrorMessage",
                    "-XX:+ExitOnOutOfMemoryError",
                    "-Dkstep.import.timeoutMillis=1234",
                    ImportWorkerLaunch.WORKER_MAIN_CLASS,
                )
            command.takeLast(3) shouldBe listOf("a.step", "--output", "json")
            command shouldContain System.getProperty("java.class.path")
        }

        "the worker arguments are rebuilt from the validated command" {
            val command = resolveImportCommand(listOf("--validate", "in.step", "--output", "json", "--render", "o.png"))
            val rebuilt = resolveImportCommand(importWorkerArgs(command as CliCommand.Import))
            rebuilt shouldBe command
        }
    })
