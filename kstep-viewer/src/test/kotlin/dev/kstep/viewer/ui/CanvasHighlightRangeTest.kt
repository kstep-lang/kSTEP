package dev.kstep.viewer.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The highlight can cover a whole range of parts, e.g. an assembly group (ADR-0054). */
class CanvasHighlightRangeTest :
    StringSpec({
        // triangles 0..5 belong to parts 0,0,1,1,2,2
        val map = intArrayOf(0, 0, 1, 1, 2, 2)
        val accent = doubleArrayOf(0.0, 0.0, 1.0)

        "without a range the highlight is exactly one part" {
            val h = CanvasHighlight(map, selected = 1, hovered = -1, accentRgb = accent)
            h.tintFor(2) shouldBe CanvasHighlight.DEFAULT_SELECT_T
            h.tintFor(0) shouldBe 0.0
            h.tintFor(4) shouldBe 0.0
        }

        "a selected range tints every part in it" {
            val h = CanvasHighlight(map, selected = 1, hovered = -1, accentRgb = accent, selectedLast = 2)
            h.tintFor(0) shouldBe 0.0
            h.tintFor(3) shouldBe CanvasHighlight.DEFAULT_SELECT_T
            h.tintFor(5) shouldBe CanvasHighlight.DEFAULT_SELECT_T
        }

        "a hovered range tints less than a selected one, selection wins on overlap" {
            val h =
                CanvasHighlight(
                    map,
                    selected = 0,
                    hovered = 0,
                    accentRgb = accent,
                    selectedLast = 0,
                    hoveredLast = 2,
                )
            h.tintFor(0) shouldBe CanvasHighlight.DEFAULT_SELECT_T
            h.tintFor(2) shouldBe CanvasHighlight.DEFAULT_HOVER_T
        }

        "the none marker -1 never matches a triangle" {
            val h = CanvasHighlight(map, selected = -1, hovered = -1, accentRgb = accent)
            (0..5).all { h.tintFor(it) == 0.0 } shouldBe true
            h.tintFor(99) shouldBe 0.0
        }
    })
