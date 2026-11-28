package dev.kstep.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.mcp.McpActivity
import dev.kstep.desktop.mcp.McpPhase
import dev.kstep.desktop.run.SandboxNotice
import dev.kstep.desktop.status.FittedStatus
import dev.kstep.desktop.status.PreviewOriginLabel
import dev.kstep.desktop.status.StatusSegment
import kotlinx.coroutines.delay

internal const val TAG_STATUSBAR = "statusbar"
internal const val TAG_MCP_INDICATOR = "mcp.indicator"
private const val FLASH_MS = 400L
private const val PULSE_MS = 700
private val STATUS_BAR_HEIGHT = 24.dp

/** What the MCP indicator needs (ADR-0099); `null` for the indicator means the server is not running. */
internal data class McpIndicatorModel(
    val port: Int,
    val editable: Boolean,
    val files: Boolean,
    val activity: McpActivity,
)

/**
 * The status bar at the bottom of the window (ADR-0099): the file name, export line, sandbox notice, run status and
 * preview origin on the left (the former toolbar status line), the MCP indicator on the right.
 */
@Composable
internal fun StatusBar(
    fileName: String?,
    exportText: String?,
    onShowInFolder: () -> Unit,
    sandboxNotice: SandboxNotice?,
    statusSegments: List<StatusSegment>,
    previewLabel: PreviewOriginLabel?,
    accent: Color,
    mcp: McpIndicatorModel?,
    onMcpClick: () -> Unit,
    fitProbe: ((FittedStatus) -> Unit)? = null,
    probe: ((String, Rect) -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(STATUS_BAR_HEIGHT)
            .background(MaterialTheme.colorScheme.surface)
            .semantics { isTraversalGroup = true }
            .testTag(TAG_STATUSBAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            StatusLine(
                fileName = fileName,
                exportText = exportText,
                onShowInFolder = onShowInFolder,
                sandboxNotice = sandboxNotice,
                statusSegments = statusSegments,
                fitProbe = fitProbe,
                probe = probe,
                previewLabel = previewLabel,
                accent = accent,
            )
        }
        if (mcp != null) {
            Spacer(Modifier.widthIn(min = 16.dp))
            McpIndicator(
                mcp,
                accent,
                onMcpClick,
                Modifier.wrapContentWidth().padding(end = 8.dp).tagged(TAG_MCP_INDICATOR, probe),
            )
        }
    }
}

/** A text link of the status bar: focusable, Enter, Space, underline on hover, accent focus ring (ADR-0099). */
@Composable
internal fun StatusBarLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val accent = if (isSystemInDarkTheme()) ACCENT_DARK else ACCENT_LIGHT
    Box(
        modifier
            .height(STATUS_BAR_HEIGHT)
            .padding(horizontal = 6.dp)
            .hoverable(source)
            .then(if (focused) Modifier.border(1.dp, accent, RoundedCornerShape(3.dp)) else Modifier)
            .onPreviewKeyEvent {
                val activates = it.key == Key.Enter || it.key == Key.NumPadEnter || it.key == Key.Spacebar
                if (activates && it.type == KeyEventType.KeyUp) onClick()
                activates
            }.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .focusable(interactionSource = source),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = accent,
            softWrap = false,
            maxLines = 1,
            textDecoration = if (hovered) TextDecoration.Underline else null,
        )
    }
}

/**
 * The MCP indicator (ADR-0099): a dot and the word "MCP" (with "edit" or "files" when agents may change things). The dot
 * pulses while a call runs, turns red when the last call failed and flashes once when a call ends. The tooltip lists the
 * last calls; a click opens the MCP dialog.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun McpIndicator(
    model: McpIndicatorModel,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = model.activity
    var seenFinished by remember { mutableStateOf(activity.finished) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(activity.finished) {
        // a first composition with calls already counted shows nothing
        if (activity.finished != seenFinished) {
            seenFinished = activity.finished
            flash = true
            delay(FLASH_MS)
            flash = false
        }
    }
    val dark = isSystemInDarkTheme()
    val warn = warningTextColor(dark)
    val dot = mcpDotColor(activity.phase, flash, dark, accent, MaterialTheme.colorScheme.error)
    val label =
        if (model.files) {
            "MCP files"
        } else if (model.editable) {
            "MCP edit"
        } else {
            "MCP"
        }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    TooltipArea(
        tooltip = {
            Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                Text(
                    McpTooltipText.of(model.port, model.editable, model.files, activity),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .height(STATUS_BAR_HEIGHT)
                .hoverable(source)
                .then(if (focused) Modifier.border(1.dp, accent, RoundedCornerShape(3.dp)) else Modifier)
                .onPreviewKeyEvent {
                    val activates = it.key == Key.Enter || it.key == Key.NumPadEnter || it.key == Key.Spacebar
                    if (activates && it.type == KeyEventType.KeyUp) onClick()
                    activates
                }.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .focusable(interactionSource = source)
                .semantics {
                    contentDescription =
                        McpTooltipText.description(model.editable, model.files, activity.phase)
                }.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pulse =
                if (activity.phase == McpPhase.Busy) {
                    val transition = rememberInfiniteTransition()
                    transition
                        .animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
                        ).value
                } else {
                    1f
                }
            Box(Modifier.size(8.dp).alpha(pulse).background(dot, CircleShape))
            Text(
                label,
                fontSize = 12.sp,
                color = if (model.editable || model.files) warn else DIM,
                softWrap = false,
                maxLines = 1,
                textDecoration = if (hovered) TextDecoration.Underline else null,
            )
        }
    }
}

private val READY_GREEN_LIGHT = Color(0xFF228822)
private val READY_GREEN_DARK = Color(0xFF55C155)

/**
 * The colour of the MCP dot (ADR-0099): red after a failed call, the accent colour while a call runs or just ended,
 * and green when the server is ready, as in kUML Desktop. A grey dot looked like "off".
 */
internal fun mcpDotColor(
    phase: McpPhase,
    flash: Boolean,
    dark: Boolean,
    accent: Color,
    error: Color,
): Color =
    when {
        phase == McpPhase.Failed -> error
        phase == McpPhase.Busy || flash -> accent
        dark -> READY_GREEN_DARK
        else -> READY_GREEN_LIGHT
    }
