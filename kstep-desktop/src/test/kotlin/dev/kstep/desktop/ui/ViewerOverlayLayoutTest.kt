package dev.kstep.desktop.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/** The pure placement rule of the badge next to the inspection panel (ADR-0094). */
class ViewerOverlayLayoutTest :
    StringSpec({
        "without a panel the badge is always top left with the width minus both margins" {
            for (w in listOf(900f, 660f, 432f, 431f, 300f)) {
                ViewerOverlayLayout.badge(w, panelShown = false) shouldBe BadgePlacement(BadgeCorner.TOP_START, w - 24f)
            }
        }

        "with a panel a wide viewer keeps the badge top left next to it" {
            ViewerOverlayLayout.badge(900f, true) shouldBe BadgePlacement(BadgeCorner.TOP_START, 900f - 292f)
            ViewerOverlayLayout.badge(660f, true) shouldBe BadgePlacement(BadgeCorner.TOP_START, 660f - 292f)
        }

        "the border is 432 dp: 140 dp still fit top left, one dp less moves the badge to the bottom" {
            ViewerOverlayLayout.badge(432f, true) shouldBe BadgePlacement(BadgeCorner.TOP_START, 140f)
            ViewerOverlayLayout.badge(431f, true) shouldBe BadgePlacement(BadgeCorner.BOTTOM_START, 431f - 24f)
            ViewerOverlayLayout.badge(300f, true) shouldBe BadgePlacement(BadgeCorner.BOTTOM_START, 300f - 24f)
        }

        "a tiny viewer never yields a negative width" {
            for (panel in listOf(true, false)) {
                ViewerOverlayLayout.badge(100f, panel).maxWidthDp shouldBeGreaterThanOrEqual 0f
                ViewerOverlayLayout.badge(10f, panel).maxWidthDp shouldBeGreaterThanOrEqual 0f
                ViewerOverlayLayout.badge(0f, panel).maxWidthDp shouldBe 0f
            }
        }
    })
