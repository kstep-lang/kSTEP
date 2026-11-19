package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.ai.extract.DisplayText
import dev.kstep.desktop.ai.keys.KeySource
import dev.kstep.desktop.ai.provider.ProviderCatalog
import dev.kstep.desktop.aiwire.AiUiState
import dev.kstep.desktop.aiwire.KeyStatus
import dev.kstep.desktop.aiwire.SandboxStatus

private const val CONSENT_PREVIEW_LINES = 6

/** What the user agrees to before anything is sent (ADR-0056). "Cancel" is the default button: Enter and Esc cancel. */
@Composable
internal fun AiConsentDialog(
    ui: AiUiState,
    prompt: String,
    scriptLines: Int?,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val preview = DisplayText.sanitize(prompt).lines()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Send to ${ui.provider.displayName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    preview.take(CONSENT_PREVIEW_LINES).joinToString("\n") +
                        if (preview.size > CONSENT_PREVIEW_LINES) "\n…" else "",
                    fontSize = 13.sp,
                )
                Text(
                    if (scriptLines !=
                        null
                    ) {
                        "Current script: included ($scriptLines lines)"
                    } else {
                        "Current script: not included"
                    },
                    fontSize = 13.sp,
                )
                Text("Model: ${ui.model.displayName}  ·  Host: ${ui.provider.host}", fontSize = 13.sp)
                Text(consentSandboxText(ui.sandbox), fontSize = 12.sp, color = DIM)
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send to ${ui.provider.displayName}") } },
        dismissButton = { Button(onClick = onCancel, modifier = Modifier.focusRequester(focus)) { Text("Cancel") } },
    )
}

/** The last paragraph of the consent dialog: what is and is not run on this computer (ADR-0059). */
internal fun consentSandboxText(status: SandboxStatus): String =
    "No API keys, file paths or other files are sent by kSTEP. " +
        when (status) {
            SandboxStatus.AVAILABLE ->
                "The generated script is run on this computer to check it, before you have read it, in a sandbox " +
                    "without network access that can write only to its session folder. It can still read your files."
            SandboxStatus.UNAVAILABLE ->
                "The generated script is not run on this system (no worker sandbox available), so it is not " +
                    "checked before you read it."
            SandboxStatus.CHECKING ->
                "The generated script is run to check it only inside a worker sandbox; kSTEP is still checking " +
                    "whether this system has one."
        }

/** Provider, model and key source; the key is typed into a masked field and never shown again. */
@Composable
internal fun AiProviderDialog(
    ui: AiUiState,
    onModel: (String) -> Unit,
    onSource: (KeySource) -> Unit,
    onSaveKey: (CharArray, KeySource) -> Unit,
    onRemoveKey: () -> Unit,
    onRevokeConsent: () -> Unit,
    onClose: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val source = ui.settings.keySource
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("AI provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Provider: ${ui.provider.displayName} (${ui.provider.host})", fontSize = 13.sp)
                Text("Model", fontSize = 12.sp, color = DIM)
                ProviderCatalog.models(ui.provider).forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = m.id == ui.model.id, onClick = { onModel(m.id) })
                        Text(m.displayName, fontSize = 13.sp)
                    }
                }
                Text("API key source", fontSize = 12.sp, color = DIM, modifier = Modifier.padding(top = 6.dp))
                SourceRow(
                    "System keychain" + (ui.keychainName?.let { " ($it)" } ?: ""),
                    source == KeySource.KEYCHAIN,
                    ui.keychainName != null,
                    "No system keychain available - key is kept for this session only",
                ) { onSource(KeySource.KEYCHAIN) }
                SourceRow("This session only", source == KeySource.SESSION, true, null) { onSource(KeySource.SESSION) }
                SourceRow(
                    "Environment variable ${ui.provider.envVar}",
                    source == KeySource.ENVIRONMENT,
                    true,
                    null,
                ) { onSource(KeySource.ENVIRONMENT) }
                if (source == KeySource.ENVIRONMENT && System.getProperty("os.name").orEmpty().startsWith("Linux")) {
                    Text(
                        "On Linux the live preview runs your own scripts without a sandbox; they can read this " +
                            "variable from the kSTEP process.",
                        fontSize = 11.sp,
                        color = DIM,
                    )
                }
                if (source != KeySource.ENVIRONMENT) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = typed.isNotBlank(),
                            onClick = {
                                onSaveKey(typed.toCharArray(), source)
                                typed = ""
                            },
                        ) { Text("Save key") }
                        TextButton(onClick = onRemoveKey) { Text("Remove key") }
                    }
                }
                Text(
                    when (ui.keyStatus) {
                        KeyStatus.PRESENT -> "Key available (${sourceName(source)})"
                        KeyStatus.MISSING ->
                            if (source ==
                                KeySource.ENVIRONMENT
                            ) {
                                "Variable not set"
                            } else {
                                "No key stored"
                            }
                        KeyStatus.UNKNOWN -> "Key status unknown"
                    },
                    fontSize = 12.sp,
                    color = DIM,
                )
                ui.notice?.let { Text(it, fontSize = 12.sp, color = WARN) }
                if (ui.hasConsent) TextButton(onClick = onRevokeConsent) { Text("Revoke consent") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

private fun sourceName(s: KeySource) =
    when (s) {
        KeySource.KEYCHAIN -> "keychain"
        KeySource.SESSION -> "session"
        KeySource.ENVIRONMENT -> "environment"
    }

@Composable
private fun SourceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    disabledReason: String?,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.width(380.dp)) {
            Text(label, fontSize = 13.sp, color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else DIM)
            if (!enabled && disabledReason != null) Text(disabledReason, fontSize = 11.sp, color = DIM)
        }
    }
}
