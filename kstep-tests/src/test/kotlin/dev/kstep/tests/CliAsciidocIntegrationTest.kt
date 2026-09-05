package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val OCCT_OVERRIDE_PROPERTY = "kstep.occt.bridge.library"

private const val CONTEXT_PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    """

/**
 * Subprocess-level, out-of-process integration coverage for `kstep asciidoc`
 * (`kstep-cli`'s `AsciidocCommand.kt`) -- mirrors [CliRenderIntegrationTest]'s own approach and
 * reuses [CliProcessRunner]. See docs/adr/ADR-0019-kstep-asciidoc.adoc.
 */
class CliAsciidocIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-asciidoc-integration-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            val resource =
                requireNotNull(CliAsciidocIntegrationTest::class.java.getResourceAsStream("/$name")) {
                    "test resource /$name not found on classpath"
                }
            resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        fun parseXml(xml: String) {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        }

        // F1
        "single-file mode rewrites the .adoc and produces a parseable SVG image" {
            val input = File(workDir, "f1.adoc")
            input.writeText(
                "```kstep\n" +
                    CONTEXT_PRELUDE +
                    """
                    val part = product("F1-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("F1-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("F1-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "f1.step") { root(definition) }
                    """.trimIndent() +
                    "\n```\n",
            )
            val output = File(workDir, "f1-out.adoc")

            val result = runner.run("asciidoc", "--input", input.name, "--output", output.name)

            result.exitCode shouldBe 0
            output.readText() shouldContain "image::"
            val svgFile = File(workDir, "f1-1.svg")
            svgFile.exists() shouldBe true
            parseXml(svgFile.readText())
        }

        // F2
        "tree mode mirrors a nested tree, including a non-.adoc file" {
            val inputDir = File(workDir, "f2-in").apply { mkdirs() }
            File(inputDir, "sub").mkdirs()
            File(inputDir, "sub/page.adoc").writeText(
                "```kstep\n" +
                    CONTEXT_PRELUDE +
                    """
                    val part = product("F2-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val prodFormation = productDefinitionFormation("F2-001-F") { ofProduct = part }.getOrThrow()
                    val definition = productDefinition("F2-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
                    stepFile(fileName = "f2.step") { root(definition) }
                    """.trimIndent() +
                    "\n```\n",
            )
            File(inputDir, "sub/notes.txt").writeText("unchanged")
            val outputDir = File(workDir, "f2-out")

            val result = runner.run("asciidoc", "--input-dir", inputDir.name, "--output-dir", outputDir.name)

            result.exitCode shouldBe 0
            File(outputDir, "sub/page.adoc").exists() shouldBe true
            File(outputDir, "sub/notes.txt").readText() shouldBe "unchanged"
        }

        // F3
        "a path-traversal macro exits 1 and writes no output" {
            val input = File(workDir, "f3.adoc")
            input.writeText("kstep::../../../../etc/passwd[]\n")
            val output = File(workDir, "f3-out.adoc")

            val result = runner.run("asciidoc", "--input", input.name, "--output", output.name)

            result.exitCode shouldBe 1
            output.exists() shouldBe false
        }

        // F4
        "--require-geometry fails when OCCT is forced unavailable" {
            val script = copyFixture("hello-box.kstep.kts")
            val input = File(workDir, "f4.adoc")
            input.writeText("kstep::${script.name}[format=svg]\n")
            val output = File(workDir, "f4-out.adoc")

            val result =
                runner.run(
                    "asciidoc",
                    "--input",
                    input.name,
                    "--output",
                    output.name,
                    "--require-geometry",
                    jvmArgs = listOf("-D$OCCT_OVERRIDE_PROPERTY=/nonexistent/libkstep_occt_bridge.so"),
                )

            result.exitCode shouldBe 1
            output.exists() shouldBe false
        }

        // F5
        "without --require-geometry, a forced-unavailable OCCT script becomes a notice card, path-redacted" {
            val script = copyFixture("hello-box.kstep.kts")
            val input = File(workDir, "f5.adoc")
            input.writeText("kstep::${script.name}[format=svg]\n")
            val output = File(workDir, "f5-out.adoc")

            val result =
                runner.run(
                    "asciidoc",
                    "--input",
                    input.name,
                    "--output",
                    output.name,
                    jvmArgs = listOf("-D$OCCT_OVERRIDE_PROPERTY=/nonexistent/libkstep_occt_bridge.so"),
                )

            result.exitCode shouldBe 0
            val svg = File(workDir, "f5-1.svg").readText()
            svg shouldContain "OCCT"
            svg shouldContain "unavailable"
            svg shouldContain "apt-get"
            svg.shouldNotContain("/nonexistent")
        }

        // F6
        "a real geometry script renders an SVG with at least six polygons".config(enabled = available) {
            val script = copyFixture("hello-box.kstep.kts")
            val input = File(workDir, "f6.adoc")
            input.writeText("kstep::${script.name}[format=svg]\n")
            val output = File(workDir, "f6-out.adoc")

            val result = runner.run("asciidoc", "--input", input.name, "--output", output.name)

            result.exitCode shouldBe 0
            val svg = File(workDir, "f6-1.svg").readText()
            (svg.split("<polygon").size - 1 >= 6) shouldBe true
        }

        // F7
        "a broken script fails the run by default, but renders a card under --on-error card" {
            val input = File(workDir, "f7.adoc")
            input.writeText("```kstep\nval x = \n```\n")
            val outputFail = File(workDir, "f7-fail.adoc")

            val failResult = runner.run("asciidoc", "--input", input.name, "--output", outputFail.name)
            failResult.exitCode shouldBe 1
            outputFail.exists() shouldBe false

            val outputCard = File(workDir, "f7-card.adoc")
            val cardResult =
                runner.run(
                    "asciidoc",
                    "--input",
                    input.name,
                    "--output",
                    outputCard.name,
                    "--on-error",
                    "card",
                )
            cardResult.exitCode shouldBe 0
            File(workDir, "f7-1.svg").readText() shouldContain "Script failed to compile:"
        }

        // F8
        "--input without --output exits 1 with the usage text" {
            val result = runner.run("asciidoc", "--input", "whatever.adoc")
            result.exitCode shouldBe 1
        }
    })
