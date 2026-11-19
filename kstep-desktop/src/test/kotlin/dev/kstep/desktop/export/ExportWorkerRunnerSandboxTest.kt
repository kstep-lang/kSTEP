package dev.kstep.desktop.export

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.WorkerSandbox
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The sandbox codes of the export worker (ADR-0062), with injected supervisors. */
class ExportWorkerRunnerSandboxTest :
    StringSpec({
        val box = WorkerSandbox.Bubblewrap(Path.of("/usr/bin/bwrap"), Path.of("/s"), Path.of("/s"), hideTmp = false)
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

        val failingStart: (Long, Path, (Process) -> Unit) -> ImportWorkerSupervisor =
            { timeout, base, started ->
                ImportWorkerSupervisor(
                    timeout,
                    tempBase = base,
                    onStarted = started,
                    start = { throw IOException("x") },
                )
            }

        fun code(r: ExportWorkerResult) = r.shouldBeInstanceOf<ExportWorkerResult.Failure>().error.code

        "a failed start with a sandbox is D-031, without one D-006" {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.script(dir, "a", "x")
                code(
                    ExportWorkerRunner(
                        dir,
                        sandbox = box,
                        supervisorFactory = failingStart,
                    ).run(script, ExportFormat.STEP, 1) {
                    },
                ) shouldBe
                    "KSTEP-D-031"
                code(
                    ExportWorkerRunner(dir, supervisorFactory = failingStart).run(script, ExportFormat.STEP, 1) { },
                ) shouldBe
                    "KSTEP-D-006"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "an unavailable sandbox starts no worker and is D-031" {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.script(dir, "a", "x")
                val r =
                    ExportWorkerRunner(
                        dir,
                        sandbox = WorkerSandbox.Unavailable("none"),
                        supervisorFactory = { _, _, _ -> error("a worker must not be started") },
                    ).run(script, ExportFormat.STEP, 1) { }
                code(r) shouldBe "KSTEP-D-031"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a swapped session folder is D-031 before the start".config(enabled = !windows) {
            val real = TestSupport.newDir()
            val link = real.resolveSibling("link-" + real.fileName)
            try {
                Files.createSymbolicLink(link, real)
                val script = TestSupport.script(real, "a", "x")
                val r =
                    ExportWorkerRunner(
                        link,
                        sandbox = box,
                        supervisorFactory = { _, _, _ -> error("a worker must not be started") },
                    ).run(script, ExportFormat.STEP, 1) { }
                code(r) shouldBe "KSTEP-D-031"
            } finally {
                Files.deleteIfExists(link)
                real.toFile().deleteRecursively()
            }
        }

        "a folder swapped for a link while the worker ran gives D-031 and no output survives".config(
            enabled = !windows,
        ) {
            val real = TestSupport.newDir()
            val moved = real.resolveSibling("moved-" + real.fileName)
            try {
                val script = TestSupport.script(real, "a", "x")
                val factory: (Long, Path, (Process) -> Unit) -> ImportWorkerSupervisor = { timeout, base, started ->
                    ImportWorkerSupervisor(
                        timeout,
                        tempBase = base,
                        onStarted = started,
                        start = {
                            Files.writeString(real.resolve("export-1.step"), "ISO")
                            Files.move(real, moved)
                            Files.createSymbolicLink(real, moved)
                            ProcessBuilder("/bin/sh", "-c", "echo '{\"status\":\"success\",\"shapeCount\":0}'").start()
                        },
                    )
                }
                val r =
                    ExportWorkerRunner(real, sandbox = box, supervisorFactory = factory)
                        .run(script, ExportFormat.STEP, 1) { }
                code(r) shouldBe "KSTEP-D-031"
                Files.exists(moved.resolve("export-1.step")) shouldBe false
            } finally {
                Files.deleteIfExists(real)
                moved.toFile().deleteRecursively()
            }
        }
    })
