package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CliVersionTest :
    StringSpec({
        "--version and version resolve to ShowVersion" {
            resolveCommand(arrayOf("--version")) shouldBe CliCommand.ShowVersion
            resolveCommand(arrayOf("version")) shouldBe CliCommand.ShowVersion
        }

        "--version with extra arguments is rejected" {
            resolveCommand(arrayOf("--version", "extra")) shouldBe CliCommand.ShowUsage(1)
        }

        "USAGE_TEXT documents --version" {
            USAGE_TEXT shouldContain "kstep --version"
        }

        "the generated version resource yields the project version" {
            cliVersion() shouldBe System.getProperty("kstep.expectedVersion")
        }

        "the OCCT-missing hint names the install commands and OCCT 7.9" {
            val hint = NativeDist.missingOcctHint("no bridge")
            hint shouldContain "brew install opencascade"
            hint shouldContain "apt install"
            hint shouldContain "7.9"
            hint shouldContain "no bridge"
        }

        "native-dist mode is off unless the launcher sets the property" {
            System.getProperty(NativeDist.PROPERTY) shouldBe null
            NativeDist.isActive() shouldBe false
        }
    })
