package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val TAG_VIEWER_BADGE = "viewer.badge"
private const val BADGE_MAX_LINES = 3

/**
 * The badge of an outdated result (ADR-0094). It sits top left, or bottom left when the inspection panel leaves too
 * little room (see [ViewerOverlayLayout]); the text wraps instead of being cut. Pure layout: it takes no input.
 */
@Composable
internal fun BadgeBox(
    text: String,
    panelShown: Boolean,
    /** Reports the bounds of the badge (tests only). */
    probe: ((Rect) -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val placement = ViewerOverlayLayout.badge(maxWidth.value, panelShown)
        val align = if (placement.corner == BadgeCorner.TOP_START) Alignment.TopStart else Alignment.BottomStart
        Row(
            Modifier
                .align(align)
                .padding(ViewerOverlayLayout.MARGIN_DP.dp)
                .widthIn(max = placement.maxWidthDp.dp)
                .testTag(TAG_VIEWER_BADGE)
                .let { if (probe == null) it else it.onGloballyPositioned { c -> probe(c.boundsInRoot()) } }
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(RectangleShape)
                    .background(WARN),
            )
            Text(
                text,
                color = Color.White,
                fontSize = 12.sp,
                softWrap = true,
                maxLines = BADGE_MAX_LINES,
                modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp),
            )
        }
    }
}
