package dev.kstep.viewer.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class OcctInstallHintTest :
    StringSpec({
        "Linux keeps the apt command" {
            val hint = OcctInstallHint.forOs("Linux")
            hint[0] shouldBe "Install the OCCT dev packages, then rebuild:"
            hint[1] shouldContain "apt-get install"
            hint shouldHaveSize 2
        }

        "macOS gets Homebrew and the native archive instead of apt" {
            val hint = OcctInstallHint.forOs("Mac OS X")
            hint shouldContain "brew install opencascade eigen boost"
            hint.joinToString("\n") shouldNotContain "apt-get"
            hint.joinToString("\n") shouldContain "macos-aarch64"
        }

        "other systems get the platform note" {
            OcctInstallHint.forOs("Windows 11") shouldBe
                listOf("OCCT bridge is only built for Linux x86-64 and macOS arm64")
            OcctInstallHint.forOs(null) shouldHaveSize 1
        }
    })
