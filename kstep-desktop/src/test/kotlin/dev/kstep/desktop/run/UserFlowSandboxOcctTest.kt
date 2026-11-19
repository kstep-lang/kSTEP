package dev.kstep.desktop.run

import dev.kstep.cli.WorkerTempDir
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportWorkerResult
import dev.kstep.desktop.export.ExportWorkerRunner
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * Real geometry in the user-flow sandbox (ADR-0062): the native bridges unpack into the worker temp folder inside the
 * session folder, which must stay writable and executable-mappable. Named after OCCT so CI's skip guard covers it; where
 * the system has no sandbox the case runs unsandboxed (like the AI case, the Linux CI job requires one).
 */
class UserFlowSandboxOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a two-part assembly renders and exports STEP and STL in the user-flow sandbox".config(
            enabled = available,
            timeout = 400.seconds,
        ) {
            val box = UserScriptSandbox.production()
            val state = box.resolve()
            val linux = System.getProperty("os.name").orEmpty().startsWith("Linux")
            if (state is UserSandboxState.Unavailable) {
                check(!(linux && System.getProperty("kstep.sandbox.require") == "true")) {
                    "the Linux sandbox is required but unavailable: ${state.detail}"
                }
                return@config
            }
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            try {
                val script = TestSupport.copyFixture(dir, "export-colour-assembly-two-parts.kstep.kts")
                val run = WorkerRunner(session, sandbox = box.forSession(session)).run(script, 1) { }
                (run.shouldBeInstanceOf<RunResult.Success>().model.triangles > 0) shouldBe true
                for (format in listOf(ExportFormat.STEP, ExportFormat.STL)) {
                    val r =
                        ExportWorkerRunner(session, timeoutMillis = 120_000, sandbox = box.forSession(session))
                            .run(script, format, 2) { }
                    val produced = r.shouldBeInstanceOf<ExportWorkerResult.Produced>()
                    (Files.size(produced.file) > 0) shouldBe true
                    Files.deleteIfExists(produced.file)
                }
            } finally {
                WorkerTempDir.deleteRecursively(session)
                dir.toFile().deleteRecursively()
            }
        }
    })
