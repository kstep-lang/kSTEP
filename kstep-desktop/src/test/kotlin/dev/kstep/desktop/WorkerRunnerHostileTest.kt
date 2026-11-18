package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.WorkerRunner
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.seconds

/**
 * The render worker against hostile and broken scripts, with a real child process on every runner and without OCCT
 * (no skip): the script is evaluated only in the worker, and every abnormal end becomes one clear error (ADR-0051).
 * Deliberately not named after OCCT: the CI skip guard only looks at test classes with "Occt" in their name.
 */
class WorkerRunnerHostileTest :
    StringSpec({
        fun withRunner(
            timeoutMillis: Long = 90_000,
            maxHeap: String = "2g",
            extra: List<String> = emptyList(),
            block: (WorkerRunner, Path) -> Unit,
        ) {
            val dir = TestSupport.newDir()
            try {
                block(WorkerRunner(dir, timeoutMillis = timeoutMillis, maxHeap = maxHeap, extraJvmOptions = extra), dir)
                // nothing of the run is left behind: neither the worker temp directory nor the GLB
                dir
                    .listDirectoryEntries()
                    .filter {
                        it.fileName.toString().let { n ->
                            n.startsWith("kstep-import-") ||
                                n.endsWith(".glb")
                        }
                    }.shouldBeEmpty()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        fun failure(result: RunResult) = result.shouldBeInstanceOf<RunResult.Failure>().errors.first()

        fun run(
            runner: WorkerRunner,
            dir: Path,
            name: String,
            text: String,
            started: AtomicReference<Process?> = AtomicReference(),
        ): RunResult {
            val script = TestSupport.script(dir, name, text)
            return runner.run(script, 1) { started.set(it) }
        }

        "an endless loop is stopped by the timeout (KSTEP-D-001) and the worker is gone".config(timeout = 120.seconds) {
            val started = AtomicReference<Process?>()
            withRunner(timeoutMillis = 5_000) { runner, dir ->
                failure(run(runner, dir, "loop", "while (true) {}\n", started)).code shouldBe DesktopErrorCodes.TIMEOUT
            }
            val process = started.get()
            (process == null || !process.isAlive) shouldBe true
            process?.let { p -> ProcessHandle.of(p.pid()).map { it.isAlive }.orElse(false) shouldBe false }
        }

        "running out of heap is KSTEP-D-002".config(timeout = 120.seconds) {
            withRunner(maxHeap = "256m") { runner, dir ->
                failure(run(runner, dir, "oom", "val a = ByteArray(1_000_000_000)\nprintln(a.size)\n")).code shouldBe
                    DesktopErrorCodes.HEAP_EXHAUSTED
            }
        }

        "a script that floods stdout is stopped (KSTEP-D-003)".config(timeout = 120.seconds) {
            withRunner { runner, dir ->
                failure(run(runner, dir, "flood", "repeat(40_000) { println(\"x\".repeat(1000)) }\n")).code shouldBe
                    DesktopErrorCodes.OUTPUT_TOO_LARGE
            }
        }

        "a script that exits the JVM leaves no result (KSTEP-D-007)".config(timeout = 120.seconds) {
            withRunner { runner, dir ->
                failure(run(runner, dir, "exit", "System.exit(0)\n")).code shouldBe DesktopErrorCodes.RESULT_UNREADABLE
            }
        }

        "a syntax error reports KSTEP-S-001 with the line".config(timeout = 120.seconds) {
            withRunner { runner, dir ->
                val error = failure(run(runner, dir, "syntax", "val a = 1\nval b = 2\nval c = \nval d = 4\n"))
                error.code shouldBe "KSTEP-S-001"
                error.line shouldBe 3
            }
        }

        "a script that prints before failing still yields the error document".config(timeout = 120.seconds) {
            withRunner { runner, dir ->
                val error = failure(run(runner, dir, "print", "println(\"hello\")\nerror(\"boom\")\n"))
                error.code shouldBe "KSTEP-S-003"
            }
        }

        "a product-structure-only script succeeds without geometry".config(timeout = 120.seconds) {
            withRunner { runner, dir ->
                val script = TestSupport.copyFixture(dir, "hello-assembly.kstep.kts")
                val result = runner.run(script, 1) { }.shouldBeInstanceOf<RunResult.Success>()
                result.model.mesh shouldBe null
                result.model.triangles shouldBe 0
                result.model.parts shouldBe null
                result.model.warnings.shouldBeEmpty()
            }
        }

        "a script that is not a .kstep.kts file is refused before any process starts" {
            withRunner { runner, dir ->
                val other = dir.resolve("notes.txt").also { Files.writeString(it, "x") }
                failure(runner.run(other, 1) { error("no process must start") }).code shouldBe
                    DesktopErrorCodes.FILE_MISSING
                failure(runner.run(dir.resolve("none.kstep.kts"), 1) { error("no process must start") }).code shouldBe
                    DesktopErrorCodes.FILE_MISSING
            }
        }

        "without the native bridge a geometry script reports NativeUnavailable".config(timeout = 120.seconds) {
            withRunner(extra = listOf("-Dkstep.occt.bridge.library=/nonexistent/libx.dylib")) { runner, dir ->
                val script = TestSupport.copyFixture(dir, "export-colour-assembly-two-parts.kstep.kts")
                runner.run(script, 1) { }.shouldBeInstanceOf<RunResult.NativeUnavailable>()
            }
        }
    })
