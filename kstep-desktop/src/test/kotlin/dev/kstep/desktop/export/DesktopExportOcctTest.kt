package dev.kstep.desktop.export

import dev.kstep.cli.WorkerOutcome
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The export worker end to end with real geometry. Named after OCCT on purpose: CI's skip guard then covers it, and the
 * one test that runs without the native bridge carries the marker the guard allows.
 */
class DesktopExportOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "the OCCT bridge is available when kstep-desktop's build requires it" {
            if (System.getProperty("kstep.occt.require") == "true") {
                OcctKernel.availability().shouldBeInstanceOf<OcctAvailability.Available>()
            }
        }

        fun fixtureRun(
            fixture: String,
            format: ExportFormat,
            block: (ExportWorkerResult, Path) -> Unit,
        ) {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.copyFixture(dir, fixture, "model.kstep.kts")
                val result = ExportWorkerRunner(dir).run(script, format, 1) { }
                block(result, dir)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "every format exports a real model to a valid file".config(enabled = available, timeout = 600.seconds) {
            for (format in ExportFormat.ALL) {
                fixtureRun("export-colour-assembly-two-parts.kstep.kts", format) { result, _ ->
                    val produced = result.shouldBeInstanceOf<ExportWorkerResult.Produced>()
                    ExportOutputValidator.validate(produced.file, format).shouldBeInstanceOf<ValidationResult.Ok>()
                    if (format == ExportFormat.STEP) {
                        Files.readString(produced.file) shouldContain "ISO-10303-21"
                        Files.readString(produced.file) shouldContain "MANIFOLD_SOLID_BREP"
                    }
                }
            }
        }

        "a structure-only script exports as STEP with or without the native bridge".config(timeout = 300.seconds) {
            fixtureRun("hello-assembly.kstep.kts", ExportFormat.STEP) { result, _ ->
                val produced = result.shouldBeInstanceOf<ExportWorkerResult.Produced>()
                ExportOutputValidator
                    .validate(
                        produced.file,
                        ExportFormat.STEP,
                    ).shouldBeInstanceOf<ValidationResult.Ok>()
            }
        }

        "a script without geometry cannot be exported as a mesh".config(enabled = available, timeout = 300.seconds) {
            fixtureRun("hello-assembly.kstep.kts", ExportFormat.STL) { result, dir ->
                result.shouldBeInstanceOf<ExportWorkerResult.Failure>().error.code shouldBe
                    DesktopErrorCodes.EXPORT_FAILED
                dir.toFile().listFiles()!!.none { it.name.startsWith("export-") } shouldBe true
            }
        }

        "a script error is reported with its script code and nothing is left behind".config(timeout = 300.seconds) {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.script(dir, "broken", "val x = \n")
                val result = ExportWorkerRunner(dir).run(script, ExportFormat.STEP, 1) { }
                result.shouldBeInstanceOf<ExportWorkerResult.Failure>().error.code shouldBe "KSTEP-S-001"
                dir.toFile().listFiles()!!.none { it.name.startsWith("export-") } shouldBe true
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "export without the native bridge fails predictably when the native bridge is unavailable" {
            // simulated by the worker's own JSON, so the check never skips and never forces a native state
            val json =
                """{"status":"success","shapeCount":3,""" +
                    """"geometry":{"merged":false,"occt":{"available":false,"reason":"no lib"}}}"""
            val runner = ExportWorkerRunner(Path.of("unused"))
            val result =
                runner.judge(
                    WorkerOutcome.Exited(1, 0, json.toByteArray(), ByteArray(0)),
                    ExportFormat.STEP,
                    Path.of("x"),
                )
            result.shouldBeInstanceOf<ExportWorkerResult.Failure>().error.code shouldBe
                DesktopErrorCodes.EXPORT_UNAVAILABLE
        }

        "geometry without the native bridge is KSTEP-D-019, fails predictably when the native bridge is unavailable"
            .config(enabled = !available, timeout = 300.seconds) {
                fixtureRun("export-colour-assembly-two-parts.kstep.kts", ExportFormat.GLB) { result, _ ->
                    result.shouldBeInstanceOf<ExportWorkerResult.Failure>().error.code shouldBe
                        DesktopErrorCodes.EXPORT_UNAVAILABLE
                }
            }
    })
