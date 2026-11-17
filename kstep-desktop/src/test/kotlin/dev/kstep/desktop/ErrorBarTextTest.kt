package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.ErrorBarText
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneOffset

class ErrorBarTextTest :
    StringSpec({
        val errors =
            listOf(
                DesktopError("KSTEP-S-001", 12, 5, "first"),
                DesktopError("KSTEP-D-005", null, null, "second"),
                DesktopError("KSTEP-S-003", 7, null, "third"),
            )

        "the headline shows code, line:column and message" {
            ErrorBarText.headline(errors) shouldBe "KSTEP-S-001  12:5  first"
            ErrorBarText.headline(listOf(errors[1])) shouldBe "KSTEP-D-005  second"
            ErrorBarText.headline(listOf(errors[2])) shouldBe "KSTEP-S-003  7  third"
            ErrorBarText.headline(emptyList()) shouldBe ""
        }

        "the more label counts the remaining errors" {
            ErrorBarText.moreLabel(errors) shouldBe "+2 more"
            ErrorBarText.moreLabel(errors.take(1)) shouldBe null
        }

        "the copy text has one line per error" {
            ErrorBarText.copyText(errors).lines() shouldBe
                listOf("KSTEP-S-001  12:5  first", "KSTEP-D-005  second", "KSTEP-S-003  7  third")
        }

        "the status line groups thousands independent of the default locale" {
            val saved = java.util.Locale.getDefault()
            try {
                java.util.Locale.setDefault(java.util.Locale.GERMANY)
                ErrorBarText.statusOk(412, 8204) shouldBe "● Ok · 412 ms · 8,204 triangles"
            } finally {
                java.util.Locale.setDefault(saved)
            }
        }

        "the last-good badge shows the time" {
            ErrorBarText.lastGoodBadge(Instant.parse("2026-11-17T09:05:03Z"), ZoneOffset.UTC) shouldBe
                "Showing last good result · 09:05:03"
        }

        "the triangle warning starts at the threshold" {
            ErrorBarText.triangleWarning(49_999) shouldBe null
            (ErrorBarText.triangleWarning(50_000) != null) shouldBe true
        }
    })
