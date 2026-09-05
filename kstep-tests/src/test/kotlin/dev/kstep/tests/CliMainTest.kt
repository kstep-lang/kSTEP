package dev.kstep.tests

import dev.kstep.asciidoc.OnErrorPolicy
import dev.kstep.cli.AsciidocMode
import dev.kstep.cli.CliCommand
import dev.kstep.cli.USAGE_TEXT
import dev.kstep.cli.resolveCommand
import dev.kstep.preview.RenderFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

// resolveCommand only -- never main() itself, since main()'s error path calls exitProcess, which
// would tear down this whole test JVM (and every other test running in it) rather than just
// failing one assertion. See kstep-mcp's own KStepMcpServerTest for the "mcp" branch's actual
// end-to-end coverage; that isn't re-tested here.
class CliMainTest :
    StringSpec({
        "no arguments resolves to ShowUsage with exit code 0" {
            resolveCommand(emptyArray()) shouldBe CliCommand.ShowUsage(0)
        }

        "the \"mcp\" argument resolves to StartMcpServer" {
            resolveCommand(arrayOf("mcp")) shouldBe CliCommand.StartMcpServer
        }

        "the \"help\" argument resolves to ShowUsage with exit code 0" {
            resolveCommand(arrayOf("help")) shouldBe CliCommand.ShowUsage(0)
        }

        "the \"--help\" argument resolves to ShowUsage with exit code 0" {
            resolveCommand(arrayOf("--help")) shouldBe CliCommand.ShowUsage(0)
        }

        "an unknown subcommand resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("bogus")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"mcp\" with a trailing extra argument is rejected, not silently accepted" {
            resolveCommand(arrayOf("mcp", "extra")) shouldBe CliCommand.ShowUsage(1)
        }

        "two unrelated arguments resolve to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("foo", "bar")) shouldBe CliCommand.ShowUsage(1)
        }

        "a very long garbage argument does not throw and resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("x".repeat(50_000))) shouldBe CliCommand.ShowUsage(1)
        }

        "an argument with control/unusual characters does not throw and resolves to ShowUsage with exit code 1" {
            val weird = "\u0000\n\t\uFFFF"
            resolveCommand(arrayOf(weird)) shouldBe CliCommand.ShowUsage(1)
        }

        "a single empty-string argument does not throw and resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("")) shouldBe CliCommand.ShowUsage(1)
        }

        "USAGE_TEXT documents the mcp subcommand" {
            USAGE_TEXT shouldContain "kstep mcp"
        }

        "USAGE_TEXT documents the help subcommand" {
            USAGE_TEXT shouldContain "kstep help"
        }

        "\"export <path>\" with no flags resolves to Export with a null outPath and jsonOutput false" {
            resolveCommand(arrayOf("export", "a.kstep.kts")) shouldBe
                CliCommand.Export(scriptPath = "a.kstep.kts", outPath = null, jsonOutput = false)
        }

        "\"export <path> --out <file>\" resolves to Export with the given outPath" {
            resolveCommand(arrayOf("export", "a.kstep.kts", "--out", "b.step")) shouldBe
                CliCommand.Export(scriptPath = "a.kstep.kts", outPath = "b.step", jsonOutput = false)
        }

        "\"export <path> --output json\" resolves to Export with jsonOutput true" {
            resolveCommand(arrayOf("export", "a.kstep.kts", "--output", "json")) shouldBe
                CliCommand.Export(scriptPath = "a.kstep.kts", outPath = null, jsonOutput = true)
        }

        "\"export\" parses --out and --output json together, in any order" {
            resolveCommand(arrayOf("export", "--output", "json", "--out", "b.step", "a.kstep.kts")) shouldBe
                CliCommand.Export(scriptPath = "a.kstep.kts", outPath = "b.step", jsonOutput = true)
        }

        "\"export\" with no script path resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("export")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"export\" with only flags and no script path resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("export", "--out", "b.step")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"export <path> --out\" with no value resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("export", "a.kstep.kts", "--out")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"export <path> --output\" with no value resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("export", "a.kstep.kts", "--output")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"export <path> --output xml\" (an unknown --output value) resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("export", "a.kstep.kts", "--output", "xml")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"export <path> --bogus\" (an unknown flag) resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("export", "a.kstep.kts", "--bogus")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"export\" with two positional script paths resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("export", "a.kstep.kts", "b.kstep.kts")) shouldBe CliCommand.ShowUsage(1)
        }

        "USAGE_TEXT documents the export subcommand" {
            USAGE_TEXT shouldContain "kstep export"
        }

        "\"render <path>\" with no flags resolves to Render with defaults" {
            resolveCommand(arrayOf("render", "a.kstep.kts")) shouldBe
                CliCommand.Render(
                    scriptPath = "a.kstep.kts",
                    format = RenderFormat.AUTO,
                    outPath = null,
                    width = 1024,
                    height = 768,
                    withStep = false,
                    requireGeometry = false,
                    jsonOutput = false,
                )
        }

        "\"render\" parses -f/-o/-w/--height/--with-step/--require-geometry/--output json together" {
            resolveCommand(
                arrayOf(
                    "render",
                    "a.kstep.kts",
                    "-f",
                    "png",
                    "-o",
                    "b.png",
                    "-w",
                    "800",
                    "--height",
                    "600",
                    "--with-step",
                    "--require-geometry",
                    "--output",
                    "json",
                ),
            ) shouldBe
                CliCommand.Render(
                    scriptPath = "a.kstep.kts",
                    format = RenderFormat.PNG,
                    outPath = "b.png",
                    width = 800,
                    height = 600,
                    withStep = true,
                    requireGeometry = true,
                    jsonOutput = true,
                )
        }

        "\"render\" accepts the long --format/--out flag spellings too" {
            resolveCommand(arrayOf("render", "a.kstep.kts", "--format", "svg", "--out", "b.svg")) shouldBe
                CliCommand.Render(
                    scriptPath = "a.kstep.kts",
                    format = RenderFormat.SVG,
                    outPath = "b.svg",
                    width = 1024,
                    height = 768,
                    withStep = false,
                    requireGeometry = false,
                    jsonOutput = false,
                )
        }

        "\"render\" with no script path resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("render")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"render <path> -f glb\" resolves to Render with format GLB" {
            resolveCommand(arrayOf("render", "a.kstep.kts", "-f", "glb")) shouldBe
                CliCommand.Render(
                    scriptPath = "a.kstep.kts",
                    format = RenderFormat.GLB,
                    outPath = null,
                    width = 1024,
                    height = 768,
                    withStep = false,
                    requireGeometry = false,
                    jsonOutput = false,
                )
        }

        "\"render <path> --format bogus\" resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("render", "a.kstep.kts", "--format", "bogus")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"render <path> --format gltf2\" (not a recognized alias) resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("render", "a.kstep.kts", "--format", "gltf2")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"render <path> --width notanumber\" resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("render", "a.kstep.kts", "--width", "notanumber")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"render <path> --out\" with no value resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("render", "a.kstep.kts", "--out")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"render <path> --bogus\" (an unknown flag) resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("render", "a.kstep.kts", "--bogus")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"render\" with two positional script paths resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("render", "a.kstep.kts", "b.kstep.kts")) shouldBe CliCommand.ShowUsage(1)
        }

        "USAGE_TEXT documents the render subcommand" {
            USAGE_TEXT shouldContain "kstep render"
        }

        "\"asciidoc --input a.adoc --output b.adoc\" resolves to Asciidoc single-file mode with defaults" {
            resolveCommand(arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc")) shouldBe
                CliCommand.Asciidoc(
                    mode = AsciidocMode.SingleFile("a.adoc", "b.adoc"),
                    format = RenderFormat.SVG,
                    width = 1024,
                    height = 768,
                    requireGeometry = false,
                    onError = OnErrorPolicy.FAIL,
                )
        }

        "\"asciidoc --input-dir in --output-dir out\" resolves to Asciidoc tree mode" {
            resolveCommand(arrayOf("asciidoc", "--input-dir", "in", "--output-dir", "out")) shouldBe
                CliCommand.Asciidoc(
                    mode = AsciidocMode.Tree("in", "out"),
                    format = RenderFormat.SVG,
                    width = 1024,
                    height = 768,
                    requireGeometry = false,
                    onError = OnErrorPolicy.FAIL,
                )
        }

        "\"asciidoc\" parses -f/-w/--height/--require-geometry/--on-error together" {
            resolveCommand(
                arrayOf(
                    "asciidoc",
                    "--input",
                    "a.adoc",
                    "--output",
                    "b.adoc",
                    "-f",
                    "png",
                    "-w",
                    "800",
                    "--height",
                    "600",
                    "--require-geometry",
                    "--on-error",
                    "card",
                ),
            ) shouldBe
                CliCommand.Asciidoc(
                    mode = AsciidocMode.SingleFile("a.adoc", "b.adoc"),
                    format = RenderFormat.PNG,
                    width = 800,
                    height = 600,
                    requireGeometry = true,
                    onError = OnErrorPolicy.CARD,
                )
        }

        "\"asciidoc\" with both --input and --input-dir resolves to ShowUsage with exit code 1" {
            resolveCommand(
                arrayOf("asciidoc", "--input", "a.adoc", "--input-dir", "in", "--output-dir", "out"),
            ) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc\" with neither --input nor --input-dir resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("asciidoc")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc --input a.adoc\" without --output resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("asciidoc", "--input", "a.adoc")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc --input-dir in\" without --output-dir resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("asciidoc", "--input-dir", "in")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc --input a.adoc --output b.adoc --output-dir out\" resolves ShowUsage, not discarding --output-dir" {
            resolveCommand(
                arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--output-dir", "out"),
            ) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc --input-dir in --output-dir out --output x.adoc\" resolves to ShowUsage, not discarding --output" {
            resolveCommand(
                arrayOf("asciidoc", "--input-dir", "in", "--output-dir", "out", "--output", "x.adoc"),
            ) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc\" with --format auto/text/glb/gltf all resolve to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--format", "auto")) shouldBe
                CliCommand.ShowUsage(1)
            resolveCommand(arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--format", "text")) shouldBe
                CliCommand.ShowUsage(1)
            resolveCommand(arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--format", "glb")) shouldBe
                CliCommand.ShowUsage(1)
            resolveCommand(arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--format", "gltf")) shouldBe
                CliCommand.ShowUsage(1)
        }

        "\"asciidoc\" with --format png is accepted" {
            resolveCommand(arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--format", "png")) shouldBe
                CliCommand.Asciidoc(
                    mode = AsciidocMode.SingleFile("a.adoc", "b.adoc"),
                    format = RenderFormat.PNG,
                    width = 1024,
                    height = 768,
                    requireGeometry = false,
                    onError = OnErrorPolicy.FAIL,
                )
        }

        "\"asciidoc\" with an unknown --on-error value resolves to ShowUsage with exit code 1" {
            resolveCommand(
                arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--on-error", "bogus"),
            ) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc\" with a non-numeric --width resolves to ShowUsage with exit code 1" {
            resolveCommand(
                arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--width", "notanumber"),
            ) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc\" with a stray positional argument resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "extra")) shouldBe
                CliCommand.ShowUsage(1)
        }

        "\"asciidoc --input\" with no value resolves to ShowUsage with exit code 1" {
            resolveCommand(arrayOf("asciidoc", "--input")) shouldBe CliCommand.ShowUsage(1)
        }

        "\"asciidoc\" with an unknown flag resolves to ShowUsage with exit code 1" {
            resolveCommand(
                arrayOf("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "--bogus"),
            ) shouldBe CliCommand.ShowUsage(1)
        }

        "USAGE_TEXT documents the asciidoc subcommand" {
            USAGE_TEXT shouldContain "kstep asciidoc"
        }
    })
