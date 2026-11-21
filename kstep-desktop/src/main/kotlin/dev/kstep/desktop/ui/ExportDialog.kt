package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.cli.DesktopExportWorker
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportPathText
import dev.kstep.desktop.export.ExportPhase
import dev.kstep.desktop.export.ExportState
import dev.kstep.desktop.export.ExportText
import dev.kstep.desktop.export.Overwrite
import dev.kstep.desktop.run.SandboxNotice

private const val DIALOG_WIDTH_DP = 480

/** What the dialog needs to know besides the export state (all of it from the window, none of it from a worker). */
internal class ExportDialogContext(
    val scriptName: String,
    /** The editor has unsaved changes: the export runs on the saved file (the preview does too). */
    val dirty: Boolean,
    /** The preview reported "native bridge unavailable": every format needs it, so the rows are disabled. */
    val nativeUnavailable: Boolean,
    /** The save before an export failed (the export was not started). */
    val saveError: String?,
    /** Changes every second while an export runs, so the dialog redraws its elapsed time. */
    val tick: Long,
    val elapsedSince: (Long) -> Long,
)

/**
 * The export dialog (ADR-0057): format first, then the target, then a single action. Format rows are plain text rows
 * (name, extension, purpose), no icons. A replacement is confirmed inline with a destructive primary button; while the
 * export runs, one indeterminate bar, the phase and the elapsed time are shown and Esc or Cmd/Ctrl+. stops it. A failed
 * export shows its code and message here and keeps all settings.
 */
