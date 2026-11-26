package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** `kstep desktop [path]` command parsing (ADR-0083). */
class DesktopCommandResolveTest :
    StringSpec({
        "desktop alone resolves to Desktop(null)" {
            resolveCommand(arrayOf("desktop")) shouldBe CliCommand.Desktop(null)
        }

        "desktop with one path resolves to Desktop(path)" {
            resolveCommand(arrayOf("desktop", "a.kstep.kts")) shouldBe CliCommand.Desktop("a.kstep.kts")
        }

        "a path with spaces stays one element" {
            resolveCommand(arrayOf("desktop", "/tmp/a b/c d.md")) shouldBe CliCommand.Desktop("/tmp/a b/c d.md")
        }

        "options are not guessed" {
            resolveCommand(arrayOf("desktop", "--x")) shouldBe CliCommand.ShowUsage(1)
            resolveCommand(arrayOf("desktop", "-")) shouldBe CliCommand.ShowUsage(1)
            resolveCommand(arrayOf("desktop", "-x.md")) shouldBe CliCommand.ShowUsage(1)
        }

        "a path starting with dash-slash is fine" {
            resolveCommand(arrayOf("desktop", "./-x.md")) shouldBe CliCommand.Desktop("./-x.md")
        }

        "more than one argument is rejected" {
            resolveCommand(arrayOf("desktop", "a", "b")) shouldBe CliCommand.ShowUsage(1)
        }

        "USAGE_TEXT documents desktop" {
            (USAGE_TEXT.contains("kstep desktop")) shouldBe true
        }
    })
