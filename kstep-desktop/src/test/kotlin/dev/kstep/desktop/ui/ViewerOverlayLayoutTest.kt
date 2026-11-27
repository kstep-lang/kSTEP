package dev.kstep.desktop.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/** The pure placement rule of the badge next to the inspection panel (ADR-0094). */
class ViewerOverlayLayoutTest :
    StringSpec({
        "without a panel the badge is always top left with the width minus both margins" {
            for (w in listOf(900f, 660f, 432f, 431f, 300f)) {
                ViewerOverlayLayout.badge(w, panelShown = false) shouldBe
                    BadgePlacement(BadgeCorner.TOP_START, w - 24f, 12f)
            }
        }

        "with a panel a wide viewer keeps the badge top left next to it" {
            ViewerOverlayLayout.badge(900f, true) shouldBe BadgePlacement(BadgeCorner.TOP_START, 900f - 292f, 12f)
            ViewerOverlayLayout.badge(660f, true) shouldBe BadgePlacement(BadgeCorner.TOP_START, 660f - 292f, 12f)
        }

        "the border is 432 dp: 140 dp still fit top left, one dp less moves the badge to the bottom" {
            ViewerOverlayLayout.badge(432f, true) shouldBe BadgePlacement(BadgeCorner.TOP_START, 140f, 12f)
            ViewerOverlayLayout.badge(431f, true) shouldBe
                BadgePlacement(BadgeCorner.BOTTOM_START, 431f - 24f, ViewerOverlayLayout.hintReserveDp(431f))
            ViewerOverlayLayout.badge(300f, true) shouldBe
                BadgePlacement(BadgeCorner.BOTTOM_START, 300f - 24f, ViewerOverlayLayout.hintReserveDp(300f))
        }

        "a tiny viewer never yields a negative width" {
            for (panel in listOf(true, false)) {
                ViewerOverlayLayout.badge(100f, panel).maxWidthDp shouldBeGreaterThanOrEqual 0f
                ViewerOverlayLayout.badge(10f, panel).maxWidthDp shouldBeGreaterThanOrEqual 0f
                ViewerOverlayLayout.badge(0f, panel).maxWidthDp shouldBe 0f
            }
        }

        "the hint reserve is 28 dp plus 16 dp per line (410 dp: two lines, 300 dp: three lines)" {
            ViewerOverlayLayout.hintLines(410f) shouldBe 2
            ViewerOverlayLayout.hintReserveDp(410f) shouldBe 60f
            ViewerOverlayLayout.hintLines(300f) shouldBe 3
            ViewerOverlayLayout.hintReserveDp(300f) shouldBe 76f
        }

        "the top left badge keeps only the normal margin at the bottom" {
            ViewerOverlayLayout.badge(900f, true).bottomInsetDp shouldBe 12f
            ViewerOverlayLayout.badge(300f, false).bottomInsetDp shouldBe 12f
        }

        "badge, hint and panel never overlap for viewers of 300 to 1200 dp (height 400 and up, panel up to 240 dp)" {
            val l = ViewerOverlayLayout
            for (h in listOf(400f, 700f)) {
                for (w in 300..1200 step 10) {
                    val wf = w.toFloat()
                    for (panel in listOf(true, false)) {
                        for (hintShown in listOf(true, false)) {
                            val lineCounts = if (hintShown) 1..l.hintLines(wf) else 0..0
                            for (lines in lineCounts) {
                                val p = l.badge(wf, panel)
                                val rects = mutableMapOf<String, FloatArray>()
                                if (panel) rects["panel"] = floatArrayOf(wf - 12f - 260f, 12f, wf - 12f, 12f + 240f)
                                if (hintShown) {
                                    val hw = minOf(80 * 7f + 16f, wf - 24f)
                                    val hh = 8f + 16f * lines
                                    rects["hint"] = floatArrayOf((wf - hw) / 2, h - 12f - hh, (wf + hw) / 2, h - 12f)
                                }
                                val bt =
                                    if (p.corner ==
                                        BadgeCorner.TOP_START
                                    ) {
                                        12f
                                    } else {
                                        h - p.bottomInsetDp - l.BADGE_MAX_HEIGHT_DP
                                    }
                                rects["badge"] = floatArrayOf(12f, bt, 12f + p.maxWidthDp, bt + l.BADGE_MAX_HEIGHT_DP)
                                val e = rects.entries.toList()
                                for (i in e.indices) {
                                    for (j in i + 1 until e.size) {
                                        val a = e[i].value
                                        val b = e[j].value
                                        val overlap = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
                                        check(
                                            !overlap,
                                        ) { "${e[i].key} overlaps ${e[j].key} at w=$w h=$h panel=$panel lines=$lines" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    })
