package dev.kstep.cli

import dev.kstep.shape.importer.StepImportErrorKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The `kstep import` supervisor (ADR-0038) against real child processes started from [FakeImportWorker]: regular end,
 * crash, truncated output, fatal-error banner, OutOfMemoryError, timeout (the worker and its descendants are gone
 * afterwards), output overflow on both streams, incomplete output, the private temp directory, the shutdown hook, the
 * orphan bound and a worker that cannot start. Every stand-in ends itself after two minutes at the latest.
 */
class ImportWorkerSupervisorTest :
    StringSpec({
        val fake = FakeImportWorker::class.java.name
        val windows =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("windows")

        fun newBase(): Path = Files.createTempDirectory("r2-base").toRealPath()

        fun fakeCommand(
            args: Array<out String>,
            tmpDir: Path? = null,
        ): List<String> =
            ImportWorkerLaunch.command(args.toList(), 60_000, mainClass = fake, maxHeap = "64m", tmpDir = tmpDir)

        fun supervise(
            vararg args: String,
            timeoutMillis: Long = 60_000,
            maxStdout: Int = ImportWorkerSupervisor.MAX_STDOUT_BYTES,
            maxStderr: Int = ImportWorkerSupervisor.MAX_STDERR_BYTES,
            streamJoinMillis: Long = ImportWorkerSupervisor.STREAM_JOIN_MILLIS,
            tempBase: Path = newBase(),
        ): WorkerOutcome =
            ImportWorkerSupervisor(
                timeoutMillis,
                maxStdoutBytes = maxStdout,
                maxStderrBytes = maxStderr,
                streamJoinMillis = streamJoinMillis,
                tempBase = tempBase,
            ).runWith { dir -> fakeCommand(args, dir) }

        fun failureOf(
            outcome: WorkerOutcome,
            json: Boolean = true,
        ): WorkerVerdict.Failure = judgeWorkerOutcome(json, 60_000, outcome).shouldBeInstanceOf<WorkerVerdict.Failure>()

        /** Polls until the process is gone (a killed process needs a moment to be reaped). */
        fun awaitDead(
            pid: Long,
            maxMillis: Long = 5_000,
        ): Boolean {
            val deadline = System.nanoTime() + maxMillis * 1_000_000
            while (true) {
                if (!ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return true
                if (System.nanoTime() > deadline) return false
                Thread.sleep(50)
            }
        }

        fun readPid(file: File): Long? =
            file
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.toLongOrNull()

        fun killByPidFile(file: File) {
            readPid(file)?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
            file.delete()
        }

        fun awaitFile(
            file: File,
            maxMillis: Long,
        ): Boolean {
            val deadline = System.nanoTime() + maxMillis * 1_000_000
            while (readPid(file) == null) {
                if (System.nanoTime() > deadline) return false
                Thread.sleep(50)
            }
            return true
        }

        fun kstepEntries(base: Path): List<String> =
            base
                .listDirectoryEntries()
                .map {
                    it.name
                }.filter { it.startsWith("kstep-") }

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

        "the diagnostic line of a crash is the exception, not the log line before it" {
            val stderr =
                "[main] INFO dev.kstep.geometry.OcctNativeLibrary - OCCT JNI bridge loaded from /tmp/x\n" +
                    "Exception in thread \"main\" java.lang.IllegalStateException: boom\n"
            val failure = failureOf(supervise("halt-stderr", "134", stderr))
            failure.kind shouldBe StepImportErrorKind.READ_FAILED
            val detail = checkNotNull(failure.detail)
            detail shouldContain "Exception"
            detail shouldNotContain "INFO"
        }

        "diagnosticLine picks the cause and drops log noise" {
            diagnosticLine("[main] INFO a.B - loaded\njava.lang.IllegalStateException: x\n") shouldBe
                "java.lang.IllegalStateException: x"
            diagnosticLine("[main] INFO a.B - loaded\n[main] DEBUG a.B - more\n  \n").shouldBeNull()
            diagnosticLine("[main] WARN a.B - careful\nCaused by: java.io.IOException: gone\n") shouldBe
                "Caused by: java.io.IOException: gone"
            diagnosticLine("[main] WARN a.B - careful\n") shouldBe "[main] WARN a.B - careful"
            diagnosticLine("bad\u0007 line\u001b[0m\n") shouldBe "bad line[0m"
            diagnosticLine("").shouldBeNull()
        }

        "the timeout kills the worker and its descendants; nothing is left behind" {
            val pidFile = Files.createTempFile("kstep-fake-grandchild", ".pid").toFile()
            pidFile.delete()
            var workerPid: Long? = null
            try {
                val start = System.nanoTime()
                val outcome = supervise("spawn-and-sleep", pidFile.absolutePath, timeoutMillis = 20_000)
                val elapsed = (System.nanoTime() - start) / 1e9
                val timedOut = outcome.shouldBeInstanceOf<WorkerOutcome.TimedOut>()
                workerPid = timedOut.pid
                (elapsed < 60.0) shouldBe true
                val grandchild = readPid(pidFile)
                checkNotNull(grandchild) { "grandchild pid not written within the timeout" }
                awaitDead(timedOut.pid) shouldBe true
                awaitDead(grandchild) shouldBe true
                val failure = failureOf(outcome)
                failure.kind shouldBe StepImportErrorKind.LIMIT_EXCEEDED
                failure.message shouldContain "within 60000 ms"
            } finally {
                workerPid?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
                killByPidFile(pidFile)
            }
        }

        "a sleeping worker is ended by a short timeout" {
            val outcome = supervise("sleep", timeoutMillis = 300)
            awaitDead(outcome.shouldBeInstanceOf<WorkerOutcome.TimedOut>().pid) shouldBe true
        }

        "a worker flooding stdout is killed at the output bound" {
            val outcome = supervise("flood", maxStdout = 1 shl 20)
            val overflow = outcome.shouldBeInstanceOf<WorkerOutcome.OutputOverflow>()
            awaitDead(overflow.pid) shouldBe true
            failureOf(outcome).kind shouldBe StepImportErrorKind.LIMIT_EXCEEDED
        }

        "a worker flooding stderr is killed at the stderr bound" {
            val outcome = supervise("flood-stderr", maxStderr = 1 shl 20)
            val overflow = outcome.shouldBeInstanceOf<WorkerOutcome.OutputOverflow>()
            overflow.limitBytes shouldBe (1 shl 20)
            awaitDead(overflow.pid) shouldBe true
            failureOf(outcome).kind shouldBe StepImportErrorKind.LIMIT_EXCEEDED
        }

        "the stdout bound stays at or below 16 MiB" {
            (ImportWorkerSupervisor.MAX_STDOUT_BYTES <= 16 * 1024 * 1024) shouldBe true
        }

        "a descendant holding the pipe after the worker ended gives incomplete output, read_failed" {
            val pidFile = Files.createTempFile("kstep-fake-leak", ".pid").toFile()
            pidFile.delete()
            try {
                val outcome = supervise("leak-pipe-and-exit", pidFile.absolutePath, streamJoinMillis = 2_000)
                val incomplete = outcome.shouldBeInstanceOf<WorkerOutcome.IncompleteOutput>()
                incomplete.exitCode shouldBe 0
                awaitDead(incomplete.pid) shouldBe true
                val failure = failureOf(outcome)
                failure.kind shouldBe StepImportErrorKind.READ_FAILED
                failure.message shouldContain "incomplete output"
            } finally {
                // The worker is already gone when the supervisor looks for descendants, so the pipe holder is
                // not found by the kill (ADR-0038, remaining limits): end it here.
                killByPidFile(pidFile)
            }
        }

        "a worker that cannot be started becomes io_error" {
            val outcome = ImportWorkerSupervisor(1_000, tempBase = newBase()) { throw IOException("no such program") }
            val failure = failureOf(outcome.run(listOf("x")))
            failure.kind shouldBe StepImportErrorKind.IO_ERROR
            failure.detail shouldBe "no such program"
        }

        "a worker temp directory that cannot be created becomes io_error" {
            val missing = newBase().resolve("does-not-exist")
            val outcome = ImportWorkerSupervisor(1_000, tempBase = missing).run(listOf("x"))
            failureOf(outcome).kind shouldBe StepImportErrorKind.IO_ERROR
        }

        "the worker gets its own temp directory, removed after a crash, a regular end and a timeout" {
            val cases =
                listOf(
                    Triple("crash", listOf("halt", "139"), 60_000L),
                    Triple("regular end", listOf("halt", "0"), 60_000L),
                    Triple("timeout", listOf("sleep"), 20_000L),
                )
            for ((label, mode, timeout) in cases) {
                val base = newBase()
                val pathFile = File(base.toFile(), "tmpdir.txt")
                val outcome =
                    supervise(
                        "extract-then",
                        pathFile.absolutePath,
                        "-",
                        *mode.toTypedArray(),
                        timeoutMillis = timeout,
                        tempBase = base,
                    )
                if (label == "timeout") {
                    outcome.shouldBeInstanceOf<WorkerOutcome.TimedOut>()
                } else {
                    outcome.shouldBeInstanceOf<WorkerOutcome.Exited>()
                }
                val workerTmp = Path.of(pathFile.readText().trim())
                workerTmp.parent.toRealPath() shouldBe base
                workerTmp.exists() shouldBe false
                kstepEntries(base) shouldBe emptyList()
            }
        }

        "removing the temp directory never follows a symbolic link out of it".config(enabled = !windows) {
            val base = newBase()
            val outside = Files.createTempFile("r2-outside", ".txt")
            Files.writeString(outside, "keep me")
            val pathFile = File(base.toFile(), "tmpdir.txt")
            val outcome =
                supervise("extract-then", pathFile.absolutePath, outside.toString(), "halt", "139", tempBase = base)
            outcome.shouldBeInstanceOf<WorkerOutcome.Exited>()
            outside.exists() shouldBe true
            Files.readString(outside) shouldBe "keep me"
            kstepEntries(base) shouldBe emptyList()
            Files.delete(outside)
        }

        "the supervisor's shutdown hook kills the worker and removes the temp directory".config(enabled = !windows) {
            val base = newBase()
            val pidFile = File(base.toFile(), "worker.pid")
            val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            val harness =
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    SupervisorHookHarness::class.java.name,
                    base.toString(),
                    pidFile.absolutePath,
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            var workerPid: Long? = null
            try {
                awaitFile(pidFile, 30_000) shouldBe true
                workerPid = readPid(pidFile)
                kstepEntries(base).size shouldBe 1
                harness.destroy()
                harness.waitFor(30, TimeUnit.SECONDS) shouldBe true
                awaitDead(checkNotNull(workerPid), 30_000) shouldBe true
                kstepEntries(base) shouldBe emptyList()
            } finally {
                harness.destroyForcibly()
                workerPid?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
            }
        }

        "a worker without a supervisor halts itself with exit 124 after timeout plus grace".config(enabled = !windows) {
            val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            val process =
                ProcessBuilder(
                    java,
                    "-Xmx64m",
                    "-D${ImportWatchdog.TIMEOUT_PROPERTY}=1000",
                    "-cp",
                    System.getProperty("java.class.path"),
                    fake,
                    "orphan",
                ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                process.outputStream.close()
                process.waitFor(30, TimeUnit.SECONDS) shouldBe true
                process.exitValue() shouldBe ImportWorkerLaunch.ORPHAN_EXIT_CODE
                process.inputStream.readAllBytes().size shouldBe 0
            } finally {
                process.destroyForcibly()
            }
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
            command.none { it.startsWith("-Djava.io.tmpdir=") } shouldBe true
        }

        "the worker command carries the private temp directory, and it is never forwarded from the supervisor" {
            val dir = Path.of("/private/kstep-import-x")
            val command = ImportWorkerLaunch.command(listOf("a.step"), 1234, tmpDir = dir)
            command shouldContain "-Djava.io.tmpdir=$dir"
            command.count { it.startsWith("-Djava.io.tmpdir=") } shouldBe 1
            ImportWorkerLaunch.FORWARDED_PROPERTIES shouldNotContain "java.io.tmpdir"
        }

        "the worker arguments are rebuilt from the validated command" {
            val command = resolveImportCommand(listOf("--validate", "in.step", "--output", "json", "--render", "o.png"))
            val rebuilt = resolveImportCommand(importWorkerArgs(command as CliCommand.Import))
            rebuilt shouldBe command
        }
    })
