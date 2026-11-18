package dev.kstep.cli

import dev.kstep.preview.RenderFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The export worker of kstep-desktop (ADR-0057) accepts exactly four argument shapes; everything else is exit 2. */
class DesktopExportWorkerTest :
    StringSpec({
        fun parse(vararg args: String) = DesktopExportWorker.parse(args.toList())

        val ok = arrayOf("a.kstep.kts", "--format", "glb", "--out", "/tmp/o.glb", "--output", "json")

        "the three mesh formats become render commands that require geometry" {
            for ((id, format) in listOf(
                "glb" to RenderFormat.GLB,
                "stl" to RenderFormat.STL,
                "3mf" to RenderFormat.THREE_MF,
            )) {
                val command = parse("a.kstep.kts", "--format", id, "--out", "/tmp/o", "--output", "json")
                val render = command.shouldBeInstanceOf<CliCommand.Render>()
                render.format shouldBe format
                render.requireGeometry shouldBe true
                render.jsonOutput shouldBe true
                render.outPath shouldBe "/tmp/o"
                render.withStep shouldBe false
            }
        }

        "step becomes an export command without the require-geometry flag and without validation" {
            val command = parse("a.kstep.kts", "--format", "step", "--out", "/tmp/o.step", "--output", "json")
            val export = command.shouldBeInstanceOf<CliCommand.Export>()
            export.requireGeometry shouldBe false
            export.validate shouldBe false
            export.jsonOutput shouldBe true
            export.outPath shouldBe "/tmp/o.step"
        }

        "extra flags, a wrong order, missing parts and unknown formats are rejected" {
            parse(*ok, "--with-step") shouldBe null
            parse(*ok, "--tolerance", "0.1") shouldBe null
            parse("a.kstep.kts", "--out", "/tmp/o", "--format", "glb", "--output", "json") shouldBe null
            parse("a.kstep.kts", "--format", "glb", "--output", "json") shouldBe null
            parse("a.kstep.kts", "--format", "svg", "--out", "/tmp/o", "--output", "json") shouldBe null
            parse("a.kstep.kts", "--format", "glb", "--out", "/tmp/o", "--output", "text") shouldBe null
            parse("a.kstep.kts", "--format", "glb", "--out", "/tmp/o") shouldBe null
            parse() shouldBe null
        }

        "two scripts, a flag as the script and a flag as the output are rejected" {
            parse("a.kstep.kts", "b.kstep.kts", "--format", "glb", "--out", "/tmp/o", "--output", "json") shouldBe null
            parse("--format", "--format", "glb", "--out", "/tmp/o", "--output", "json") shouldBe null
            parse("a.kstep.kts", "--format", "glb", "--out", "--output", "--output", "json") shouldBe null
        }

        "empty and control-character paths are rejected" {
            parse("", "--format", "glb", "--out", "/tmp/o", "--output", "json") shouldBe null
            parse("a.kstep.kts", "--format", "glb", "--out", "", "--output", "json") shouldBe null
            parse("a\u0000.kstep.kts", "--format", "glb", "--out", "/tmp/o", "--output", "json") shouldBe null
            parse("a.kstep.kts", "--format", "glb", "--out", "/tmp/o\n", "--output", "json") shouldBe null
        }

        "the worker's orphan watchdog outlives the export timeout (it must not halt a legitimate export)" {
            (DesktopExportWorker.ORPHAN_ARM_MILLIS > DesktopExportWorker.EXPORT_TIMEOUT_MILLIS) shouldBe true
            DesktopExportWorker.ORPHAN_ARM_MILLIS shouldBe
                DesktopExportWorker.EXPORT_TIMEOUT_MILLIS + ImportWorkerLaunch.ORPHAN_GRACE_MILLIS
            // the import watchdog's own value is lower: using it would halt the worker at 70 s
            (
                ImportWatchdog.resolveTimeoutMillis() + ImportWorkerLaunch.ORPHAN_GRACE_MILLIS <
                    DesktopExportWorker.EXPORT_TIMEOUT_MILLIS
            ) shouldBe true
        }

        "an invalid argument list ends the process with the usage exit code".config(timeout = 120.seconds) {
            val command =
                ImportWorkerLaunch.command(
                    listOf("--bogus"),
                    60_000,
                    mainClass = DesktopExportWorker.MAIN_CLASS,
                    maxHeap = "128m",
                )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.close()
            process.inputStream.readAllBytes()
            check(process.waitFor(90, TimeUnit.SECONDS)) { "worker did not end" }
            process.exitValue() shouldBe DesktopExportWorker.USAGE_EXIT_CODE
        }
    })
