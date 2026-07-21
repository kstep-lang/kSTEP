package dev.kstep.tests

import dev.kstep.cli.CliCommand
import dev.kstep.cli.USAGE_TEXT
import dev.kstep.cli.resolveCommand
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
    })
