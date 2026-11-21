package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.ai.diff.HunkPlan
import dev.kstep.desktop.ai.extract.DisplayText
import dev.kstep.desktop.ai.repair.AiLimits
import dev.kstep.desktop.ai.repair.NotVerifiedReason
import dev.kstep.desktop.ai.repair.ProposalOrigin
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.ai.repair.Verdict
import dev.kstep.desktop.aiwire.AiController
import dev.kstep.desktop.aiwire.AiUiState
import dev.kstep.desktop.aiwire.KeyStatus
import dev.kstep.desktop.aiwire.SandboxStatus
import dev.kstep.desktop.recent.HistoryNav
import dev.kstep.desktop.recent.PromptHistoryCursor

private const val COUNTER_FROM = 3_500
private const val PROMPT_MIN_LINES = 4
private const val PROMPT_MAX_LINES = 10
private val ATTEMPTS_MAX_HEIGHT = 160.dp
private val DIFF_BG = Color.Black.copy(alpha = 0.04f)
private const val HISTORY_LABEL_CHARS = 80

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
        is RepairState.CheckingSelection -> "AI: checking ${repair.selected.size} of ${repair.total} changes"
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
    onApplyIf: (expected: String, text: String) -> Boolean,
    onEscapeToEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var promptField by remember { mutableStateOf(TextFieldValue("")) }
    val prompt = promptField.text
    var nav by remember { mutableStateOf(HistoryNav()) }
    var historyMenu by remember { mutableStateOf(false) }
    val historyEntries by controller.history.collectAsState()
    var includeScript by remember { mutableStateOf(false) }
    var consentAsk by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var providerDialog by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusNonce) { if (focusNonce > 0) runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) { controller.refresh() }
    val limits = controller.limitsInUse
    val scriptTooBig = editorText.toByteArray(Charsets.UTF_8).size > limits.maxIncludedScriptBytes
    val running =
        repair is RepairState.Requesting || repair is RepairState.Compiling || repair is RepairState.CheckingSelection
    // The group selection lives out here, not in the `when`: it must survive Proposal -> Checking -> Proposal (Stop).
    val modelProposal =
        when (repair) {
            is RepairState.Proposal -> repair.original ?: repair
            is RepairState.CheckingSelection -> repair.original
            else -> null
        }
    val plan = if (modelProposal != null) rememberHunkPlan(editorText, modelProposal.script) else null
    // Tied to the plan itself: during the debounce the old plan is still shown, and a choice made on it must not
    // survive into the recomputed plan.
    var selected by remember(modelProposal?.script, editorText, plan) { mutableStateOf<Set<Int>?>(null) }
    val shownPlan =
        if (repair is RepairState.Proposal && repair.origin == ProposalOrigin.SELECTION) {
            rememberHunkPlan(editorText, repair.script)
        } else {
            plan
        }
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
                value = promptField,
                onValueChange = {
                    val text = it.text.take(limits.maxPromptChars + 1)
                    if (it.text != prompt) nav = HistoryNav() // typing starts a new draft; caret moves keep the place
                    promptField =
                        if (text.length == it.text.length) {
                            it
                        } else {
                            TextFieldValue(text, TextRange(minOf(it.selection.end, text.length)))
                        }
                },
                label = { Text("What should the script do?") },
                minLines = PROMPT_MIN_LINES,
                maxLines = PROMPT_MAX_LINES,
                trailingIcon =
                    if (historyEntries.isEmpty()) {
                        null
                    } else {
                        {
                            Box {
                                TextButton(onClick = { historyMenu = true }) { Text("↺") }
                                DropdownMenu(expanded = historyMenu, onDismissRequest = { historyMenu = false }) {
                                    historyEntries.forEach { entry ->
                                        DropdownMenuItem(
                                            text = { Text(historyLabel(entry), fontSize = 12.sp) },
                                            onClick = {
                                                historyMenu = false
                                                promptField = TextFieldValue(entry, TextRange(entry.length))
                                                nav = HistoryNav()
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Clear history", fontSize = 12.sp) },
                                        onClick = {
                                            historyMenu = false
                                            controller.clearHistory()
                                        },
                                    )
                                }
                            }
                        }
                    },
                modifier =
                    Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown || !promptField.selection.collapsed) {
                            return@onPreviewKeyEvent false
                        }
                        val caret = promptField.selection.end
                        val step =
                            when {
                                e.key == Key.DirectionUp && PromptHistoryCursor.isFirstLine(prompt, caret) ->
                                    PromptHistoryCursor.up(nav, historyEntries, prompt)
                                e.key == Key.DirectionDown && PromptHistoryCursor.isLastLine(prompt, caret) ->
                                    PromptHistoryCursor.down(nav, historyEntries)
                                else -> null
                            }
                        if (step == null) {
                            false
                        } else {
                            promptField = TextFieldValue(step.second, TextRange(step.second.length))
                            nav = step.first
                            true
                        }
                    },
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
            Result(
                controller,
                repair,
                editorText,
                limits.maxAttempts,
                ui.sandbox,
                plan,
                shownPlan,
                selected,
                { selected = it },
                onApply,
                onApplyIf,
            )
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

