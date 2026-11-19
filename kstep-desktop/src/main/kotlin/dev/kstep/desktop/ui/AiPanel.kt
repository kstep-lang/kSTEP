package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.ai.extract.DisplayText
import dev.kstep.desktop.ai.repair.AiLimits
import dev.kstep.desktop.ai.repair.NotVerifiedReason
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.ai.repair.Verdict
import dev.kstep.desktop.aiwire.AiController
import dev.kstep.desktop.aiwire.AiUiState
import dev.kstep.desktop.aiwire.KeyStatus
import dev.kstep.desktop.aiwire.SandboxStatus

private const val COUNTER_FROM = 3_500
private const val PROMPT_MIN_LINES = 4
private const val PROMPT_MAX_LINES = 10
private val ATTEMPTS_MAX_HEIGHT = 160.dp

private fun elapsedSeconds(
    nowMs: Long,
    startedMs: Long,
): Long = (nowMs - startedMs).coerceAtLeast(0) / 1000

/** The one-line AI status shown in the toolbar (ADR-0056); a pure function of the repair state. */
internal fun aiStatusText(
    repair: RepairState,
    nowMs: Long,
    maxAttempts: Int = AiLimits().maxAttempts,
): String =
    when (repair) {
        RepairState.Idle -> ""
        is RepairState.Requesting ->
            "AI: attempt ${repair.attempt}/$maxAttempts · ${elapsedSeconds(nowMs, repair.ctx.startedMs)} s"
        is RepairState.Compiling ->
            "AI: attempt ${repair.attempt}/$maxAttempts · ${elapsedSeconds(nowMs, repair.ctx.startedMs)} s"
        is RepairState.Proposal -> "AI: proposal ready"
        is RepairState.Failed -> "AI: failed (${repair.code})"
        RepairState.Stopped -> "AI: stopped"
    }

/**
 * The AI panel (ADR-0056): prompt, one Generate/Stop button, the attempt list and the proposal as a diff. Nothing is
 * applied without "Apply"; there is no "Apply & Run".
 */
@Composable
internal fun AiPanel(
    controller: AiController,
    ui: AiUiState,
    repair: RepairState,
    editorText: String,
    hasFile: Boolean,
    focusNonce: Int,
    onApply: (String) -> Unit,
    onEscapeToEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var prompt by remember { mutableStateOf("") }
    var includeScript by remember { mutableStateOf(false) }
    var consentAsk by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var providerDialog by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusNonce) { if (focusNonce > 0) runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) { controller.refresh() }
    val limits = controller.limitsInUse
    val scriptTooBig = editorText.toByteArray(Charsets.UTF_8).size > limits.maxIncludedScriptBytes
    val running = repair is RepairState.Requesting || repair is RepairState.Compiling
    val noKey = ui.keyStatus == KeyStatus.MISSING
    val blockReason =
        when {
            !hasFile -> "Open a .kstep.kts script first"
            prompt.isBlank() -> "Write what the script should do"
            prompt.length > limits.maxPromptChars -> "The prompt is too long"
            noKey -> "Set up an API key first"
            else -> null
        }
    val go = {
        val ask = ui.hasConsent.not()
        val withScript = includeScript && !scriptTooBig
        if (ask) {
            consentAsk = prompt to withScript
        } else {
            controller.generate(prompt, editorText, withScript)
        }
    }

    Column(
        modifier.padding(12.dp).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                if (running) controller.stop() else onEscapeToEditor()
                true
            } else {
                false
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui.keyStatus != KeyStatus.PRESENT && !ui.hasConsent && repair == RepairState.Idle) {
            Text("No AI provider set up. The editor and preview work without it.", fontSize = 13.sp, color = DIM)
            Button(onClick = { providerDialog = true }) { Text("Set up provider…") }
        } else {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it.take(limits.maxPromptChars + 1) },
                label = { Text("What should the script do?") },
                minLines = PROMPT_MIN_LINES,
                maxLines = PROMPT_MAX_LINES,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                supportingText = {
                    if (prompt.length >= COUNTER_FROM) Text("${prompt.length} / ${limits.maxPromptChars}")
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = includeScript && !scriptTooBig,
                    onCheckedChange = { includeScript = it },
                    enabled = !scriptTooBig,
                )
                Text(
                    if (scriptTooBig) "Include current script (too large to send)" else "Include current script",
                    fontSize = 13.sp,
                )
            }
            Text(
                "Sends to ${ui.provider.host} · ${ui.model.displayName} · script: " +
                    if (includeScript && !scriptTooBig) "on" else "off",
                fontSize = 11.sp,
                color = DIM,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    Button(onClick = { controller.stop() }, modifier = Modifier.weight(1f)) { Text("Stop") }
                } else {
                    Button(
                        onClick = go,
                        enabled = blockReason == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Generate") }
                }
                TextButton(onClick = { providerDialog = true }) { Text("Provider…") }
            }
            if (!running && blockReason != null && prompt.isNotBlank()) Text(blockReason, fontSize = 11.sp, color = DIM)
            ui.notice?.let { Text(it, fontSize = 12.sp, color = WARN) }
            Result(controller, repair, editorText, limits.maxAttempts, ui.sandbox, onApply)
        }
    }

    consentAsk?.let { (p, withScript) ->
        AiConsentDialog(
            ui,
            p,
            if (withScript) editorText.lines().size else null,
            onSend = {
                consentAsk = null
                controller.grantConsent()
                controller.generate(p, editorText, withScript)
            },
            onCancel = { consentAsk = null },
        )
    }
    if (providerDialog) {
        AiProviderDialog(
            ui,
            onModel = controller::setModel,
            onSource = controller::setKeySource,
            onSaveKey = { k, s -> controller.saveKey(k, s) },
            onRemoveKey = controller::removeKey,
            onRevokeConsent = controller::revokeConsent,
            onClose = { providerDialog = false },
        )
    }
}

