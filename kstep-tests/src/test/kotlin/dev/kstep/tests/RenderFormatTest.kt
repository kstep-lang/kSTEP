package dev.kstep.tests

import dev.kstep.cli.RenderFormat
import dev.kstep.cli.deriveRenderOutputPath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RenderFormatTest :
    StringSpec({
        "parse recognizes the four format names case-insensitively" {
            RenderFormat.parse("auto") shouldBe RenderFormat.AUTO
            RenderFormat.parse("SVG") shouldBe RenderFormat.SVG
            RenderFormat.parse("Png") shouldBe RenderFormat.PNG
            RenderFormat.parse("TEXT") shouldBe RenderFormat.TEXT
        }

        "parse returns null for an unrecognized value" {
            RenderFormat.parse("xml") shouldBe null
            RenderFormat.parse("") shouldBe null
        }

        "fromExtension recognizes .svg/.png/.txt case-insensitively" {
            RenderFormat.fromExtension("x.svg") shouldBe RenderFormat.SVG
            RenderFormat.fromExtension("x.SVG") shouldBe RenderFormat.SVG
            RenderFormat.fromExtension("x.png") shouldBe RenderFormat.PNG
            RenderFormat.fromExtension("x.txt") shouldBe RenderFormat.TEXT
        }

        "fromExtension returns null for an unrecognized or missing extension" {
            RenderFormat.fromExtension("x.step") shouldBe null
            RenderFormat.fromExtension("x") shouldBe null
        }

        "deriveRenderOutputPath replaces the .kstep.kts extension per format" {
            deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.SVG) shouldBe "bracket.svg"
            deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.PNG) shouldBe "bracket.png"
            deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.TEXT) shouldBe "bracket.txt"
        }

        "deriveRenderOutputPath appends the extension when the script has no conventional suffix" {
            deriveRenderOutputPath("bracket", RenderFormat.SVG) shouldBe "bracket.svg"
        }

        "deriveRenderOutputPath rejects AUTO -- callers must resolve a concrete format first" {
            shouldThrow<IllegalArgumentException> { deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.AUTO) }
        }
    })
