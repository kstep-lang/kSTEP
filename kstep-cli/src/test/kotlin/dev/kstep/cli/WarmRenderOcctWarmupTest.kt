package dev.kstep.cli

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** The geometry warm-up script of the warm worker really renders geometry (ADR-0081). Named after OCCT for CI's guard. */
class WarmRenderOcctWarmupTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "the geometry warm-up script renders a GLB".config(enabled = available, timeout = 300.seconds) {
            val dir = Files.createTempDirectory("warmup-test")
            try {
                val script = dir.resolve("w.kstep.kts")
                Files.writeString(script, DesktopWarmRenderWorker.GEOMETRY_WARMUP)
                val out = dir.resolve("w.glb")
                val command =
                    resolveCommand(
                        arrayOf(
                            "render",
                            script.toString(),
                            "--format",
                            "glb",
                            "--out",
                            out.toString(),
                            "--output",
                            "json",
                        ),
                    ) as CliCommand.Render
                val saved = System.out
                val buffer = ByteArrayOutputStream()
                System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
                val code =
                    try {
                        RenderExit.capture { runRender(command, includeParts = true) }
                    } finally {
                        System.setOut(saved)
                    }
                buffer.toString(Charsets.UTF_8) shouldContain "\"status\":\"success\""
                code shouldBe 0
                (Files.size(out) > 0) shouldBe true
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
