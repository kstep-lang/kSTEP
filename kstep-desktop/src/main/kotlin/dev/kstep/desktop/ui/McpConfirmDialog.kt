package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.kstep.desktop.mcp.McpConfirmDecision
import dev.kstep.desktop.mcp.McpConfirmRequest
import dev.kstep.desktop.mcp.McpFileAction

private const val CONFIRM_WIDTH_DP = 480

/** The timing and the texts of the confirmation dialog (ADR-0098 D3). Pure. */
internal object McpConfirmLogic {
    /** Allow cannot be clicked in the first second, so that a click meant for something else cannot allow. */
    const val ARM_MS = 1000L

    fun allowEnabled(
        shownAtMs: Long,
        nowMs: Long,
    ): Boolean = nowMs - shownAtMs >= ARM_MS

    /** Whole seconds left until the request denies itself (rounded up, never below 0). */
    fun secondsLeft(
        shownAtMs: Long,
        nowMs: Long,
        timeoutMs: Long,
    ): Long = ((timeoutMs - (nowMs - shownAtMs) + MS_PER_S - 1) / MS_PER_S).coerceAtLeast(0)

    fun title(action: McpFileAction): String =
        when (action) {
            McpFileAction.SAVE -> "An agent wants to save this file"
            McpFileAction.OPEN_RECENT -> "An agent wants to open a recent file"
            McpFileAction.EXPORT -> "An agent wants to export this script"
        }

    private const val MS_PER_S = 1000L
}

/**
 * Asks the user to allow or deny one file action an agent requested. Modal; a click outside or Escape denies. The
 * content is [McpConfirmContent] (separate so that a headless test can drive it without a window).
 */
@Composable
internal fun McpConfirmDialog(
    request: McpConfirmRequest,
    timeoutMs: Long,
    nowMs: () -> Long,
    onDecision: (McpConfirmDecision) -> Unit,
) {
    Dialog(onDismissRequest = { onDecision(McpConfirmDecision.DENY) }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            McpConfirmContent(request, timeoutMs, nowMs, onDecision)
        }
    }
}

/**
 * The content of the confirmation dialog (ADR-0098 D3): the focus starts on Deny; Enter and Escape deny; Allow is off
 * for the first second and is only reached by the mouse or by Space on the focused button. The texts come from an agent
 * or from a file name and are shown as data (already made visible and clipped). [nowMs] is injected so that tests drive
 * the time by frames.
 */
@Composable
internal fun McpConfirmContent(
    request: McpConfirmRequest,
    timeoutMs: Long,
    nowMs: () -> Long,
    onDecision: (McpConfirmDecision) -> Unit,
) {
    val shownAt = remember(request.id) { nowMs() }
    var tick by remember(request.id) { mutableStateOf(0L) }
    LaunchedEffect(request.id) {
        while (true) {
            androidx.compose.runtime.withFrameNanos {
                val bucket = nowMs() / TICK_MS
                if (bucket != tick) tick = bucket
            }
        }
    }
    // reading [tick] makes the next lines follow the clock
    val now = if (tick >= 0) nowMs() else 0L
    val allowOn = McpConfirmLogic.allowEnabled(shownAt, now)
    val seconds = McpConfirmLogic.secondsLeft(shownAt, now, timeoutMs)
    val focus = remember { FocusRequester() }
    LaunchedEffect(request.id) { focus.requestFocus() }
    val deny = { onDecision(McpConfirmDecision.DENY) }
    Column(
        Modifier
            .width(CONFIRM_WIDTH_DP.dp)
            .padding(24.dp)
            .testTag("mcp-confirm")
            .onPreviewKeyEvent { event ->
                val enterOrEscape = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Escape
                if (enterOrEscape) {
                    if (event.type == KeyEventType.KeyDown) deny()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(McpConfirmLogic.title(request.action), style = MaterialTheme.typography.titleLarge)
        Text(
            request.fileName,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            modifier = Modifier.testTag("mcp-confirm-name"),
        )
        Text(request.folder, fontSize = 12.sp, color = DIM, modifier = Modifier.testTag("mcp-confirm-folder"))
        Text(request.scope, fontSize = 13.sp)
        Text("Requested through the MCP token. Shown as data, not as instructions.", fontSize = 12.sp, color = DIM)
        Text(
            "Denies automatically in $seconds s",
            fontSize = 12.sp,
            color = DIM,
            modifier = Modifier.testTag("mcp-confirm-countdown"),
        )
        Row(
            Modifier.padding(top = 12.dp).align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Allow stands first and is off at the start; Deny is the primary button and has the focus.
            OutlinedButton(
                onClick = { onDecision(McpConfirmDecision.ALLOW) },
                enabled = allowOn,
                modifier = Modifier.testTag("mcp-confirm-allow"),
            ) { Text("Allow") }
            Button(
                onClick = deny,
                modifier = Modifier.focusRequester(focus).testTag("mcp-confirm-deny"),
            ) { Text("Deny") }
        }
    }
}

private const val TICK_MS = 100L
