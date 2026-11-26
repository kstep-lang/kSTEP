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
    })
