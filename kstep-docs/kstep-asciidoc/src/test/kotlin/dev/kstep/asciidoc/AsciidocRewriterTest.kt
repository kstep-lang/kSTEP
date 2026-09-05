package dev.kstep.asciidoc

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageWriterSpi

/**
 * Temporarily deregisters every ImageIO [ImageWriterSpi] able to write "png" so
 * `ImageIO.write(image, "png", out)` returns `false` for the duration of [block] -- simulates a
 * stripped-down jlink runtime image (`kstep-cli`'s own distribution form) with no PNG
 * `ImageWriter` registered, without needing an actual such JVM. Always restores every
 * deregistered SPI afterwards, even if [block] throws, so no state leaks into a later test
 * sharing this JVM. See `PreviewRendererTest`'s identical helper.
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

private fun productScript(id: String): String =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    val part = product("$id") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
    val prodFormation = productDefinitionFormation("$id-F") { ofProduct = part }.getOrThrow()
    val definition = productDefinition("$id-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
    stepFile(fileName = "$id.step", timestamp = "2026-01-01T00:00:00Z") { root(definition) }
    """.trimIndent()

private const val INVALID_WHERE_SCRIPT =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    stepFile(fileName = "u.step") { root(product(id = "") { name = "Nameless"; frameOfReference = setOf(prodCtx) }) }
    """

private const val COMPILE_ERROR_SCRIPT = "val x = "

class AsciidocRewriterTest :
    StringSpec({
        val tmp = Files.createTempDirectory("kstep-asciidoc-rewriter-test")
        val options = AsciidocOptions()

        // This module's build.gradle.kts forwards "-Pkstep.occt.require=true" into this test
        // JVM's system properties exactly like kstep-tests/OcctBridgeSmokeTest does -- but until
        // this case existed, nothing here ever READ that property. Every OCCT-dependent case
        // below is instead gated solely on `OcctKernel.availability()`, so on a machine without
        // the OCCT dev packages installed the whole set silently reports green via skip, and the
        // "-Pkstep.occt.require=true" flag this project's release/CI builds are run with (see
        // README 'Building') proves nothing for this module. See the identical guard in
        // OcctBridgeSmokeTest.
        "the OCCT bridge is available when this module's build requires it" {
            if (System.getProperty("kstep.occt.require") == "true") {
                OcctKernel.availability().shouldBeInstanceOf<OcctAvailability.Available>()
            }
        }

        fun doc(
            text: String,
            baseName: String = "doc",
        ): AsciidocDocument = AsciidocDocument(text, tmp, tmp, baseName)

        // B1
        "one inline block produces an SVG image and the expected macro line" {
            val text = "```kstep\n${productScript("B1-001")}\n```\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            result.images.size shouldBe 1
            result.text shouldContain "image::doc-1.svg[]"
            result.images
                .single()
                .bytes
                .toString(Charsets.UTF_8) shouldContain "<svg"
        }

        // B2
        "an explicit target produces a matching image name" {
            val text = "[kstep,bracket]\n----\n${productScript("B2-001")}\n----\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            result.text shouldContain "image::bracket.svg[]"
            result.images.single().relativePath shouldBe "bracket.svg"
        }

        // B3
        "format=png produces PNG-magic bytes" {
            val text = "[kstep,bracket,format=png]\n----\n${productScript("B3-001")}\n----\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            val bytes = result.images.single().bytes
            bytes.take(4) shouldBe listOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        }

        // B4
        "alt is quoted in the output, and an alt containing ']' is rejected" {
            val text = "[kstep,b4,alt=\"A bracket\"]\n----\n${productScript("B4-001")}\n----\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            result.text shouldContain "image::b4.svg[\"A bracket\"]"

            val badAlt = "[kstep,b4b,alt=\"has]bracket\"]\n----\n${productScript("B4-002")}\n----\n"
            AsciidocRewriter(options).process(doc(badAlt)).shouldBeInstanceOf<RewriteResult.Failed>()
        }

        // B5
        ":imagesdir: routes image bytes into a subfolder but keeps a bare macro reference" {
            val text = ":imagesdir: images\n\n```kstep\n${productScript("B5-001")}\n```\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            result.images.single().relativePath shouldBe "images/doc-1.svg"
            result.text shouldContain "image::doc-1.svg[]"
        }

        // B13
        "a block BEFORE ':imagesdir:' is not routed into the subfolder, a block AFTER it is" {
            val text =
                "```kstep\n${productScript("B13-001")}\n```\n\n" +
                    ":imagesdir: assets\n\n" +
                    "```kstep\n${productScript("B13-002")}\n```\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            result.images.map { it.relativePath } shouldBe listOf("doc-1.svg", "assets/doc-2.svg")
            result.text shouldContain "image::doc-1.svg[]"
            result.text shouldContain "image::doc-2.svg[]"
        }

        // B6 / B7
        "a WHERE-rule violation fails the document under FAIL, and renders a card under CARD" {
            val text = "```kstep\n$INVALID_WHERE_SCRIPT\n```\n"
            AsciidocRewriter(AsciidocOptions(onError = OnErrorPolicy.FAIL))
                .process(doc(text))
                .shouldBeInstanceOf<RewriteResult.Failed>()

            val cardResult =
                AsciidocRewriter(AsciidocOptions(onError = OnErrorPolicy.CARD))
                    .process(doc(text))
                    .shouldBeInstanceOf<RewriteResult.Rendered>()
            cardResult.images
                .single()
                .bytes
                .toString(Charsets.UTF_8) shouldContain "Validation failed:"
        }

        // B8
        "a compile error under CARD renders an error card" {
            val text = "```kstep\n$COMPILE_ERROR_SCRIPT\n```\n"
            val result =
                AsciidocRewriter(AsciidocOptions(onError = OnErrorPolicy.CARD))
                    .process(doc(text))
                    .shouldBeInstanceOf<RewriteResult.Rendered>()
            result.images
                .single()
                .bytes
                .toString(Charsets.UTF_8) shouldContain "Script failed to compile:"
        }

        // B9
        "two blocks get sequential default names and distinct images" {
            val text = "```kstep\n${productScript("B9-001")}\n```\n\n```kstep\n${productScript("B9-002")}\n```\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            result.text shouldContain "image::doc-1.svg[]"
            result.text shouldContain "image::doc-2.svg[]"
            result.images.map { it.relativePath } shouldBe listOf("doc-1.svg", "doc-2.svg")
        }

        // B10
        "text between blocks is preserved" {
            val text = "before\n\n```kstep\n${productScript("B10-001")}\n```\n\nafter\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            result.text shouldContain "before"
            result.text shouldContain "after"
        }

        // B11
        "a macro referencing a missing file fails with a line number" {
            val text = "kstep::missing.kstep.kts[]\n"
            val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Failed>()
            result.line shouldBe 1
        }

        // B12
        "rendering the same geometry script twice produces byte-identical images".config(
            enabled = OcctKernel.availability() is OcctAvailability.Available,
        ) {
            val script =
                """
                val appCtx = applicationContext { application = "config control" }.getOrThrow()
                val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
                val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
                val part = product("B12-001") { name = "Box"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                val prodFormation = productDefinitionFormation("B12-001-F") { ofProduct = part }.getOrThrow()
                val definition = productDefinition("B12-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                val box = OcctKernel.makeBox(10.0, 20.0, 30.0)
                stepFile(fileName = "b12.step") { root(definition); shape(definition, box) }
                """.trimIndent()
            val text = "```kstep\n$script\n```\n"
            val first = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            val second = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Rendered>()
            first.images
                .single()
                .bytes
                .toString(Charsets.UTF_8) shouldBe
                second.images
                    .single()
                    .bytes
                    .toString(Charsets.UTF_8)
        }

        // B14
        "format=png fails the block cleanly, instead of throwing, when no PNG ImageWriter is registered" {
            val text = "[kstep,b14,format=png]\n----\n${productScript("B14-001")}\n----\n"
            withoutPngImageWriter {
                val result = AsciidocRewriter(options).process(doc(text)).shouldBeInstanceOf<RewriteResult.Failed>()
                result.message shouldContain "image encoding failed"
            }
        }

        // B15
        "format=png under CARD fails cleanly, instead of writing a corrupt image, with no PNG ImageWriter" {
            val text = "[kstep,b15,format=png]\n----\n$COMPILE_ERROR_SCRIPT\n----\n"
            withoutPngImageWriter {
                val result =
                    AsciidocRewriter(AsciidocOptions(onError = OnErrorPolicy.CARD))
                        .process(doc(text))
                        .shouldBeInstanceOf<RewriteResult.Failed>()
                result.message shouldContain "image encoding failed"
            }
        }
    })
