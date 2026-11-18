package dev.kstep.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.pick.VisibilityCounts
import dev.kstep.desktop.pick.VisibilityState

private val MUTED = Color(0xFF8A8A8A)
private const val ALPHA_MIN = 0.05f
private const val ALPHA_MAX = 0.6f
private const val ALPHA_STEPS = 10 // 12 positions: 5 %, 10 %, ... 60 %
private const val PERCENT_PER_STEP = 5

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
 * Show all / Isolate / Hide and the opacity of transparent parts (ADR-0055). Isolate and Hide need a selection, Show all
 * needs something hidden or transparent, and the opacity needs a transparent part; a disabled control says why.
 */
@Composable
internal fun VisibilityControls(
    counts: VisibilityCounts,
    hasSelection: Boolean,
    alphaStep: Int,
    onShowAll: () -> Unit,
    onIsolate: () -> Unit,
    onHide: () -> Unit,
    onAlpha: (Float) -> Unit,
) {
    val nothingChanged = counts.hidden == 0 && counts.transparent == 0
    WithTip(if (nothingChanged) "Nothing is hidden or transparent" else null) {
        TextButton(onClick = onShowAll, enabled = !nothingChanged) { Text("Show all", fontSize = 13.sp) }
    }
    WithTip(if (hasSelection) null else "Select a part first") {
        TextButton(onClick = onIsolate, enabled = hasSelection) { Text("Isolate", fontSize = 13.sp) }
    }
    WithTip(if (hasSelection) null else "Select a part first") {
        TextButton(onClick = onHide, enabled = hasSelection) { Text("Hide", fontSize = 13.sp) }
    }
    val noTransparent = counts.transparent == 0
    WithTip(if (noTransparent) "No transparent parts" else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = alphaStep * VisibilityState.ALPHA_UNIT,
                onValueChange = onAlpha,
                modifier =
                    Modifier
                        .width(96.dp)
                        .height(32.dp)
                        .semantics { contentDescription = "Opacity of transparent parts" },
                enabled = !noTransparent,
                valueRange = ALPHA_MIN..ALPHA_MAX,
                steps = ALPHA_STEPS,
            )
            Text(
                "Opacity ${alphaStep * PERCENT_PER_STEP} %",
                fontSize = 12.sp,
                color = if (noTransparent) MUTED.copy(alpha = 0.6f) else MUTED,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            )
        }
    }
}
