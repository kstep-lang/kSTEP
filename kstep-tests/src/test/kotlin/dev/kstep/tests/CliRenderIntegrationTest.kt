package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

// Mirrors dev.kstep.geometry.occt.OcctNativeLibrary.OVERRIDE_PROPERTY, which is `internal` to
// kstep-geometry and therefore not visible from this module -- the property NAME itself is not
// secret (it is documented in that class's own KDoc and in this module's README "Building"
// section), only the resolution logic behind it is internal.
private const val OCCT_OVERRIDE_PROPERTY = "kstep.occt.bridge.library"

private const val CONTEXT_PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    """

/**
 * Subprocess-level, out-of-process integration coverage for `kstep render` (`kstep-cli`'s
 * `RenderCommand.kt`) -- see [CliExportIntegrationTest]'s KDoc for why a genuine child process is
 * used rather than calling `main()` in this test JVM. See
 * docs/adr/ADR-0011-headless-preview-rendering.adoc for the full content/exit-code contract this
 * class exercises.
 *
 * Geometry-dependent cases are gated on [OcctKernel.availability] like every other OCCT-gated
 * suite in this repo (`OcctBridgeSmokeTest`, `RenderOcctPipelineTest`); the Pflicht-Fallback cases
 * (R8/R9) force OCCT "off" for a single subprocess via `OcctNativeLibrary.OVERRIDE_PROPERTY`
 * pointed at a non-existent path -- this is a genuinely different JVM process from this test's
 * own, so it does not disturb this suite's own (in-process, cached) [OcctKernel.availability]
 * reads.
 */
class CliRenderIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-render-integration-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            val resource =
                requireNotNull(CliRenderIntegrationTest::class.java.getResourceAsStream("/$name")) {
                    "test resource /$name not found on classpath"
                }
            resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        // Hardened against DTD/external-entity resolution -- this parser exists specifically to
        // catch an SvgEscaping regression (R15 below), so it must not itself become an XXE vector
        // if a future regression ever lets a `<!DOCTYPE ...>` reach the generated SVG; an
        // unhardened DocumentBuilderFactory would resolve such an entity (local file read, or an
        // outbound network call from CI) instead of surfacing the regression it is meant to catch.
        fun parseXml(xml: String) {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        }

        // R1
        "a product-structure-only script renders a .txt preview by default" {
            val script = File(workDir, "assembly-r1.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R1-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R1-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R1-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "r1.step") { root(definition) }
                    """.trimIndent(),
            )

            val result = runner.run("render", script.name)

            result.exitCode shouldBe 0
            result.stdout.trim() shouldBe "Wrote assembly-r1.txt"
            val outFile = File(workDir, "assembly-r1.txt")
            outFile.exists() shouldBe true
            val text = outFile.readText()
            text shouldContain "roots       1"
            text shouldContain "geometry    none"
            text shouldContain "Validation"
            text shouldContain "PRODUCT_DEFINITION"
        }

        // R2
        "--with-step includes the Part-21 text; without it the text is absent" {
            val script = File(workDir, "assembly-r2.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R2-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R2-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R2-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "r2.step") { root(definition) }
                    """.trimIndent(),
            )

            val withStep = runner.run("render", script.name, "--with-step", "-o", "r2-with.txt")
            withStep.exitCode shouldBe 0
            val withStepText = File(workDir, "r2-with.txt").readText()
            withStepText shouldContain "ISO-10303-21;"
            withStepText shouldContain "END-ISO-10303-21;"

            val without = runner.run("render", script.name, "-o", "r2-without.txt")
            without.exitCode shouldBe 0
            val withoutText = File(workDir, "r2-without.txt").readText()
            withoutText.contains("ISO-10303-21;") shouldBe false
        }

        // R3
        "--format svg on a product-structure-only script renders a text-card SVG, not a triangle mesh" {
            val script = File(workDir, "assembly-r3.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R3-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R3-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R3-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "r3.step") { root(definition) }
                    """.trimIndent(),
            )

            val result = runner.run("render", script.name, "--format", "svg")

            result.exitCode shouldBe 0
            val svg = File(workDir, "assembly-r3.svg").readText()
            svg shouldContain "<svg"
            svg shouldContain "</svg>"
            svg shouldContain "product structure only"
            svg.contains("<polygon") shouldBe false
            parseXml(svg)
        }

        // R4
        "a WHERE-rule violation exits 1 and writes no output file" {
            val script = File(workDir, "invalid-r4.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    stepFile(fileName = "invalid.step") {
                        root(product(id = "") { name = "Nameless"; frameOfReference = setOf(prodCtx) })
                    }
                    """.trimIndent(),
            )

            val result = runner.run("render", "--output", "json", script.name)

            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]?.jsonPrimitive?.content shouldBe "error"
            json["errorKind"]?.jsonPrimitive?.content shouldBe "validation_failed"
            json["command"]?.jsonPrimitive?.content shouldBe "render"
            File(workDir, "invalid-r4.txt").exists() shouldBe false
            File(workDir, "invalid-r4.svg").exists() shouldBe false
        }

        // R5
        "a script that fails to compile renders human-readable text starting with \"Script failed to compile:\"" {
            val script = File(workDir, "does-not-compile-r5.kstep.kts")
            script.writeText("this is not valid kotlin {{{")

            val result = runner.run("render", script.name)

            result.exitCode shouldBe 1
            result.stdout shouldContain "Script failed to compile:"
        }

        // R6
        "a real OCCT box renders a well-formed, non-trivial SVG (content=geometry)".config(enabled = available) {
            val script = copyFixture("hello-box.kstep.kts")

            val result = runner.run("render", "--output", "json", script.name)

            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["content"]?.jsonPrimitive?.content shouldBe "geometry"
            json["fallback"]?.jsonPrimitive?.boolean shouldBe false
            val geometry = json["geometry"]?.jsonObject
            ((geometry?.get("triangleCount")?.jsonPrimitive?.int ?: 0) >= 6) shouldBe true

            val svgFile = File(workDir, "hello-box.svg")
            svgFile.exists() shouldBe true
            val svg = svgFile.readText()
            // A 10x20x30 box culls to 6 visible triangles (see IsometricProjectionTest's
            // identical unit-cube reasoning) -- a few hundred bytes of markup, not a large file;
            // this just proves the writer produced real triangle data, not an empty shell.
            (svg.length > 500) shouldBe true
            svg.contains("unavailable") shouldBe false
            parseXml(svg)
            (svg.split("<polygon").size - 1 >= 6) shouldBe true
        }

        // R7
        "a real OCCT box renders a plausible, distinctly-shaded isometric PNG".config(enabled = available) {
            val script = copyFixture("hello-box.kstep.kts")

            val result =
                runner.run(
                    "render",
                    script.name,
                    "--format",
                    "png",
                    "-w",
                    "800",
                    "--height",
                    "600",
                    "-o",
                    "box-r7.png",
                )

            result.exitCode shouldBe 0
            val pngFile = File(workDir, "box-r7.png")
            pngFile.exists() shouldBe true
            val image = ImageIO.read(pngFile)
            image.width shouldBe 800
            image.height shouldBe 600

            var nonBackground = 0
            val histogram = mutableMapOf<Int, Int>()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    if (rgb != -1) {
                        nonBackground++
                        val level = rgb and 0xFF
                        histogram[level] = (histogram[level] ?: 0) + 1
                    }
                }
            }
            val fraction = nonBackground.toDouble() / (image.width * image.height)
            (fraction in 0.15..0.75) shouldBe true
            (histogram.values.count { it >= 500 } >= 3) shouldBe true
        }

        // R8
        "geometry script falls back to a text-card SVG when OCCT is forced unavailable (Pflicht-Fallback)" {
            val script = copyFixture("hello-box.kstep.kts")

            val result =
                runner.run(
                    "render",
                    "--output",
                    "json",
                    script.name,
                    "-o",
                    "box-r8-fallback.svg",
                    jvmArgs = listOf("-D$OCCT_OVERRIDE_PROPERTY=/nonexistent/libkstep_occt_bridge.so"),
                )

            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["fallback"]?.jsonPrimitive?.boolean shouldBe true
            json["fallbackReason"]?.jsonPrimitive?.content shouldBe "occt_unavailable"
            json["occt"]
                ?.jsonObject
                ?.get("available")
                ?.jsonPrimitive
                ?.boolean shouldBe false

            val svg = File(workDir, "box-r8-fallback.svg").readText()
            parseXml(svg)
            svg shouldContain "OCCT"
            svg shouldContain "unavailable"
            svg shouldContain "apt-get"
            svg.contains("<polygon") shouldBe false
            result.stderr shouldContain "reason"
        }

        // R9
        "geometry script falls back to a text preview when OCCT is forced unavailable" {
            val script = copyFixture("hello-box.kstep.kts")

            val result =
                runner.run(
                    "render",
                    script.name,
                    "--format",
                    "text",
                    "-o",
                    "box-r9-fallback.txt",
                    jvmArgs = listOf("-D$OCCT_OVERRIDE_PROPERTY=/nonexistent/libkstep_occt_bridge.so"),
                )

            result.exitCode shouldBe 0
            val text = File(workDir, "box-r9-fallback.txt").readText()
            text shouldContain "Geometry preview unavailable"
            text shouldContain "apt-get"
        }

        // R10
        "--require-geometry turns the OCCT-unavailable fallback into a failure" {
            val script = copyFixture("hello-box.kstep.kts")

            val result =
                runner.run(
                    "render",
                    script.name,
                    "--require-geometry",
                    "-o",
                    "box-r10.svg",
                    jvmArgs = listOf("-D$OCCT_OVERRIDE_PROPERTY=/nonexistent/libkstep_occt_bridge.so"),
                )

            result.exitCode shouldBe 1
            result.stderr shouldContain "require-geometry"
        }

        // R11 -- the self-guarded/portable script pattern ADR-0011 recommends, run unconditionally
        // (works whether or not this machine actually has OCCT).
        "a self-guarded script that only builds geometry when OCCT is available renders content=summary cleanly" {
            val script = copyFixture("hello-box-guarded.kstep.kts")

            val result = runner.run("render", "--output", "json", script.name)

            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            if (available) {
                json["content"]?.jsonPrimitive?.content shouldBe "geometry"
            } else {
                json["content"]?.jsonPrimitive?.content shouldBe "summary"
                json["fallback"]?.jsonPrimitive?.boolean shouldBe false
            }
        }

        // R12
        "a script that closed its own shape before registering it falls back to a notice".config(enabled = available) {
            val script = copyFixture("hello-box-closed.kstep.kts")

            val result = runner.run("render", "--output", "json", script.name)

            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["content"]?.jsonPrimitive?.content shouldBe "notice"
            json["fallbackReason"]?.jsonPrimitive?.content shouldBe "shape_closed_by_script"
        }

        // R14
        "-o with an explicit .png extension resolves auto-format to png" {
            val script = File(workDir, "assembly-r14.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R14-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R14-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R14-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "r14.step") { root(definition) }
                    """.trimIndent(),
            )

            val result = runner.run("render", script.name, "-o", "custom-r14.png")

            result.exitCode shouldBe 0
            result.stdout.trim() shouldBe "Wrote custom-r14.png"
            val pngFile = File(workDir, "custom-r14.png")
            pngFile.exists() shouldBe true
            ImageIO.read(pngFile) // must decode as a real PNG
        }

        // R15
        "a name containing SVG/script-injection characters is escaped, not embedded raw" {
            val script = File(workDir, "injection-r15.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R15-001") { name = "</text><script>alert(1)</script>"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R15-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R15-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "</text><script>alert(1)</script>", description = listOf("desc")) { root(part); root(definition) }
                    """.trimIndent(),
            )

            val result = runner.run("render", script.name, "--format", "svg")

            result.exitCode shouldBe 0
            val svg = File(workDir, "injection-r15.svg").readText()
            // A product's own `name` never reaches the default (non---with-step) text card at all
            // -- only PreviewSummary.modelLines' "file name" line (fed from stepFile's `fileName`)
            // is guaranteed present regardless of flags, so the payload is planted there. Asserting
            // only the RAW form's absence cannot fail even if SvgEscaping.escape() regressed to an
            // identity function (parseXml would still parse the raw markup as valid, well-nested
            // XML) -- positively assert the ESCAPED form is present too.
            svg.contains("<script>alert") shouldBe false
            svg shouldContain "&lt;/text&gt;&lt;script&gt;alert(1)&lt;/script&gt;"
            parseXml(svg)
        }

        // R16
        "an out-of-range --width is rejected without allocating anything" {
            val script = File(workDir, "assembly-r16.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R16-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R16-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R16-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "r16.step") { root(definition) }
                    """.trimIndent(),
            )

            val tooWide = runner.run("render", script.name, "--width", "99999")
            tooWide.exitCode shouldBe 1

            val zero = runner.run("render", script.name, "--width", "0")
            zero.exitCode shouldBe 1
        }

        // R17
        "kstep export on a geometry-carrying script warns on stderr and reports shapeCount in JSON".config(
            enabled = available,
        ) {
            val script = copyFixture("hello-box.kstep.kts")

            val result = runner.run("export", "--output", "json", script.name, "--out", "hello-box-r17.step")

            result.exitCode shouldBe 0
            File(workDir, "hello-box-r17.step").exists() shouldBe true
            result.stderr shouldContain "geometry merging is not wired into 'export' yet"
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["shapeCount"]?.jsonPrimitive?.int shouldBe 1
        }

        // R18 -- regression test for a real, reproduced crash: a root(...) that is not one of the
        // twelve supported kstep-core AP242 V1/support entity types used to make Part21Writer.emit
        // throw an uncaught Part21WriteException deep inside PreviewSummary.entityListLines,
        // leaving stdout empty for a JSON-consuming caller (MCP, CI). Must now degrade to a normal,
        // successful preview whose entity list just says "unavailable".
        "a root() that is not a supported entity type renders a clean preview instead of crashing with a stack trace" {
            val script = File(workDir, "unsupported-r18.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    stepFile(fileName = "u.step") { root(appCtx); root("this is not an AP242 entity") }
                    """.trimIndent(),
            )

            val result = runner.run("render", "--output", "json", script.name)

            result.exitCode shouldBe 0
            result.stderr.contains("Exception in thread") shouldBe false
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]?.jsonPrimitive?.content shouldBe "success"
            val text = File(workDir, "unsupported-r18.txt").readText()
            text shouldContain "Part 21 instances (unavailable"
        }

        // R19 -- regression test: only the PNG branch of writeContainer called mkdirs() on the
        // output path's parent directory; the identical --out into a not-yet-existing directory
        // crashed with an uncaught FileNotFoundException for svg/text.
        "-o into a not-yet-existing directory creates it for svg and text formats too, not just png" {
            val script = File(workDir, "assembly-r19.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R19-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R19-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R19-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "r19.step") { root(definition) }
                    """.trimIndent(),
            )

            val svgResult = runner.run("render", script.name, "-o", "nested-r19-svg/preview.svg")
            svgResult.exitCode shouldBe 0
            File(workDir, "nested-r19-svg/preview.svg").exists() shouldBe true

            val textResult = runner.run("render", script.name, "-o", "nested-r19-txt/preview.txt")
            textResult.exitCode shouldBe 0
            File(workDir, "nested-r19-txt/preview.txt").exists() shouldBe true
        }

        // R20 -- regression test: an --out that cannot be written (here: it names an existing
        // directory) used to propagate a raw java.io.FileNotFoundException/stack trace and leave
        // stdout empty even under --output json; must now report a clean io_error and exit 1.
        "-o pointing at an existing directory reports a clean io_error instead of a raw stack trace" {
            val script = File(workDir, "assembly-r20.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R20-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R20-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R20-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "r20.step") { root(definition) }
                    """.trimIndent(),
            )
            val dirTarget = File(workDir, "adir-r20").apply { mkdirs() }

            val result = runner.run("render", "--output", "json", script.name, "-o", dirTarget.name)

            result.exitCode shouldBe 1
            result.stderr.contains("Exception in thread") shouldBe false
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]?.jsonPrimitive?.content shouldBe "error"
            json["errorKind"]?.jsonPrimitive?.content shouldBe "io_error"
            json["command"]?.jsonPrimitive?.content shouldBe "render"
        }

        // R20b -- regression test: ImageIO.write(RenderedImage, String, File) calls
        // output.delete() internally before opening the ImageOutputStream, so with --format png
        // an *empty* existing directory at --out used to be silently deleted and replaced by the
        // PNG file (exit 0, "Wrote ...") instead of failing like svg/text do on the same input.
        "-o at an existing empty directory with --format png reports io_error, not a silently deleted directory" {
            val script = File(workDir, "assembly-r20b.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val part = product("R20B-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("R20B-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("R20B-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "r20b.step") { root(definition) }
                    """.trimIndent(),
            )
            val dirTarget = File(workDir, "pngdir-r20b").apply { mkdirs() }

            val result =
                runner.run(
                    "render",
                    "--output",
                    "json",
                    script.name,
                    "--format",
                    "png",
                    "-o",
                    dirTarget.name,
                )

            result.exitCode shouldBe 1
            result.stderr.contains("Exception in thread") shouldBe false
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]?.jsonPrimitive?.content shouldBe "error"
            json["errorKind"]?.jsonPrimitive?.content shouldBe "io_error"
            json["command"]?.jsonPrimitive?.content shouldBe "render"
            dirTarget.isDirectory shouldBe true
        }
    })
