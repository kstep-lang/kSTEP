package dev.kstep.desktop.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class ExportTextTest :
    StringSpec({
        "sizes use base 1000 with one decimal from kilobytes" {
            ExportText.formatSize(999) shouldBe "999 B"
            ExportText.formatSize(1000) shouldBe "1.0 KB"
            ExportText.formatSize(1_400_000) shouldBe "1.4 MB"
            ExportText.formatSize(0) shouldBe "0 B"
        }

        "the success and overwrite lines name the file and the size" {
            ExportText.successLine("bracket.step", 1_400_000) shouldBe "Exported bracket.step (1.4 MB)"
            ExportText.overwriteLine("a.glb", 2_500) shouldBe "a.glb already exists (2.5 KB). Exporting replaces it."
        }

        "the phase line shows elapsed seconds of the limit" {
            ExportText.phaseLine(ExportPhase.RUNNING_SCRIPT, 12_400, 120_000) shouldBe
                "Running script... 12 s of max. 120 s"
        }

        "control characters and ANSI escapes never reach the dialog" {
            ExportText.successLine("a\u001B[31m.step", 1) shouldNotContain "\u001B"
        }

        "a long path is shortened in the middle and keeps the end" {
            val text = "/Users/someone/projects/a/very/deep/folder/with/many/parts/bracket.step"
            val short = ExportText.middleEllipsis(text, 30)
            short.length shouldBe 30
            short.endsWith("bracket.step") shouldBe true
            short.contains("…") shouldBe true
            ExportText.middleEllipsis("short", 30) shouldBe "short"
        }
    })