@Composable
internal fun ExportDialog(
    state: ExportState,
    context: ExportDialogContext,
    onFormat: (ExportFormat) -> Unit,
    onChoose: () -> Unit,
    onExport: (saveFirst: Boolean) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    sandboxNotice: SandboxNotice? = null,
    onScriptTarget: (Int) -> Unit = {},
) {
    val running = state as? ExportState.Running
    AlertDialog(
        onDismissRequest = { if (running != null) onStop() else onClose() },
        modifier =
            Modifier.width(DIALOG_WIDTH_DP.dp).onPreviewKeyEvent { event ->
                val stop = event.key == Key.Period && (event.isMetaPressed || event.isCtrlPressed)
                if (stop && running != null && event.type == KeyEventType.KeyDown) {
                    onStop()
                    true
                } else {
                    false
                }
            },
        title = { Text("Export ${context.scriptName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is ExportState.Configuring -> ConfiguringBody(state, context, onFormat, onChoose, onScriptTarget)
                    is ExportState.Running -> RunningBody(state, context)
                    is ExportState.Succeeded ->
                        Text(ExportText.successLine(state.fileName, state.bytes), fontSize = 13.sp)
                }
                sandboxNotice?.let { SandboxMark(it, DIM) }
            }
        },
        confirmButton = {
            when (state) {
                is ExportState.Configuring -> ConfiguringActions(state, context, onExport, onClose)
                is ExportState.Running ->
                    OutlinedButton(
                        onClick = onStop,
                        enabled =
                            state.phase != dev.kstep.desktop.export.ExportPhase.WRITING,
                    ) {
                        Text("Stop")
                    }
                is ExportState.Succeeded -> TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

@Composable
private fun ConfiguringBody(
    state: ExportState.Configuring,
    context: ExportDialogContext,
    onFormat: (ExportFormat) -> Unit,
    onChoose: () -> Unit,
    onScriptTarget: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScriptTargetsRow(state, onScriptTarget)
        Text("Format", fontSize = 12.sp, color = DIM)
        ExportFormat.ALL.forEach { format ->
            val enabled = !context.nativeUnavailable
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.format == format, onClick = { onFormat(format) }, enabled = enabled)
                Column(Modifier.padding(start = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            format.label,
                            fontSize = 13.sp,
                            color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else DIM,
                        )
                        Text(
                            format.extension,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = DIM,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(format.purpose, fontSize = 12.sp, color = DIM)
                }
            }
        }
        if (context.nativeUnavailable) {
            Text("Requires the native geometry bridge (OCCT), which is not available.", fontSize = 12.sp, color = WARN)
        }
        Text("Save to", fontSize = 12.sp, color = DIM, modifier = Modifier.padding(top = 8.dp))
        val label = state.target?.let(ExportPathText::label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label == null) {
                Text(
                    "No target chosen",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f)) {
                    WithTip(label.full) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                label.fileName,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                label.folder,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = DIM,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onChoose) { Text("Choose…", fontSize = 13.sp) }
        }
        (state.overwrite as? Overwrite.NeedsConfirm)?.let { overwrite ->
            Text(
                ExportText.overwriteLine(
                    state.target
                        ?.fileName
                        ?.toString()
                        .orEmpty(),
                    overwrite.bytes,
                ),
                fontSize = 12.sp,
                color = WARN,
            )
        }
        if (context.dirty) {
            Text("Preview and export use the saved file.", fontSize = 12.sp, color = DIM)
        }
        state.notice?.let { Text(it, fontSize = 12.sp, color = DIM) }
        (state.error?.let { ExportText.errorLine(it) } ?: context.saveError)?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The names the script suggested (ADR-0063): one muted line for a single name, a row of plain text buttons for two to
 * four. Nothing is drawn without an `export { }` block, so the dialog of an ordinary script is unchanged.
 */
@Composable
private fun ScriptTargetsRow(
    state: ExportState.Configuring,
    onScriptTarget: (Int) -> Unit,
) {
    val targets = state.scriptTargets
    when {
        targets.isEmpty() -> Unit
        targets.size == 1 ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From script: ", fontSize = 12.sp, color = DIM)
                Text(targets[0].fileName, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = DIM)
            }
        else -> {
            Text("From script", fontSize = 12.sp, color = DIM)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                targets.forEachIndexed { index, target ->
                    TextButton(onClick = { onScriptTarget(index) }) {
                        Text(
                            target.fileName,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            color =
                                if (state.selectedScriptTarget == index) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    DIM
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfiguringActions(
    state: ExportState.Configuring,
    context: ExportDialogContext,
    onExport: (saveFirst: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val replace = state.overwrite is Overwrite.NeedsConfirm
    val ready = state.target != null && !context.nativeUnavailable
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) { Text("Cancel") }
        if (context.dirty) {
            OutlinedButton(onClick = { onExport(false) }, enabled = ready) { Text("Export Saved Version") }
        }
        Button(
            onClick = { onExport(context.dirty) },
            enabled = ready,
            colors =
                if (replace) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
        ) {
            Text(
                when {
                    replace && context.dirty -> "Save and Replace"
                    replace -> "Replace"
                    context.dirty -> "Save and Export"
                    else -> "Export"
                },
            )
        }
    }
}

@Composable
private fun RunningBody(
    state: ExportState.Running,
    context: ExportDialogContext,
) {
    val fraction =
        if (state.phase ==
            ExportPhase.WRITING
        ) {
            ExportText.progressFraction(state.writtenBytes, state.totalBytes)
        } else {
            null
        }
    val line =
        ExportText.stepLine(
            state.phase,
            context.elapsedSince(state.startedAtMs),
            DesktopExportWorker.EXPORT_TIMEOUT_MILLIS,
            state.writtenBytes,
            state.totalBytes,
        )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val barModifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = line
                progressBarRangeInfo =
                    if (fraction != null) ProgressBarRangeInfo(fraction, 0f..1f) else ProgressBarRangeInfo.Indeterminate
            }
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = barModifier)
        } else {
            LinearProgressIndicator(modifier = barModifier)
        }
        Text(line, fontSize = 13.sp, style = TextStyle(fontFeatureSettings = "tnum"))
        Text(
            state.target.fileName
                ?.toString()
                .orEmpty(),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = DIM,
        )
    }
}
