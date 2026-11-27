package dev.kstep.desktop.ui

/** The corner of the viewer in which the "last good result" badge is drawn (ADR-0094). */
internal enum class BadgeCorner { TOP_START, BOTTOM_START }

/** Where the badge goes and how wide it may get, in dp. */
internal data class BadgePlacement(
    val corner: BadgeCorner,
    val maxWidthDp: Float,
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
    private const val SIDES = 2

    /**
     * The badge stays top left while it keeps at least [MIN_BADGE_DP] next to the panel (a viewer of 432 dp or more); below
     * that it moves to the bottom left, where the panel never is.
     */
    fun badge(
        viewerWidthDp: Float,
        panelShown: Boolean,
    ): BadgePlacement {
        val full = (viewerWidthDp - SIDES * MARGIN_DP).coerceAtLeast(0f)
        if (!panelShown) return BadgePlacement(BadgeCorner.TOP_START, full)
        val available = (viewerWidthDp - SIDES * MARGIN_DP - PANEL_WIDTH_DP - GAP_DP).coerceAtLeast(0f)
        return if (available >= MIN_BADGE_DP) {
            BadgePlacement(BadgeCorner.TOP_START, available)
        } else {
            BadgePlacement(BadgeCorner.BOTTOM_START, full)
        }
    }
}
