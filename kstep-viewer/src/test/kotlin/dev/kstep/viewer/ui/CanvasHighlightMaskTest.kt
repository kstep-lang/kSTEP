package dev.kstep.viewer.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** A multi-selection is a mask over the parts, not one range (ADR-0092). */
class CanvasHighlightMaskTest :
    StringSpec({
        // triangles 0..5 belong to parts 0,0,1,1,2,2
        val map = intArrayOf(0, 0, 1, 1, 2, 2)
        val accent = doubleArrayOf(0.0, 0.0, 1.0)

        "a mask tints exactly the flagged parts, also when they are not adjacent" {
            val h =
                CanvasHighlight(
                    map,
                    selected = -1,
                    hovered = -1,
                    accentRgb = accent,
                    selectedMask = booleanArrayOf(true, false, true),
                )
            h.tintFor(0) shouldBe CanvasHighlight.DEFAULT_SELECT_T
            h.tintFor(2) shouldBe 0.0
            h.tintFor(4) shouldBe CanvasHighlight.DEFAULT_SELECT_T
        }

        "the mask is checked before the range and a short mask never fails" {
            val h =
                CanvasHighlight(
                    map,
                    selected = 1,
                    hovered = -1,
                    accentRgb = accent,
                    selectedMask = booleanArrayOf(true),
                )
            h.tintFor(0) shouldBe CanvasHighlight.DEFAULT_SELECT_T
            h.tintFor(2) shouldBe CanvasHighlight.DEFAULT_SELECT_T
            h.tintFor(4) shouldBe 0.0
        }

        "a mask part that is also hovered shows the selection tint" {
            val h =
                CanvasHighlight(
                    map,
                    selected = -1,
                    hovered = 0,
                    accentRgb = accent,
                    hoveredLast = 2,
                    selectedMask = booleanArrayOf(false, true, false),
                )
            h.tintFor(2) shouldBe CanvasHighlight.DEFAULT_SELECT_T
            h.tintFor(0) shouldBe CanvasHighlight.DEFAULT_HOVER_T
        }

        "without a mask nothing changes" {
            val h = CanvasHighlight(map, selected = 1, hovered = -1, accentRgb = accent)
            h.selectedMask shouldBe null
            h.tintFor(2) shouldBe CanvasHighlight.DEFAULT_SELECT_T
        }
    })
