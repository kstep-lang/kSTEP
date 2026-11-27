package dev.kstep.desktop.ui

import dev.kstep.viewer.ui.VIEWER_CONTROLS_HINT

/** The corner of the viewer in which the "last good result" badge is drawn (ADR-0094). */
internal enum class BadgeCorner { TOP_START, BOTTOM_START }

/** Where the badge goes and how wide it may get, in dp. */
internal data class BadgePlacement(
    val corner: BadgeCorner,
    val maxWidthDp: Float,
    /** Space kept free below the badge: the margin at the top, the controls-hint reserve at the bottom (ADR-0094 D3d-2). */
    val bottomInsetDp: Float,
)

/**
 * Pure placement rules for the overlays of the viewer (ADR-0094): the inspection panel sits top right, so the badge must
 * never run under it. No Compose here, so the rule is testable without a scene.
 */
internal object ViewerOverlayLayout {
    const val MARGIN_DP = 12f
    const val PANEL_WIDTH_DP = 260f
    const val GAP_DP = 8f
    const val MIN_BADGE_DP = 140f
    const val HINT_PAD_H_DP = 8f
    const val HINT_PAD_V_DP = 4f
    const val HINT_LINE_DP = 16f
    const val HINT_CHAR_DP = 7.0f
    const val BADGE_MAX_HEIGHT_DP = 56f
    private const val SIDES = 2

    /** Lines the controls hint needs at [viewerWidthDp]: greedy word wrap at an estimated [HINT_CHAR_DP] per character. */
    fun hintLines(viewerWidthDp: Float): Int {
        val textWidth = viewerWidthDp - SIDES * MARGIN_DP - SIDES * HINT_PAD_H_DP
        val perLine =
            kotlin.math
                .floor(textWidth / HINT_CHAR_DP)
                .toInt()
                .coerceAtLeast(1)
        var lines = 1
        var used = 0
        for (word in VIEWER_CONTROLS_HINT.split(' ')) {
            val len = word.length
            if (used == 0) {
                lines += (len + perLine - 1) / perLine - 1
                used = len % perLine
                if (used == 0) used = perLine
            } else if (used + 1 + len <= perLine) {
                used += 1 + len
            } else {
                lines += (len + perLine - 1) / perLine
                used = len % perLine
                if (used == 0) used = perLine
            }
        }
        return lines.coerceAtLeast(1)
    }

    /** Height kept free at the bottom for the controls hint (outer margin, padding, lines, gap); a stable place, no measuring at run time. */
    fun hintReserveDp(viewerWidthDp: Float): Float =
        MARGIN_DP + SIDES * HINT_PAD_V_DP + hintLines(viewerWidthDp) * HINT_LINE_DP + GAP_DP

    /**
     * The badge stays top left while it keeps at least [MIN_BADGE_DP] next to the panel (a viewer of 432 dp or more); below
     * that it moves to the bottom left, where the panel never is.
     */
    fun badge(
        viewerWidthDp: Float,
        panelShown: Boolean,
    ): BadgePlacement {
        val full = (viewerWidthDp - SIDES * MARGIN_DP).coerceAtLeast(0f)
        if (!panelShown) return BadgePlacement(BadgeCorner.TOP_START, full, MARGIN_DP)
        val available = (viewerWidthDp - SIDES * MARGIN_DP - PANEL_WIDTH_DP - GAP_DP).coerceAtLeast(0f)
        return if (available >= MIN_BADGE_DP) {
            BadgePlacement(BadgeCorner.TOP_START, available, MARGIN_DP)
        } else {
            BadgePlacement(BadgeCorner.BOTTOM_START, full, hintReserveDp(viewerWidthDp))
        }
    }
}
