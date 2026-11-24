package dev.kstep.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.menu.ToolbarButtonState
import dev.kstep.desktop.menu.toolbarTooltip
import dev.kstep.desktop.menu.stateDescription as selectedDescription

internal val TOOLBAR_BUTTON_SIZE: Dp = 32.dp
internal val TOOLBAR_ICON_SIZE: Dp = 18.dp
internal val TOOLBAR_SPACING: Dp = 4.dp
private const val SELECTED_ALPHA = 0.16f
private const val TOOLTIP_ALPHA = 0.8f

/** The tooltip below its anchor, centred; the 4 dp gap comes from the outer padding of the tooltip box. */
@OptIn(ExperimentalFoundationApi::class)
private fun tooltipBelow(): TooltipPlacement =
    TooltipPlacement.ComponentRect(
        anchor = Alignment.BottomCenter,
        alignment = Alignment.BottomCenter,
        offset = DpOffset(0.dp, 0.dp),
    )

/** The one tooltip look of the toolbar: names a button, or says why a disabled control is disabled. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolbarTooltipArea(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Box(Modifier.padding(4.dp)) {
                Box(
                    Modifier
                        .background(Color.Black.copy(alpha = TOOLTIP_ALPHA), RoundedCornerShape(4.dp))
                        .padding(6.dp),
                ) {
                    Text(text, color = Color.White, fontSize = 12.sp)
                }
            }
        },
        tooltipPlacement = tooltipBelow(),
        modifier = modifier,
    ) { content() }
}

/**
 * An icon-only toolbar button (ADR-0079): 32 dp target, 18 dp icon, a tooltip that names it (and, when disabled, says
 * why), and a tinted background while a toggle is on. The tooltip area is the outer element, so it also shows over a
 * disabled button.
 */
@Composable
internal fun IconTooltipButton(
    state: ToolbarButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolbarTooltipArea(toolbarTooltip(state), modifier) {
        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier
                .size(TOOLBAR_BUTTON_SIZE)
                .clip(shape)
                .background(
                    if (state.selected == true) {
                        MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_ALPHA)
                    } else {
                        Color.Transparent
                    },
                    shape,
                ),
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                IconButton(
                    onClick = onClick,
                    enabled = state.enabled,
                    modifier =
                        Modifier
                            .size(TOOLBAR_BUTTON_SIZE)
                            .semantics {
                                contentDescription = state.id.toolbarName
                                state.selected?.let { stateDescription = selectedDescription(it) }
                            },
                ) {
                    // The painter is already tinted (and dimmed when disabled): no second tint here.
                    val painter = menuIcon(state.id.icon, state.enabled)
                    if (painter != null) {
                        Icon(
                            painter = painter,
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(TOOLBAR_ICON_SIZE),
                        )
                    }
                }
            }
        }
    }
}
