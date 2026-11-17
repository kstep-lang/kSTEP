package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The render worker of kstep-desktop (ADR-0051) accepts only JSON-output render arguments; everything else is exit 2. */
class DesktopRenderWorkerTest :
    StringSpec({
        fun exitCodeOf(vararg args: String): Int {
            val command =
                ImportWorkerLaunch.command(
                    args.toList(),
                    60_000,
                    mainClass = DesktopRenderWorker.MAIN_CLASS,
                    maxHeap = "128m",
                )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.close()
            process.inputStream.readAllBytes()
            check(process.waitFor(90, TimeUnit.SECONDS)) { "worker did not end" }
            return process.exitValue()
        }

        "an invalid argument list ends with the usage exit code".config(timeout = 120.seconds) {
            exitCodeOf("--bogus") shouldBe DesktopRenderWorker.USAGE_EXIT_CODE
        }

        "render arguments without --output json end with the usage exit code".config(timeout = 120.seconds) {
            exitCodeOf("some.kstep.kts", "--format", "glb") shouldBe DesktopRenderWorker.USAGE_EXIT_CODE
        }

        "a non-render command word is not accepted".config(timeout = 120.seconds) {
            // the worker prefixes "render" itself, so "mcp" becomes a (missing) script path or an unknown flag
            exitCodeOf("mcp") shouldBe DesktopRenderWorker.USAGE_EXIT_CODE
        }

        "the command resolution of the worker prefix is a render command".config(timeout = 30.seconds) {
            resolveCommand(arrayOf("render", "a.kstep.kts", "--output", "json")).shouldBeInstanceOf<CliCommand.Render>()
        }

        "a script that does not exist is reported as a render error document, not as a crash".config(
            timeout = 120.seconds,
        ) {
            val dir = Files.createTempDirectory("d1-worker")
            try {
                val missing = dir.resolve("missing.kstep.kts").toString()
                val code =
                    exitCodeOf(missing, "--format", "glb", "--out", dir.resolve("o.glb").toString(), "--output", "json")
                (code == 0 || code == 1) shouldBe true
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