@Composable
private fun Result(
    controller: AiController,
    repair: RepairState,
    editorText: String,
    maxAttempts: Int,
    sandbox: SandboxStatus,
    onApply: (String) -> Unit,
) {
    when (repair) {
        is RepairState.Requesting -> Attempts(repair.attempts.map { it.text }, "asking the model…", maxAttempts)
        is RepairState.Compiling ->
            Attempts(
                repair.attempts.map { it.text },
                if (sandbox == SandboxStatus.AVAILABLE) {
                    "checking the script in the worker (sandbox: no network, writes only to the session folder)…"
                } else {
                    "preparing the check…"
                },
                maxAttempts,
            )
        is RepairState.Failed -> {
            Attempts(repair.attempts.map { it.text }, null, maxAttempts)
            Text("${repair.code}: ${DisplayText.sanitize(repair.message)}", fontSize = 12.sp, color = WARN)
        }
        is RepairState.Proposal -> {
            Attempts(repair.attempts.map { it.text }, null, maxAttempts)
            val head =
                when (repair.verdict) {
                    Verdict.COMPILES -> "Proposal · compiles ✓ (attempt ${repair.attemptsUsed}/$maxAttempts)"
                    Verdict.DOES_NOT_COMPILE -> "Proposal · does not compile (last attempt)"
                    Verdict.NOT_VERIFIED ->
                        if (repair.notVerifiedReason == NotVerifiedReason.SANDBOX_UNAVAILABLE) {
                            "Proposal · not checked (no worker sandbox on this system)"
                        } else {
                            "Proposal · not verified (geometry engine unavailable)"
                        }
                }
            Text(
                head,
                fontSize = 13.sp,
                color =
                    if (repair.verdict == Verdict.DOES_NOT_COMPILE ||
                        repair.notVerifiedReason == NotVerifiedReason.SANDBOX_UNAVAILABLE
                    ) {
                        WARN
                    } else {
                        Color.Unspecified
                    },
            )
            if (repair.notVerifiedReason == NotVerifiedReason.SANDBOX_UNAVAILABLE) {
                Text(
                    "kSTEP does not run AI scripts without a sandbox. Read the diff before applying.",
                    fontSize = 11.sp,
                    color = DIM,
                )
            }
            if (editorText != repair.baseText) {
                Text("Editor changed since request: the diff shows the current text.", fontSize = 11.sp, color = WARN)
            }
            ProposalDiffView(
                editorText,
                repair.script,
                Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp).background(Color.Black.copy(alpha = 0.04f)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onApply(repair.script)
                    controller.discard()
                }) { Text("Apply") }
                TextButton(onClick = controller::discard) { Text("Discard") }
            }
        }
        RepairState.Stopped -> Text("Stopped.", fontSize = 12.sp, color = DIM)
        RepairState.Idle -> Unit
    }
}

@Composable
private fun Attempts(
    lines: List<String>,
    running: String?,
    maxAttempts: Int,
) {
    // A bounded height is required: the panel itself is vertically scrollable, and a scrollable child measured with an
    // infinite max height throws.
    Column(Modifier.fillMaxWidth().heightIn(max = ATTEMPTS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
        lines.forEachIndexed {
            i,
            l,
            ->
            Text("Attempt ${i + 1}/$maxAttempts – ${DisplayText.sanitize(l)}", fontSize = 12.sp, color = DIM)
        }
        running?.let { Text("Attempt ${lines.size + 1}/$maxAttempts – $it", fontSize = 12.sp) }
    }
}
