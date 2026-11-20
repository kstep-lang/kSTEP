package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ScrollTargetTest :
    StringSpec({
        val lh = 20f

        "a comfortable line does not scroll" {
            // viewport 400 px = 20 lines; line 10 is at 180..200, scroll 0, margin 3 lines
            ScrollTarget.scrollFor(10, 100, 0, 400, lh) shouldBe null
        }

        "a line near the top or bottom edge scrolls so the margin is kept" {
            ScrollTarget.scrollFor(19, 100, 0, 400, lh) shouldBe (18 * 20 - 60)
            ScrollTarget.scrollFor(2, 100, 400, 400, lh) shouldBe 0
        }

        "a line far away scrolls with the margin above it" {
            ScrollTarget.scrollFor(50, 100, 0, 400, lh) shouldBe (49 * 20 - 60)
        }

        "the target is clamped to the scrollable range" {
            ScrollTarget.scrollFor(1, 100, 500, 400, lh) shouldBe 0
            ScrollTarget.scrollFor(100, 100, 0, 400, lh) shouldBe (100 * 20 - 400)
        }

        "a tiny viewport reduces the margin" {
            ScrollTarget.scrollFor(5, 100, 80, 40, lh) shouldBe null
            ScrollTarget.scrollFor(5, 100, 0, 40, lh) shouldBe 80
        }

        "a single line and out-of-range lines" {
            ScrollTarget.scrollFor(1, 1, 0, 400, lh) shouldBe null
            ScrollTarget.scrollFor(999, 10, 0, 100, lh) shouldBe (10 * 20 - 100)
            ScrollTarget.scrollFor(-4, 10, 100, 100, lh) shouldBe 0
        }

        "a large file does not overflow" {
            ScrollTarget.scrollFor(200_000, 200_000, 0, 800, lh) shouldBe (200_000 * 20 - 800)
        }

        "degenerate sizes give no scroll" {
            ScrollTarget.scrollFor(5, 10, 0, 0, lh) shouldBe null
            ScrollTarget.scrollFor(5, 10, 0, 100, 0f) shouldBe null
        }
    })
