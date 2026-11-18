package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.LiveRunController
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** The controller with the real runner and real child processes (no OCCT needed: the script loops before any geometry). */
class ExportControllerProcessTest :
    StringSpec({
        "stop kills the export worker, writes nothing and leaves no files behind".config(timeout = 240.seconds) {
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            val out = Files.createDirectory(dir.resolve("out"))
            val runner = ExportWorkerRunner(session)
            val controller =
                ExportController(
                    worker = { s, f, g, started -> runner.run(s, f, g, started) },
                    sessionDir = session,
                )
            try {
                val script = TestSupport.script(dir, "loop", "while (true) {}\n")
                controller.begin(script, ExportFormat.STEP, out)
                controller.start()
                TestSupport.await(120_000) { TestSupport.liveWorkers().isNotEmpty() } shouldBe true
                controller.stop()
                controller.state.value
                    .shouldBeInstanceOf<ExportState.Configuring>()
                    .notice shouldBe
                    ExportText.STOPPED_NOTICE
                TestSupport.await(60_000) { TestSupport.liveWorkers().isEmpty() } shouldBe true
                Files.list(out).use { it.count() } shouldBe 0L
            } finally {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }

        "a script that runs into the export timeout is KSTEP-D-001 in the dialog".config(timeout = 240.seconds) {
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            val out = Files.createDirectory(dir.resolve("out"))
            val runner = ExportWorkerRunner(session, timeoutMillis = 8_000)
            val controller =
                ExportController(
                    worker = { s, f, g, started -> runner.run(s, f, g, started) },
                    sessionDir = session,
                )
            try {
                val script = TestSupport.script(dir, "loop", "while (true) {}\n")
                controller.begin(script, ExportFormat.GLB, out)
                controller.start()
                val state =
                    TestSupport.awaitExportState(controller, 120_000) {
                        it is ExportState.Configuring &&
                            it.error != null
                    }
                state.shouldBeInstanceOf<ExportState.Configuring>().error!!.code shouldBe DesktopErrorCodes.TIMEOUT
                Files.list(out).use { it.count() } shouldBe 0L
            } finally {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
