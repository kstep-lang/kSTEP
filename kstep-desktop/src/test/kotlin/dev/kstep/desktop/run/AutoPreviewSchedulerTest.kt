package dev.kstep.desktop.run

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The automatic preview of unsaved text (ADR-0087): debounce, rate limit, blocks, size limit. No real time. */
class AutoPreviewSchedulerTest :
    StringSpec({
        val ready = AutoPreviewConditions(fileOpen = true, isScript = true)

        /** A scheduler that is on, focused, with a file open whose picture shows [saved]. */
        fun scheduler(saved: String = "saved"): AutoPreviewScheduler {
            val s = AutoPreviewScheduler()
            s.fileChanged(0)
            s.enabledChanged(true, 0)
            s.conditionsChanged(ready, 0)
            s.focusChanged(true, 0)
            s.baselineChanged(saved)
            return s
        }

        "the debounce: nothing at 799 ms, a run at 800 ms" {
            val s = scheduler()
            s.textChanged("a", 1000)
            s.tick(1799) shouldBe null
            s.tick(1800) shouldBe AutoPreviewAction.RunNow("a")
        }

        "typing in a series gives one run, 800 ms after the last key" {
            val s = scheduler()
            for ((i, t) in listOf("a", "ab", "abc").withIndex()) s.textChanged(t, 1000L + i * 300)
            s.tick(1000 + 600 + 799) shouldBe null
            s.tick(1000 + 600 + 800) shouldBe AutoPreviewAction.RunNow("abc")
            s.tick(10_000) shouldBe null
        }

        "the rate limit: the second start is at least 2 s after the first, the last edit still runs" {
            val s = scheduler()
            s.textChanged("a", 1000)
            s.tick(1800) shouldBe AutoPreviewAction.RunNow("a")
            s.textChanged("b", 2000) // debounce alone would be due at 2800
            s.nextDueAt() shouldBe 3800L // lastAutoStart + 2000
            s.tick(3799) shouldBe null
            s.tick(3800) shouldBe AutoPreviewAction.RunNow("b")
            // a later edit at +1900 after the start is not lost
            s.textChanged("c", 3800 + 1900)
            s.nextDueAt() shouldBe 6500L // max(5700 + 800, 3800 + 2000)
            s.tick(6500) shouldBe AutoPreviewAction.RunNow("c")
        }

        "nextDueAt is max of the debounce and the rate limit" {
            val s = scheduler()
            s.nextDueAt() shouldBe null
            s.textChanged("a", 100)
            s.nextDueAt() shouldBe 900L
            s.tick(900) shouldBe AutoPreviewAction.RunNow("a")
            s.textChanged("b", 5000)
            s.nextDueAt() shouldBe 5800L
        }

        "text equal to the shown picture runs nothing" {
            val s = scheduler("saved")
            s.textChanged("saved", 100)
            s.nextDueAt() shouldBe null
            s.tick(100_000) shouldBe null
        }

        "going back to the saved text after a buffer run runs again, so the picture matches" {
            val s = scheduler("saved")
            s.textChanged("changed", 0)
            s.tick(800) shouldBe AutoPreviewAction.RunNow("changed")
            s.textChanged("saved", 1000)
            s.tick(3000) shouldBe AutoPreviewAction.RunNow("saved")
        }

        "blocks give no action and no state change, then the pending change runs" {
            fun blocked(
                conditions: AutoPreviewConditions,
                focus: Boolean = true,
            ) {
                val s = scheduler()
                s.conditionsChanged(conditions, 0)
                s.focusChanged(focus, 0)
                s.textChanged("a", 0)
                s.nextDueAt() shouldBe null
                s.tick(1_000_000) shouldBe null
                // lifting the block plans afresh and runs once
                s.conditionsChanged(ready, 5000)
                s.focusChanged(true, 5000)
                s.tick(5799) shouldBe null
                s.tick(5800) shouldBe AutoPreviewAction.RunNow("a")
            }
            blocked(ready.copy(draftOfferOpen = true))
            blocked(ready.copy(isScript = false)) // a note
            blocked(ready.copy(fileOpen = false))
            blocked(ready, focus = false)
            blocked(ready.copy(fileRunActive = true))
        }

        "a run of the saved file that ends re-plans 800 ms after its end" {
            val s = scheduler()
            s.conditionsChanged(ready.copy(fileRunActive = true), 0)
            s.textChanged("a", 0)
            s.conditionsChanged(ready, 10_000)
            s.nextDueAt() shouldBe 10_800L
        }

        "gaining the focus with a pending difference debounces, it never starts at once" {
            val s = scheduler()
            s.focusChanged(false, 0)
            s.textChanged("a", 0)
            s.focusChanged(true, 7000)
            s.tick(7000) shouldBe null
            s.tick(7800) shouldBe AutoPreviewAction.RunNow("a")
        }

        "gaining the focus without a difference runs nothing" {
            val s = scheduler()
            s.focusChanged(false, 0)
            s.focusChanged(true, 5000)
            s.nextDueAt() shouldBe null
            s.tick(100_000) shouldBe null
        }

        "a failed run does not start again for the same text" {
            val s = scheduler()
            s.textChanged("bad", 0)
            s.tick(800) shouldBe AutoPreviewAction.RunNow("bad")
            // the run failed: neither the text nor the baseline is reset
            s.tick(100_000) shouldBe null
            s.nextDueAt() shouldBe null
            s.textChanged("bad2", 200_000)
            s.tick(200_800) shouldBe AutoPreviewAction.RunNow("bad2")
        }

        "over 1 MiB: one message, no run; typing on gives no second message" {
            val s = scheduler()
            val big = "a".repeat(AutoPreviewScheduler.MAX_BYTES + 1)
            s.textChanged(big, 0)
            s.tick(799) shouldBe null
            s.tick(800) shouldBe AutoPreviewAction.ShowTooLarge
            s.textChanged(big + "b", 1000)
            s.tick(5000) shouldBe null
            s.nextDueAt() shouldBe null
        }

        "under the limit and over again is a new message" {
            val s = scheduler()
            val big = "a".repeat(AutoPreviewScheduler.MAX_BYTES + 1)
            s.textChanged(big, 0)
            s.tick(800) shouldBe AutoPreviewAction.ShowTooLarge
            s.textChanged("small", 1000)
            s.tick(1800) shouldBe AutoPreviewAction.RunNow("small")
            s.textChanged(big, 5000)
            s.tick(5800) shouldBe AutoPreviewAction.ShowTooLarge
        }

        "switching on with an oversized text is one transition: one message" {
            val s = AutoPreviewScheduler()
            s.fileChanged(0)
            s.conditionsChanged(ready, 0)
            s.focusChanged(true, 0)
            val big = "a".repeat(AutoPreviewScheduler.MAX_BYTES + 1)
            s.textChanged(big, 0) // ignored while off
            s.enabledChanged(true, 100)
            s.textChanged(big, 100)
            s.tick(900) shouldBe AutoPreviewAction.ShowTooLarge
            s.tick(5000) shouldBe null
        }

        "the limit counts UTF-8 bytes, not characters" {
            val s = scheduler()
            val text = "ä".repeat(AutoPreviewScheduler.MAX_BYTES / 2 + 1) // fewer chars than bytes, 1 byte over
            (text.length < AutoPreviewScheduler.MAX_BYTES) shouldBe true
            AutoPreviewScheduler.exceedsLimit(text) shouldBe true
            AutoPreviewScheduler.exceedsLimit("ä".repeat(AutoPreviewScheduler.MAX_BYTES / 2)) shouldBe false
            // a surrogate pair is four bytes
            AutoPreviewScheduler.exceedsLimit("😀".repeat(AutoPreviewScheduler.MAX_BYTES / 4)) shouldBe false
            AutoPreviewScheduler.exceedsLimit("😀".repeat(AutoPreviewScheduler.MAX_BYTES / 4 + 1)) shouldBe true
            s.textChanged(text, 0)
            s.tick(800) shouldBe AutoPreviewAction.ShowTooLarge
        }

        "a file change forgets limit, message and rate limit of the old file" {
            val s = scheduler()
            val big = "a".repeat(AutoPreviewScheduler.MAX_BYTES + 1)
            s.textChanged(big, 0)
            s.tick(800) shouldBe AutoPreviewAction.ShowTooLarge
            s.fileChanged(1000)
            s.baselineChanged("other")
            s.textChanged(big, 1000)
            s.tick(1800) shouldBe AutoPreviewAction.ShowTooLarge // reported again for the new file
            s.fileChanged(3000)
            s.baselineChanged("other")
            s.textChanged("x", 3000)
            s.tick(3800) shouldBe AutoPreviewAction.RunNow("x") // no rate limit carried over
        }

        "off: every input is ignored, nothing is due, no tick acts" {
            val s = AutoPreviewScheduler()
            s.fileChanged(0)
            s.conditionsChanged(ready, 0)
            s.focusChanged(true, 0)
            s.baselineChanged("saved")
            s.textChanged("a", 0)
            s.nextDueAt() shouldBe null
            s.tick(1_000_000) shouldBe null
            // switched off again: pending work is gone
            s.enabledChanged(true, 10)
            s.textChanged("b", 10)
            s.enabledChanged(false, 20)
            s.nextDueAt() shouldBe null
            s.tick(1_000_000) shouldBe null
        }

        "the driver feeds the scheduler only when the setting is on, a file is open and it is a script" {
            shouldSchedulePreview(false, ready) shouldBe false
            shouldSchedulePreview(true, ready) shouldBe true
            shouldSchedulePreview(true, ready.copy(fileOpen = false)) shouldBe false
            shouldSchedulePreview(true, ready.copy(isScript = false)) shouldBe false
        }
    })
