package dev.kstep.tests

import dev.kstep.preview.PreviewOutcome
import dev.kstep.preview.PreviewRenderer
import dev.kstep.preview.PreviewRequest
import dev.kstep.preview.PreviewSource
import dev.kstep.preview.RenderFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * Pins "one implementation, two callers": `kstep render` (subprocess, via `kstep-cli`'s
 * `RenderCommand.kt`) and [PreviewRenderer.render] called in-process must produce byte-identical
 * output for the identical script -- otherwise the ADR-0019 extraction has silently forked the
 * Container-Regel/Pflicht-Fallback logic into two implementations instead of sharing one. See
 * that ADR's Decision 2.
 */
class RenderExtractionParityTest :
    StringSpec({
        val workDir = File("build/render-extraction-parity-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            val resource =
                requireNotNull(RenderExtractionParityTest::class.java.getResourceAsStream("/$name")) {
                    "test resource /$name not found on classpath"
                }
            resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        "kstep render (subprocess) and PreviewRenderer.render (in-process) agree byte-for-byte on hello-box.kstep.kts" {
            val script = copyFixture("hello-box.kstep.kts")

            val cliResult = runner.run("render", script.name, "-f", "svg", "-o", "parity-cli.svg")
            cliResult.exitCode shouldBe 0
            val cliBytes = File(workDir, "parity-cli.svg").readBytes()

            val request =
                PreviewRequest(
                    source = PreviewSource.ScriptFile(script),
                    format = RenderFormat.SVG,
                )
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()

            outcome.bytes.toString(Charsets.UTF_8) shouldBe cliBytes.toString(Charsets.UTF_8)
        }
    })
