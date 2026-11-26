package dev.kstep.desktop.draft

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Path
import java.time.ZoneId

class DraftTextTest :
    StringSpec({
        val utc = ZoneId.of("UTC")
        val at = 1_700_000_000_000L // 2023-11-14 22:13 UTC

        fun offer(
            kind: OfferKind,
            reason: String? = null,
        ) = RecoveryOffer(Path.of("/tmp/a.kstep.kts"), kind, at, "body", reason)

        "the time is shown as day, month and clock in English" {
            DraftText.stamp(at, utc) shouldBe "14 Nov, 22:13"
        }

        "each kind has its own text with the time of the last write" {
            val texts = OfferKind.entries.map { DraftText.offerMessage(offer(it, "why"), false, utc) }
            texts.toSet().size shouldBe 4
            texts.forEach { it shouldContain "14 Nov, 22:13" }
            texts[OfferKind.NOT_EDITABLE.ordinal] shouldContain "why"
        }

        "the emphasised text starts with the instruction" {
            DraftText.offerMessage(offer(OfferKind.UNCHANGED), true, utc) shouldStartWith
                "Choose Restore or Discard first. "
            DraftText.offerMessage(offer(OfferKind.UNCHANGED), false, utc) shouldNotContain "Choose"
        }

        "file names are cleaned and shortened" {
            val shown = DraftText.elsewhereMessage("a" + Char(0x202E) + "b\u0007c" + "x".repeat(300) + ".kstep.kts")
            shown shouldContain "‹U+202E›"
            shown shouldContain "‹U+0007›"
            shown shouldNotContain Char(0x202E).toString()
            (shown.length < 200) shouldBe true
        }

        "every notice names its code" {
            listOf("KSTEP-D-042", "KSTEP-D-044", "KSTEP-D-045").forEach { DraftText.noticeMessage(it) shouldContain it }
        }
    })
