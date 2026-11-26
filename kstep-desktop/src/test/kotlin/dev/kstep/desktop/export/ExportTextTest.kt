package dev.kstep.desktop.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        "control characters and ANSI escapes never reach the dialog" {
            ExportText.successLine("a\u001B[31m.step", 1) shouldNotContain "\u001B"
        }

        "the step line numbers the phases and shows the limit" {
            ExportText.stepLine(ExportPhase.STARTING, 0, 120_000) shouldBe
                "Step 1 of 4 \u00b7 Starting \u00b7 0 s of max. 120 s"
            ExportText.stepLine(ExportPhase.RUNNING_SCRIPT, 12_400, 120_000) shouldBe
                "Step 2 of 4 \u00b7 Running script \u00b7 12 s of max. 120 s"
            ExportText.stepLine(ExportPhase.VALIDATING, 1_000, 120_000) shouldBe
                "Step 3 of 4 \u00b7 Checking the result \u00b7 1 s of max. 120 s"
            ExportText.stepLine(ExportPhase.WRITING, 2_000, 120_000) shouldBe
                "Step 4 of 4 \u00b7 Writing the file \u00b7 2 s of max. 120 s"
        }

        "a percentage appears only while writing and from 8 MiB on" {
            val min = ExportText.PROGRESS_PERCENT_MIN_BYTES
            ExportText.stepLine(ExportPhase.WRITING, 0, 120_000, 1, min - 1) shouldNotContain "%"
            ExportText.stepLine(ExportPhase.WRITING, 0, 120_000, min / 2, min) shouldContain "50 %"
            ExportText.stepLine(ExportPhase.WRITING, 0, 120_000, 3, 8) shouldNotContain "%"
            ExportText.stepLine(ExportPhase.RUNNING_SCRIPT, 0, 120_000, min / 2, min) shouldNotContain "%"
            ExportText.stepLine(ExportPhase.WRITING, 0, 120_000, null, null) shouldNotContain "%"
        }

        "the percentage is rounded down and never reaches 100" {
            val total = ExportText.PROGRESS_PERCENT_MIN_BYTES * 100
            ExportText.stepLine(ExportPhase.WRITING, 0, 120_000, total * 37 / 100 + 5, total) shouldContain "37 %"
            ExportText.stepLine(ExportPhase.WRITING, 0, 120_000, total, total) shouldContain "99 %"
            ExportText.stepLine(ExportPhase.WRITING, 0, 120_000, total * 2, total) shouldContain "99 %"
            ExportText.stepLine(ExportPhase.WRITING, 0, 120_000, -5, total) shouldContain " 0 %"
        }

        "the bar fraction is null when unknown and clamped otherwise" {
            ExportText.progressFraction(null, 10) shouldBe null
            ExportText.progressFraction(5, null) shouldBe null
            ExportText.progressFraction(5, 0) shouldBe null
            ExportText.progressFraction(5, 10) shouldBe 0.5f
            ExportText.progressFraction(50, 10) shouldBe 1f
            ExportText.progressFraction(-1, 10) shouldBe 0f
        }

        "the dialog says the export uses the saved file; a buffer preview is named too (ADR-0086)" {
            ExportText.unsavedNotes(dirty = false, previewIsBuffer = false) shouldBe emptyList()
            ExportText.unsavedNotes(dirty = true, previewIsBuffer = false) shouldBe listOf("Exports the saved file.")
            ExportText.unsavedNotes(dirty = true, previewIsBuffer = true) shouldBe
                listOf("Exports the saved file.", "The preview shows the unsaved buffer.")
        }
    })
