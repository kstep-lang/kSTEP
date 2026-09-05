package dev.kstep.preview

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageWriterSpi

/**
 * Temporarily deregisters every ImageIO [ImageWriterSpi] able to write "png" so
 * `ImageIO.write(image, "png", out)` returns `false` for the duration of [block] -- simulates a
 * stripped-down jlink runtime image (`kstep-cli`'s own distribution form) with no PNG
 * `ImageWriter` registered, without needing an actual such JVM. Always restores every
 * deregistered SPI afterwards, even if [block] throws, so no state leaks into a later test
 * sharing this JVM.
 */
private fun withoutPngImageWriter(block: () -> Unit) {
    val registry = IIORegistry.getDefaultInstance()
    val pngWriterSpis =
        registry
            .getServiceProviders(ImageWriterSpi::class.java, true)
            .asSequence()
            .filter { spi -> spi.formatNames.any { it.equals("png", ignoreCase = true) } }
            .toList()
    pngWriterSpis.forEach { registry.deregisterServiceProvider(it) }
    try {
        block()
    } finally {
        pngWriterSpis.forEach { registry.registerServiceProvider(it) }
    }
}

private const val CONTEXT_PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    """

private const val PRODUCT_STRUCTURE_SCRIPT =
    CONTEXT_PRELUDE +
        """
        val part = product("PR-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
        val prodFormation = productDefinitionFormation("PR-001-F") { ofProduct = part }.getOrThrow()
        val definition = productDefinition("PR-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
        stepFile(fileName = "pr.step") { root(definition) }
        """

// A fixed, non-blank timestamp -- KStepScriptHost only defaults a BLANK timestamp to
// Instant.now(), so this script's rendered output (unlike PRODUCT_STRUCTURE_SCRIPT's) is
// reproducible across repeated evaluations. See docs/adr/ADR-0019-kstep-asciidoc.adoc's
// Stolperfalle 12 -- a Summary/Notice card with a defaulted timestamp is NOT deterministic;
// only a script with an explicit timestamp (or real geometry, which carries no timestamp at all)
// is.
private const val PRODUCT_STRUCTURE_SCRIPT_FIXED_TS =
    CONTEXT_PRELUDE +
        """
        val part = product("PR-002") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
        val prodFormation = productDefinitionFormation("PR-002-F") { ofProduct = part }.getOrThrow()
        val definition = productDefinition("PR-002-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
        stepFile(fileName = "pr2.step", timestamp = "2026-01-01T00:00:00Z") { root(definition) }
        """

private const val INVALID_SCRIPT =
    CONTEXT_PRELUDE +
        """
        stepFile(fileName = "u.step") { root(product(id = "") { name = "Nameless"; frameOfReference = setOf(prodCtx) }) }
        """

private const val HELLO_BOX_SCRIPT =
    CONTEXT_PRELUDE +
        """
        val part = product("BOX-001") { name = "Box"; frameOfReference = setOf(prodCtx) }.getOrThrow()
        val prodFormation = productDefinitionFormation("BOX-001-F") { ofProduct = part }.getOrThrow()
        val definition = productDefinition("BOX-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
        val box = OcctKernel.makeBox(10.0, 20.0, 30.0)
        stepFile(fileName = "hello-box.step") { root(definition); shape(definition, box) }
        """

class PreviewRendererTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        // This module's build.gradle.kts forwards "-Pkstep.occt.require=true" into this test
        // JVM's system properties exactly like kstep-tests/OcctBridgeSmokeTest does -- but until
        // this case existed, nothing here ever READ that property. Every OCCT-dependent case
        // below is instead gated solely on `available` above, so on a machine without the OCCT
        // dev packages installed the whole set silently reports green via skip, and the
        // "-Pkstep.occt.require=true" flag this project's release/CI builds are run with (see
        // README 'Building') proves nothing for this module. See the identical guard in
        // OcctBridgeSmokeTest.
        "the OCCT bridge is available when this module's build requires it" {
            if (System.getProperty("kstep.occt.require") == "true") {
                OcctKernel.availability().shouldBeInstanceOf<OcctAvailability.Available>()
            }
        }

        "a product-structure-only script with AUTO renders a SUMMARY text card" {
            val request = PreviewRequest(PreviewSource.InlineScript(PRODUCT_STRUCTURE_SCRIPT, "pr.kstep.kts"))
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()

            outcome.contentKind shouldBe PreviewContentKind.SUMMARY
            outcome.format shouldBe RenderFormat.TEXT
            outcome.model?.hasGeometry shouldBe false
        }

        "a product-structure-only script with SVG renders a text card, not a triangle mesh" {
            val request =
                PreviewRequest(
                    PreviewSource.InlineScript(PRODUCT_STRUCTURE_SCRIPT, "pr.kstep.kts"),
                    format = RenderFormat.SVG,
                )
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()

            outcome.format shouldBe RenderFormat.SVG
            val svg = outcome.bytes.toString(Charsets.UTF_8)
            svg shouldContain "<svg"
            svg.contains("<polygon") shouldBe false
        }

        "an InlineScript source's displayName appears in the rendered text card" {
            val request =
                PreviewRequest(
                    PreviewSource.InlineScript(PRODUCT_STRUCTURE_SCRIPT, "my-special-name.kstep.kts"),
                    format = RenderFormat.TEXT,
                )
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()
            outcome.bytes.toString(Charsets.UTF_8) shouldContain "my-special-name.kstep.kts"
        }

        "a WHERE-rule violation resolves to ScriptFailed with ValidationErrors" {
            val request = PreviewRequest(PreviewSource.InlineScript(INVALID_SCRIPT, "invalid.kstep.kts"))
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.ScriptFailed>()
            outcome.outcome.shouldBeInstanceOf<dev.kstep.script.KStepScriptOutcome.ValidationErrors>()
        }

        "width=99999 resolves to InvalidRequest with the legacy error text" {
            val request =
                PreviewRequest(
                    PreviewSource.InlineScript(PRODUCT_STRUCTURE_SCRIPT, "pr.kstep.kts"),
                    width = 99999,
                )
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.InvalidRequest>()
            outcome.message shouldContain "--width/--height must each be between"
            outcome.message shouldContain "99999"
        }

        "a real geometry script renders GEOMETRY content with the expected triangle counts".config(
            enabled = available,
        ) {
            val request =
                PreviewRequest(
                    PreviewSource.InlineScript(HELLO_BOX_SCRIPT, "hello-box.kstep.kts"),
                    format = RenderFormat.SVG,
                )
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()

            outcome.contentKind shouldBe PreviewContentKind.GEOMETRY
            outcome.geometry?.meshTriangleCount shouldBe 12
            outcome.geometry?.projectedTriangleCount shouldBe 6
        }

        "PreviewWriter creates missing parent directories" {
            val dir = File("build/preview-writer-test/nested/dir").apply { deleteRecursively() }
            val target = File(dir, "out.txt")
            PreviewWriter.write(target.path, "hello".toByteArray(Charsets.UTF_8))
            target.exists() shouldBe true
            target.readText() shouldBe "hello"
        }

        "PreviewWriter reports a clean PreviewIoException for an existing-directory target, without deleting it" {
            val dirTarget =
                File("build/preview-writer-test/adir").apply {
                    deleteRecursively()
                    mkdirs()
                }
            try {
                PreviewWriter.write(dirTarget.path, "hello".toByteArray(Charsets.UTF_8))
                error("expected PreviewIoException")
            } catch (e: PreviewIoException) {
                e.message shouldContain "failed to write"
            }
            dirTarget.isDirectory shouldBe true
        }

        "PNG format throws PreviewEncodingException when no PNG ImageWriter is registered" {
            val request =
                PreviewRequest(
                    PreviewSource.InlineScript(PRODUCT_STRUCTURE_SCRIPT, "pr.kstep.kts"),
                    format = RenderFormat.PNG,
                )
            withoutPngImageWriter {
                val exception = shouldThrow<PreviewEncodingException> { PreviewRenderer.render(request) }
                exception.message shouldContain "png"
            }
        }

        "rendering the same request twice produces byte-identical output" {
            val request =
                PreviewRequest(
                    PreviewSource.InlineScript(PRODUCT_STRUCTURE_SCRIPT_FIXED_TS, "pr2.kstep.kts"),
                    format = RenderFormat.SVG,
                )
            val first = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()
            val second = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()
            first.bytes.toString(Charsets.UTF_8) shouldBe second.bytes.toString(Charsets.UTF_8)
        }
    })
