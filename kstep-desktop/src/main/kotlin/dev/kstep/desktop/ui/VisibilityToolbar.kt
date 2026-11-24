package dev.kstep.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.menu.MenuContext
import dev.kstep.desktop.menu.MenuIconId
import dev.kstep.desktop.menu.ToolbarModel
import dev.kstep.desktop.menu.hideToolbarState
import dev.kstep.desktop.menu.isolateToolbarState
import dev.kstep.desktop.menu.opacityTooltip
import dev.kstep.desktop.menu.showAllToolbarState
import dev.kstep.desktop.pick.VisibilityCounts
import dev.kstep.desktop.pick.VisibilityState

private const val ALPHA_MIN = 0.05f
private const val ALPHA_MAX = 0.6f
private const val ALPHA_STEPS = 10 // 12 positions: 5 %, 10 %, ... 60 %

/** The tooltip of a control that may be disabled: it also appears over a disabled button, which says why. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithTip(
    tip: String?,
    content: @Composable () -> Unit,
) {
    if (tip == null) {
        content()
    } else {
        TooltipArea(
            tooltip = {
                Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                    Text(tip, color = Color.White, fontSize = 12.sp)
                }
            },
        ) { content() }
    }
}

/**
 * Show all / Isolate / Hide and the opacity of transparent parts (ADR-0055, ADR-0079). The button states come from the
 * same rules as the View menu; a disabled control says why in its tooltip.
 */
@Composable
internal fun VisibilityControls(
    ctx: MenuContext,
    counts: VisibilityCounts,
    alphaStep: Int,
    onShowAll: () -> Unit,
    onIsolate: () -> Unit,
    onHide: () -> Unit,
    onAlpha: (Float) -> Unit,
) {
    IconTooltipButton(showAllToolbarState(ctx), onShowAll)
    IconTooltipButton(isolateToolbarState(ctx), onIsolate)
    IconTooltipButton(hideToolbarState(ctx), onHide)
    val noTransparent = counts.transparent == 0
    ToolbarTooltipArea(opacityTooltip(alphaStep, counts.transparent)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = menuIcon(MenuIconId.TRANSPARENT, !noTransparent)!!,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(TOOLBAR_ICON_SIZE).clearAndSetSemantics {},
            )
            Slider(
                value = alphaStep * VisibilityState.ALPHA_UNIT,
                onValueChange = onAlpha,
                modifier =
                    Modifier
                        .width(80.dp)
                        .height(TOOLBAR_BUTTON_SIZE)
                        .semantics {
                            contentDescription = "Opacity of transparent parts"
                            stateDescription = "${alphaStep * ToolbarModel.PERCENT_PER_STEP} %"
                        },
                enabled = !noTransparent,
                valueRange = ALPHA_MIN..ALPHA_MAX,
                steps = ALPHA_STEPS,
            )
        }
    }
}
