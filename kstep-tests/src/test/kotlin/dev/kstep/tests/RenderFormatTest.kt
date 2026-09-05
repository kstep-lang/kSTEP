package dev.kstep.tests

import dev.kstep.preview.RenderFormat
import dev.kstep.preview.deriveRenderOutputPath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RenderFormatTest :
    StringSpec({
        "parse recognizes the five format names case-insensitively" {
            RenderFormat.parse("auto") shouldBe RenderFormat.AUTO
            RenderFormat.parse("SVG") shouldBe RenderFormat.SVG
            RenderFormat.parse("Png") shouldBe RenderFormat.PNG
            RenderFormat.parse("TEXT") shouldBe RenderFormat.TEXT
            RenderFormat.parse("glb") shouldBe RenderFormat.GLB
        }

        "parse accepts \"gltf\" as an alias for GLB" {
            RenderFormat.parse("gltf") shouldBe RenderFormat.GLB
            RenderFormat.parse("GLTF") shouldBe RenderFormat.GLB
        }

        "parse returns null for an unrecognized value" {
            RenderFormat.parse("xml") shouldBe null
            RenderFormat.parse("") shouldBe null
        }

        "fromExtension recognizes .svg/.png/.txt/.glb case-insensitively" {
            RenderFormat.fromExtension("x.svg") shouldBe RenderFormat.SVG
            RenderFormat.fromExtension("x.SVG") shouldBe RenderFormat.SVG
            RenderFormat.fromExtension("x.png") shouldBe RenderFormat.PNG
            RenderFormat.fromExtension("x.txt") shouldBe RenderFormat.TEXT
            RenderFormat.fromExtension("x.glb") shouldBe RenderFormat.GLB
            RenderFormat.fromExtension("x.GLB") shouldBe RenderFormat.GLB
        }

        "fromExtension deliberately does NOT recognize .gltf -- this writer only ever produces GLB" {
            RenderFormat.fromExtension("x.gltf") shouldBe null
        }

        "fromExtension returns null for an unrecognized or missing extension" {
            RenderFormat.fromExtension("x.step") shouldBe null
            RenderFormat.fromExtension("x") shouldBe null
        }

        "deriveRenderOutputPath replaces the .kstep.kts extension per format" {
            deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.SVG) shouldBe "bracket.svg"
            deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.PNG) shouldBe "bracket.png"
            deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.TEXT) shouldBe "bracket.txt"
            deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.GLB) shouldBe "bracket.glb"
        }

        "deriveRenderOutputPath appends the extension when the script has no conventional suffix" {
            deriveRenderOutputPath("bracket", RenderFormat.SVG) shouldBe "bracket.svg"
        }

        "deriveRenderOutputPath rejects AUTO -- callers must resolve a concrete format first" {
            shouldThrow<IllegalArgumentException> { deriveRenderOutputPath("bracket.kstep.kts", RenderFormat.AUTO) }
        }
    })
