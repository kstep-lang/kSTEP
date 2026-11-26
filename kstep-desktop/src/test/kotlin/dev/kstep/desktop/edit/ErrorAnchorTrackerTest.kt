package dev.kstep.desktop.edit

import dev.kstep.desktop.run.DesktopError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ErrorAnchorTrackerTest :
    StringSpec({
        val errors = listOf(DesktopError("KSTEP-S-001", 2, 1, "m"))
        val saved = "a\nbcd\ne"

        "a new error list pins against the saved text and replays the dirty buffer" {
            val t = ErrorAnchorTracker()
            val a = t.update(errors, saved, "NEW\n" + saved)
            a.anchors.single().start shouldBe 6
        }

        "later edits move the anchors with the buffer, once per change" {
            val t = ErrorAnchorTracker()
            t
                .update(errors, saved, saved)
                .anchors
                .single()
                .start shouldBe 2
            t
                .update(errors, saved, "X$saved")
                .anchors
                .single()
                .start shouldBe 3
            t
                .update(errors, saved, "X$saved")
                .anchors
                .single()
                .start shouldBe 3
            t
                .update(errors, saved, saved)
                .anchors
                .single()
                .start shouldBe 2
        }

        "a vanished anchor does not come back when the text returns" {
            val t = ErrorAnchorTracker()
            t.update(errors, saved, saved)
            t.update(errors, saved, "a\n\ne").anchors shouldBe emptyList()
            t.update(errors, saved, saved).anchors shouldBe emptyList()
        }

        "a new list of errors replaces the anchors, an empty list clears them" {
            val t = ErrorAnchorTracker()
            t.update(errors, saved, saved)
            t
                .update(listOf(DesktopError("KSTEP-S-002", 3, 1, "m")), saved, saved)
                .anchors
                .single()
                .start shouldBe 6
            t.update(emptyList(), saved, saved).anchors shouldBe emptyList()
        }

        "an unsaved run pins the marks against the exact text it was sent, not against the saved file (ADR-0086)" {
            val t = ErrorAnchorTracker()
            val onLine3 = listOf(DesktopError("KSTEP-S-001", 3, 1, "m"))
            val sent = "X\n" + saved // the snapshot the worker saw: "X\na\nbcd\ne"; line 3 is "bcd"
            val first =
                t
                    .update(onLine3, sent, sent)
                    .anchors
                    .single()
                    .start
            first shouldBe 4 // against the saved text, line 3 would be "e" at 6
            // typing afterwards moves the mark with the text
            t
                .update(onLine3, sent, "YY" + sent)
                .anchors
                .single()
                .start shouldBe 6
        }

        "a run of the saved file still uses the saved text as base" {
            val t = ErrorAnchorTracker()
            t
                .update(errors, saved, saved)
                .anchors
                .single()
                .start shouldBe 2
        }
    })
