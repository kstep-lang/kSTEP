package dev.kstep.desktop.export

import dev.kstep.cli.WorkerOutcome
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The judging of a worker's end (no process needed) and the checks before a process is started. */
class ExportWorkerRunnerTest :
    StringSpec({
        val out: Path = Path.of("unused.step")

        fun runner(dir: Path = Path.of("unused")) = ExportWorkerRunner(dir, timeoutMillis = 5_000)

        fun exited(
            code: Int,
            stdout: String = "",
            stderr: String = "",
        ) = WorkerOutcome.Exited(1, code, stdout.toByteArray(), stderr.toByteArray())

        fun code(result: ExportWorkerResult) = result.shouldBeInstanceOf<ExportWorkerResult.Failure>().error.code

        "a timeout is KSTEP-D-001 and names the limit" {
            val result = runner().judge(WorkerOutcome.TimedOut(1), ExportFormat.STEP, out)
            code(result) shouldBe DesktopErrorCodes.TIMEOUT
            (result as ExportWorkerResult.Failure).error.message.contains("5 s") shouldBe true
        }

        "too much output is KSTEP-D-003" {
            code(runner().judge(WorkerOutcome.OutputOverflow(1, 10), ExportFormat.STEP, out)) shouldBe
                DesktopErrorCodes.OUTPUT_TOO_LARGE
        }

        "a start failure is KSTEP-D-006" {
            code(runner().judge(WorkerOutcome.StartFailed("no java"), ExportFormat.STEP, out)) shouldBe
                DesktopErrorCodes.WORKER_START_FAILED
        }

        "an incomplete output is KSTEP-D-007" {
            code(
                runner().judge(WorkerOutcome.IncompleteOutput(1, 0, ByteArray(0), ByteArray(0)), ExportFormat.GLB, out),
            ) shouldBe
                DesktopErrorCodes.RESULT_UNREADABLE
        }

        "running out of memory is KSTEP-D-002" {
            code(
                runner().judge(
                    exited(3, stderr = "Terminating due to java.lang.OutOfMemoryError: Java heap space"),
                    ExportFormat.STEP,
                    out,
                ),
            ) shouldBe DesktopErrorCodes.HEAP_EXHAUSTED
        }

        "the usage exit code, a signal and no output are KSTEP-D-007" {
            code(runner().judge(exited(2), ExportFormat.STEP, out)) shouldBe DesktopErrorCodes.RESULT_UNREADABLE
            code(runner().judge(exited(137), ExportFormat.STEP, out)) shouldBe DesktopErrorCodes.RESULT_UNREADABLE
            code(runner().judge(exited(0), ExportFormat.STEP, out)) shouldBe DesktopErrorCodes.RESULT_UNREADABLE
            code(runner().judge(exited(0, stdout = "hello\n"), ExportFormat.STEP, out)) shouldBe
                DesktopErrorCodes.RESULT_UNREADABLE
        }

        "a good STEP result is produced, the last stdout line counts" {
            val json = """{"status":"success","shapeCount":0}"""
            runner().judge(exited(0, stdout = "script printed this\n$json\n"), ExportFormat.STEP, out) shouldBe
                ExportWorkerResult.Produced(out)
        }

        "an unmerged STEP is KSTEP-D-019" {
            val json = """{"status":"success","shapeCount":2,"geometry":{"merged":false,"occt":{"available":false}}}"""
            code(runner().judge(exited(0, stdout = json), ExportFormat.STEP, out)) shouldBe
                DesktopErrorCodes.EXPORT_UNAVAILABLE
        }

        "a script that does not exist is KSTEP-D-005 and starts no process" {
            val dir = TestSupport.newDir()
            try {
                var started = false
                val result =
                    ExportWorkerRunner(dir).run(dir.resolve("missing.kstep.kts"), ExportFormat.STEP, 1) {
                        started =
                            true
                    }
                code(result) shouldBe DesktopErrorCodes.FILE_MISSING
                started shouldBe false
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a script that loops is stopped by the export timeout (KSTEP-D-001) and leaves no output".config(
            timeout = 120.seconds,
        ) {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.script(dir, "loop", "while (true) {}\n")
                val result = ExportWorkerRunner(dir, timeoutMillis = 8_000).run(script, ExportFormat.STEP, 1) { }
                code(result) shouldBe DesktopErrorCodes.TIMEOUT
                dir.toFile().listFiles()!!.map { it.name }.filter {
                    it.startsWith("export-") ||
                        it.startsWith("kstep-import-")
                } shouldBe
                    emptyList()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
