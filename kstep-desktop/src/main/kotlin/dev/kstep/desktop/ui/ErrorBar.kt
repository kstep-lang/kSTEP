package dev.kstep.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.ErrorBarText
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private val STRIPE = Color(0xFFC62828)
private val BACKGROUND_LIGHT = Color(0xFFFDECEA)
private val BACKGROUND_DARK = Color(0xFF3B1F1F)
private val WARNING_BACKGROUND_LIGHT = Color(0xFFFFF4D6)
private val WARNING_BACKGROUND_DARK = Color(0xFF3A3017)

/**
 * The error bar (ADR-0051): collapsed it is one 32 dp line with the first error (code, line:column, message), a
 * "+N more" toggle and "Copy"; expanded it lists every error, at most [maxHeight] high. A red stripe on the left, the
 * code and position in monospace. Never covers the canvas: it sits below it. With [warning] it uses the amber warning
 * colours of the editor markers instead (e.g. an invalid part list, ADR-0053).
 */
@Composable
fun ErrorBar(
    errors: List<DesktopError>,
    dark: Boolean,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    warning: Boolean = false,
    onSelect: (DesktopError) -> Unit = {},
    /** The entry whose line is marked in the editor (ADR-0067); drawn with a stronger background. */
    selected: DesktopError? = null,
) {
    var expanded by remember(errors) { mutableStateOf(initiallyExpanded) }
    val textColor =
        when {
            warning -> warningTextColor(dark)
            dark -> Color(0xFFFFDAD6)
            else -> Color(0xFF410002)
        }
    // The stripe is drawn behind the content (no IntrinsicSize: the expanded list must stay measurable).
    Box(
        modifier
            .fillMaxWidth()
            .background(
                when {
                    warning && dark -> WARNING_BACKGROUND_DARK
                    warning -> WARNING_BACKGROUND_LIGHT
                    dark -> BACKGROUND_DARK
                    else -> BACKGROUND_LIGHT
                },
            ).drawBehind { drawRect(if (warning) MARK_WARNING else STRIPE, size = Size(4.dp.toPx(), size.height)) }
            .padding(start = 4.dp),
    ) {
        if (!expanded) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val first = errors.firstOrNull()
                EntryText(
                    ErrorBarText.displayHeadline(errors),
                    first,
                    selected != null && first == selected,
                    textColor,
                    dark,
                    onSelect,
                    Modifier.weight(1f),
                    singleLine = true,
                )
                ErrorBarText.moreLabel(errors)?.let { label ->
                    Text(
                        label,
                        fontSize = 12.sp,
                        color = textColor,
                        modifier = Modifier.clickable { expanded = true }.padding(horizontal = 8.dp),
                    )
                }
                CopyButton(errors)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                    errors.forEach { error ->
                        EntryText(
                            ErrorBarText.displayLine(error),
                            error,
                            selected != null && error == selected,
                            textColor,
                            dark,
                            onSelect,
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            singleLine = false,
                        )
                    }
                }
                Row {
                    TextButton(onClick = { expanded = false }) { Text("Collapse", fontSize = 12.sp) }
                    CopyButton(errors)
                }
            }
        }
    }
}

/**
 * One entry: clickable only when the error has a line (ADR-0067); otherwise it explains why with a tooltip. The marked
 * entry gets a stronger background.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryText(
    text: String,
    error: DesktopError?,
    marked: Boolean,
    textColor: Color,
    dark: Boolean,
    onSelect: (DesktopError) -> Unit,
    modifier: Modifier,
    singleLine: Boolean,
) {
    val clickable = error?.line != null && error != null
    val tint =
        Modifier.background(
            if (marked) textColor.copy(alpha = if (dark) 0.28f else 0.16f) else Color.Transparent,
        )
    val body: @Composable (Modifier) -> Unit = { m ->
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = textColor,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = m,
        )
    }
    if (clickable && error != null) {
        body(modifier.then(tint).pointerHoverIcon(PointerIcon.Hand).clickable { onSelect(error) })
    } else {
        TooltipArea(
            tooltip = {
                Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 4.dp) {
                    Text("No position known", fontSize = 12.sp, modifier = Modifier.padding(6.dp))
                }
            },
            delayMillis = 500,
            modifier = modifier,
        ) { body(Modifier.fillMaxWidth().then(tint)) }
    }
}

@Composable
private fun CopyButton(errors: List<DesktopError>) {
    TextButton(
        onClick = {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(
                StringSelection(ErrorBarText.copyText(errors)),
                null,
            )
        },
    ) {
        Text("Copy", fontSize = 12.sp)
    }
}

/** The text colour on amber warnings (error bar, status line notice), light and dark (ADR-0062). */
internal fun warningTextColor(dark: Boolean): Color = if (dark) Color(0xFFFFE9B0) else Color(0xFF3D2A00)
