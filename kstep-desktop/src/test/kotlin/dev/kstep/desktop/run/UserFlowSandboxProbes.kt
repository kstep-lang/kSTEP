package dev.kstep.desktop.run

import dev.kstep.cli.WorkerTempDir
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportWorkerResult
import dev.kstep.desktop.export.ExportWorkerRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

/** Shared cases of the user-flow sandbox tests (ADR-0062): the same assertions on macOS and Linux. */
object UserFlowSandboxProbes {
    /** The sandbox of one session folder, from a fresh production probe (the same code path as the desktop). */
    fun factory(box: UserScriptSandbox): SandboxFor =
        { dir ->
            box.forSession(dir)
                ?: error("no sandbox: ${box.state.value}")
        }

    /** A script and its input file live outside the session folder (`/tmp`, other temp folders): reading must work. */
    fun readCase(sandbox: SandboxFor) {
        val outside = SandboxProbes.outsideDir()
        try {
            val input = Files.writeString(outside.resolve("input.txt"), "hello")
            val script = "if (java.io.File(\"$input\").readText() != \"hello\") error(\"unreadable\")"
            SandboxProbes.run(sandbox, { script }) { _, r -> r.shouldBeInstanceOf<RunResult.Success>() }
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    /** Export of a script without geometry work through a sandboxed runner; D-031 must never appear. */
    fun exportCase(box: UserScriptSandbox) {
        val dir = TestSupport.newDir()
        val session = LiveRunController.createSessionDir(dir)
        try {
            val script = TestSupport.copyFixture(dir, "hello-assembly.kstep.kts", "model.kstep.kts")
            val runner = ExportWorkerRunner(session, timeoutMillis = 120_000, sandbox = box.forSession(session))
            val result = runner.run(script, ExportFormat.STEP, 1) { }
            if (result is ExportWorkerResult.Failure) {
                (result.error.code != DesktopErrorCodes.SANDBOX_START_FAILED) shouldBe true
            } else {
                val produced = result.shouldBeInstanceOf<ExportWorkerResult.Produced>()
                // the desktop (outside the sandbox) can copy the result out of the session folder
                val target = dir.resolve("out.step")
                Files.copy(produced.file, target)
                (Files.size(target) > 0) shouldBe true
            }
        } finally {
            WorkerTempDir.deleteRecursively(session)
            dir.toFile().deleteRecursively()
        }
    }

    /** A knowledge block runs through the sandboxed shared runner. */
    fun knowledgeCase(box: UserScriptSandbox) {
        val dir = TestSupport.newDir()
        val session = LiveRunController.createSessionDir(dir)
        try {
            val file = TestSupport.copyFixture(dir, "hello-assembly.kstep.kts", "block.kstep.kts")
            val result = box.workerRunner(session).value.run(file, 1) { }
            result.shouldBeInstanceOf<RunResult.Success>()
        } finally {
            WorkerTempDir.deleteRecursively(session)
            dir.toFile().deleteRecursively()
        }
    }

    /** A never-ending script is stopped by the timeout under the sandbox, and no worker stays alive. */
    fun timeoutCase(box: UserScriptSandbox) {
        val dir = TestSupport.newDir()
        try {
            val file = TestSupport.script(dir, "loop", "while (true) {}\n")
            val r = WorkerRunner(dir, timeoutMillis = 5_000, sandbox = box.forSession(dir)).run(file, 1) { }
            r
                .shouldBeInstanceOf<RunResult.Failure>()
                .errors
                .single()
                .code shouldBe DesktopErrorCodes.TIMEOUT
            TestSupport.await(60_000) { TestSupport.liveWorkers().isEmpty() } shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
