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
    })
