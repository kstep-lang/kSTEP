package dev.kstep.desktop.run

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Which code a sandbox failure gets, per flow (ADR-0062); a supervisor with an injected start, no real worker. */
class WorkerRunnerSandboxCodeTest :
    StringSpec({
        val fakeBox = WorkerSandbox.Bubblewrap(Path.of("/usr/bin/bwrap"), Path.of("/s"), Path.of("/s"), hideTmp = false)
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

        fun failing(dir: Path): (Long, Path, (Process) -> Unit) -> ImportWorkerSupervisor =
            { timeout, base, started ->
                ImportWorkerSupervisor(
                    timeout,
                    tempBase = base,
                    onStarted = started,
                    start = { throw IOException("nope") },
                )
            }

        fun refusing(): (Long, Path, (Process) -> Unit) -> ImportWorkerSupervisor =
            { timeout, base, started ->
                ImportWorkerSupervisor(
                    timeout,
                    tempBase = base,
                    onStarted = started,
                    start = { ProcessBuilder("/bin/sh", "-c", "echo 'bwrap: no permission' >&2; exit 2").start() },
                )
            }

        fun code(r: RunResult): DesktopError = r.shouldBeInstanceOf<RunResult.Failure>().errors.single()

        "a failed start with the user-flow code is D-031 with the user text" {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.script(dir, "a", "x")
                val r =
                    WorkerRunner(
                        dir,
                        sandbox = fakeBox,
                        sandboxFailureCode = DesktopErrorCodes.SANDBOX_START_FAILED,
                        sandboxFailureMessage = UserScriptSandbox.SANDBOX_FAILED_MESSAGE,
                        supervisorFactory = failing(dir),
                    ).run(script, 1) { }
                code(r).code shouldBe "KSTEP-D-031"
                code(r).message.startsWith("Sandbox failed to start; the script was not run.") shouldBe true
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "the defaults keep D-030 for the AI check" {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.script(dir, "a", "x")
                val r = WorkerRunner(dir, sandbox = fakeBox, supervisorFactory = failing(dir)).run(script, 1) { }
                code(r).code shouldBe "KSTEP-D-030"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "without a sandbox a failed start is D-006" {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.script(dir, "a", "x")
                val r = WorkerRunner(dir, supervisorFactory = failing(dir)).run(script, 1) { }
                code(r).code shouldBe "KSTEP-D-006"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "exit 2 with a bwrap line is D-031 in the user flows".config(enabled = !windows) {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.script(dir, "a", "x")
                val r =
                    WorkerRunner(
                        dir,
                        sandbox = fakeBox,
                        sandboxFailureCode = DesktopErrorCodes.SANDBOX_START_FAILED,
                        sandboxFailureMessage = UserScriptSandbox.SANDBOX_FAILED_MESSAGE,
                        supervisorFactory = refusing(),
                    ).run(script, 1) { }
                code(r).code shouldBe "KSTEP-D-031"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a swapped session folder is D-031 and no worker is started".config(enabled = !windows) {
            val real = TestSupport.newDir()
            val link = real.resolveSibling("link-" + real.fileName)
            try {
                Files.createSymbolicLink(link, real)
                val script = TestSupport.script(real, "a", "x")
                val r =
                    WorkerRunner(
                        link,
                        sandbox = fakeBox,
                        sandboxFailureCode = DesktopErrorCodes.SANDBOX_START_FAILED,
                        sandboxFailureMessage = UserScriptSandbox.SANDBOX_FAILED_MESSAGE,
                        supervisorFactory = { _, _, _ -> error("a worker must not be started") },
                    ).run(script, 1) { }
                code(r).code shouldBe "KSTEP-D-031"
                code(r).message shouldBe WorkerRunner.GUARD_MESSAGE_USER
            } finally {
                Files.deleteIfExists(link)
                real.toFile().deleteRecursively()
            }
        }
    })
