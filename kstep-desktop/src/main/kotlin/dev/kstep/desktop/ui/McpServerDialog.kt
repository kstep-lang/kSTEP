package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.mcp.DesktopMcpServerManager
import dev.kstep.desktop.mcp.McpDialogText
import dev.kstep.desktop.mcp.mcpDialogState
import dev.kstep.desktop.mcp.parsePortInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MCP_DIALOG_WIDTH_DP = 520
private const val COPIED_MS = 1500L

/**
 * The dialog of the built-in MCP server (ADR-0095). Closing it (Esc or Close) leaves the server as it is. The token is
 * never shown; the command preview contains the literal `<token>`. Copying is always a click, and the clipboard is
 * never cleared by the application.
 */
@Composable
internal fun McpServerDialog(
    manager: DesktopMcpServerManager,
    onClose: () -> Unit,
) {
    val status by manager.status.collectAsState()
    val settings by manager.settingsState.collectAsState()
    val saveFailed by manager.lastSaveFailed.collectAsState()
    val hasToken by manager.hasToken.collectAsState()
    val allowFiles by manager.allowFiles.collectAsState()
    val state = mcpDialogState(status, settings, hasToken, saveFailed, allowFiles)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<String?>(null) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var regenerateFailed by remember { mutableStateOf(false) }
    var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var portInvalid by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPIED_MS)
            copied = null
        }
    }
    val commitPort = {
        val parsed = parsePortInput(portText)
        if (parsed == null) {
            portInvalid = true
        } else {
            portInvalid = false
            manager.setPort(parsed)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.width(MCP_DIALOG_WIDTH_DP.dp),
        title = { Text(McpDialogText.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(checked = settings.enabled, onCheckedChange = { manager.setEnabled(it) })
                    Column {
                        Text(McpDialogText.SWITCH, fontSize = 14.sp)
                        Text(state.statusText, fontSize = 12.sp, color = DIM)
                        state.warningText?.let { Text(it, fontSize = 12.sp, color = DIM) }
                    }
                }
                Row(
                    Modifier.padding(start = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = state.actionsChecked,
                        enabled = state.actionsEnabled,
                        onCheckedChange = { manager.setAllowActions(it) },
                    )
                    Column {
                        Text(McpDialogText.ACTIONS_SWITCH, fontSize = 14.sp)
                        Text(McpDialogText.ACTIONS_SUBTITLE, fontSize = 12.sp, color = DIM)
                        state.actionsReason?.let { Text(it, fontSize = 12.sp, color = DIM) }
                    }
                }
                Row(
                    Modifier.padding(start = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = state.filesChecked,
                        enabled = state.filesEnabled,
                        onCheckedChange = { manager.setAllowFiles(it) },
                    )
                    Column {
                        Text(McpDialogText.FILES_SWITCH, fontSize = 14.sp)
                        Text(McpDialogText.FILES_SUBTITLE, fontSize = 12.sp, color = DIM)
                        state.filesReason?.let { Text(it, fontSize = 12.sp, color = DIM) }
                    }
                }
                state.useActualPortLabel?.let { label ->
                    TextButton(onClick = { manager.adoptActualPort() }) { Text(label) }
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = state.portEditable,
                    isError = portInvalid,
                    supportingText = {
                        val reason = if (portInvalid) McpDialogText.PORT_RANGE else state.portReason
                        if (reason != null) Text(reason, fontSize = 12.sp)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused && state.portEditable) commitPort() }
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter &&
                                    event.type == KeyEventType.KeyDown &&
                                    state.portEditable
                                ) {
                                    commitPort()
                                    true
                                } else {
                                    false
                                }
                            },
                )
                Text(state.hintText, fontSize = 12.sp, color = DIM)
                // not wrapped in a SelectionContainer: the preview cannot be selected, only copied with the buttons
                Text(
                    state.commandPreview,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
                Text("Token  ${McpDialogText.TOKEN_MASK}", fontSize = 12.sp, color = DIM)
                if (regenerateFailed) Text(McpDialogText.TOKEN_FAILED, fontSize = 12.sp, color = DIM)
                state.copyReason?.let { Text(it, fontSize = 12.sp, color = DIM) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = state.copyEnabled,
                        onClick = {
                            manager.copyCommand()?.let { text ->
                                scope.launch { if (ClipboardText.write(clipboard, text)) copied = "command" }
                            }
                        },
                    ) { Text(if (copied == "command") "Copied" else "Copy Command") }
                    OutlinedButton(
                        enabled = state.copyEnabled,
                        onClick = {
                            manager.copyToken()?.let { text ->
                                scope.launch { if (ClipboardText.write(clipboard, text)) copied = "token" }
                            }
                        },
                    ) { Text(if (copied == "token") "Copied" else "Copy Token") }
                    TextButton(
                        enabled = state.copyEnabled,
                        onClick = { confirmRegenerate = true },
                    ) { Text("Regenerate Token…") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(McpDialogText.REGEN_TITLE) },
            text = { Text(McpDialogText.REGEN_TEXT) },
            // Cancel is the primary button and the Esc target; replacing is the deliberate second choice
            confirmButton = { Button(onClick = { confirmRegenerate = false }) { Text("Cancel") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        regenerateFailed = !manager.regenerateToken()
                        confirmRegenerate = false
                    },
                ) { Text("Regenerate") }
            },
        )
    }
}