/** One line for the history menu: no line breaks, at most 80 characters. */
private fun historyLabel(entry: String): String {
    val one = DisplayText.sanitize(entry).replace("\n", " ⏎ ").replace("\t", " ")
    return if (one.length > HISTORY_LABEL_CHARS) one.take(HISTORY_LABEL_CHARS - 1) + "…" else one
}

@Composable
private fun Result(
    controller: AiController,
    repair: RepairState,
    editorText: String,
    maxAttempts: Int,
    sandbox: SandboxStatus,
    modelPlan: HunkPlan?,
    shownPlan: HunkPlan?,
    selectedOrNull: Set<Int>?,
    onSelected: (Set<Int>?) -> Unit,
    onApply: (String) -> Unit,
    onApplyIf: (String, String) -> Boolean,
) {
    val readyPlan = modelPlan as? HunkPlan.Ready
    val allIds =
        readyPlan
            ?.hunks
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    val selected = selectedOrNull?.intersect(allIds) ?: allIds
    val choosable = readyPlan != null && readyPlan.hunks.size >= 2
    when (repair) {
        is RepairState.CheckingSelection -> {
            Attempts(repair.original.attempts.map { it.text }, null, maxAttempts)
            Text(
                "Checking ${repair.selected.size} of ${repair.total} changes in the worker…",
                fontSize = 13.sp,
            )
            ProposalDiffView(
                shownPlan,
                selected,
                selectable = choosable,
                locked = true,
                onToggle = {},
                onToggleAll = {},
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp).background(DIFF_BG),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {},
                    enabled = false,
                ) { Text("Checking ${repair.selected.size} of ${repair.total} changes…") }
                TextButton(onClick = controller::stop) { Text("Stop") }
            }
        }
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
            if (repair.back != null) TextButton(onClick = controller::backToProposal) { Text("Back to proposal") }
        }
        is RepairState.Proposal -> {
            Attempts(repair.attempts.map { it.text }, null, maxAttempts)
            val fromSelection = repair.origin == ProposalOrigin.SELECTION
            val head =
                when (repair.verdict) {
                    Verdict.COMPILES -> "Proposal · compiles ✓ (attempt ${repair.attemptsUsed}/$maxAttempts)"
                    Verdict.DOES_NOT_COMPILE ->
                        if (fromSelection) {
                            "Your selection does not compile"
                        } else {
                            "Proposal · does not compile (last attempt)"
                        }
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
            val selectable = !fromSelection && choosable && sandbox == SandboxStatus.AVAILABLE
            ProposalDiffView(
                shownPlan,
                if (fromSelection) emptySet() else selected,
                selectable = selectable,
                locked = false,
                onToggle = { id -> onSelected(if (id in selected) selected - id else selected + id) },
                onToggleAll = { onSelected(if (selected.size == allIds.size) emptySet() else null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp).background(DIFF_BG),
            )
            if (!fromSelection && choosable && sandbox != SandboxStatus.AVAILABLE) {
                Text("Choosing single changes needs the worker sandbox.", fontSize = 11.sp, color = DIM)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val subset = !fromSelection && selectable && selected.size < allIds.size
                when {
                    fromSelection ->
                        Button(onClick = { controller.applyChecked(repair, onApplyIf) }) { Text("Apply anyway") }
                    subset && selected.isEmpty() -> Button(onClick = {}, enabled = false) { Text("Apply") }
                    subset ->
                        Button(onClick = {
                            controller.applySelection(repair, readyPlan!!, selected, onApplyIf)
                        }) { Text("Apply ${selected.size} of ${allIds.size} changes") }
                    else ->
                        Button(onClick = {
                            onApply(repair.script)
                            controller.discard()
                        }) { Text("Apply") }
                }
                if (fromSelection) TextButton(onClick = controller::backToProposal) { Text("Back to proposal") }
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
