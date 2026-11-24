package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertTarget
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class ConvertPrefillTest :
    StringSpec({
        val none = ConvertPreferences()
        val ws = Path.of("/work/space")

        "an open script wins over the workspace and goes to okf" {
            val script = Path.of("/work/space/bracket.kstep.kts")
            val r = ConvertPrefill.resolve(script, ws, none)
            r shouldBe ConvertRequest(script, ConvertTarget.OKF, null, recursive = false)
        }

        "the script suffix is case-insensitive" {
            val script = Path.of("/work/B.KSTEP.KTS")
            ConvertPrefill.resolve(script, null, none).target shouldBe ConvertTarget.OKF
        }

        "an open note is not a script, the workspace is used" {
            val r = ConvertPrefill.resolve(Path.of("/work/space/n.md"), ws, none)
            r shouldBe ConvertRequest(ws, ConvertTarget.KTS, null, recursive = true)
        }

        "only a workspace gives kts, recursive" {
            ConvertPrefill.resolve(null, ws, none) shouldBe ConvertRequest(ws, ConvertTarget.KTS, null, true)
        }

        "nothing open gives an empty source and the remembered target" {
            val r = ConvertPrefill.resolve(null, null, ConvertPreferences(null, ConvertTarget.OKF))
            r shouldBe ConvertRequest(null, ConvertTarget.OKF, null, recursive = false)
            ConvertPrefill.resolve(null, null, none).target shouldBe ConvertTarget.KTS
        }

        "the remembered folder is used" {
            val out = Path.of("/out")
            ConvertPrefill.resolve(null, ws, ConvertPreferences(out, null)).outDir shouldBe out
        }
    })
