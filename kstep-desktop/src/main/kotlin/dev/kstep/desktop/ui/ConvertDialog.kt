@file:Suppress("ktlint:standard:filename")

package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.cli.ConvertIssue
import dev.kstep.cli.ConvertTarget
import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.convert.ConvertController
import dev.kstep.desktop.convert.ConvertPreferencesStore
import dev.kstep.desktop.convert.ConvertPrefill
import dev.kstep.desktop.convert.ConvertRequest
import dev.kstep.desktop.convert.ConvertState
import dev.kstep.desktop.convert.ConvertText
import kotlinx.coroutines.flow.StateFlow
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

private const val LIST_MAX_DP = 200

/**
 * What the convert dialog keeps besides the controller state (ADR-0078): whether it is shown, the title and tag text,
 * and whether the source is a folder. It owns the controller, so the window only needs one call and one action branch.
 * The dialog calls the controller's begin, edit, back, closeDialog, start and replace, and nothing else.
 */
internal class ConvertHost(
    private val controller: ConvertController,
    private val store: ConvertPreferencesStore,
    private val window: Frame,
) {
    val state: StateFlow<ConvertState> get() = controller.state

    var visible by mutableStateOf(false)
        private set
    var titleText by mutableStateOf("")
        private set
    var tagsText by mutableStateOf("")
        private set
    var sourceIsDir by mutableStateOf(false)
        private set

    /** The request the dialog starts with, from the open script, the workspace and the remembered choices. */
    fun prefill(
        openScript: Path?,
        workspaceRoot: Path?,
    ): ConvertRequest = ConvertPrefill.resolve(openScript, workspaceRoot, store.load())

    fun open(prefill: ConvertRequest) {
        if (visible) {
            window.toFront()
            window.requestFocus()
            return
        }
        titleText = ""
        tagsText = ""
        sourceIsDir = prefill.source?.let { Files.isDirectory(it) } ?: false
        controller.begin(prefill)
        visible = true
    }

    fun close() {
        if (controller.state.value is ConvertState.Running) return
        controller.closeDialog()
        visible = false
    }

    fun closeController() = controller.close()

    fun back() = controller.back()

    fun start() = controller.start()

    fun replaceConfirmed() = controller.replace()

    /** Any change of the request goes through here so title, tags and folder rules are applied in one place. */
    fun change(
        request: ConvertRequest,
        titleChange: String = titleText,
        tagsChange: String = tagsText,
    ) {
        titleText = titleChange
        tagsText = tagsChange
        controller.edit(ConvertText.effectiveRequest(request, request.source, sourceIsDir, titleText, tagsText))
    }

    /** A new source: direction, folder rule and recursion are decided together, in one edit. */
    fun chooseSource(
        current: ConvertRequest,
        path: Path,
        isDirectory: Boolean,
    ) {
        sourceIsDir = isDirectory
        titleText = ""
        tagsText = ""
        val target = ConvertText.deriveTarget(path.fileName?.toString().orEmpty(), isDirectory)
        controller.edit(
            ConvertText.effectiveRequest(
                current.copy(source = path, target = target, recursive = isDirectory),
                path,
                isDirectory,
                titleText,
                tagsText,
            ),
        )
    }

    fun chooseFile(current: ConvertRequest) {
        chooseSourceFile(window)?.let { chooseSource(current, it, isDirectory = false) }
    }

    fun chooseFolder(current: ConvertRequest) {
        chooseFolderDialog(window, "Choose a folder to convert")?.let { chooseSource(current, it, isDirectory = true) }
    }

    fun chooseOut(current: ConvertRequest) {
        chooseFolderDialog(window, "Choose the output folder")?.let { change(current.copy(outDir = it)) }
    }
}

@Composable
internal fun rememberConvertHost(
    window: Frame,
    store: ConvertPreferencesStore = remember { ConvertPreferencesStore() },
): ConvertHost {
    val host =
        remember(window, store) {
            ConvertHost(
                ConvertController(onDone = { request ->
                    store.remember(request.outDir, request.target)
                }),
                store,
                window,
            )
        }
    DisposableEffect(host) { onDispose { host.closeController() } }
    return host
}

/** Draws the dialog while it is visible and the controller has a state to show. */
@Composable
internal fun ConvertHost.ConvertDialogHost() {
    val current by state.collectAsState()
    if (!visible) return
    val s = current
    if (s is ConvertState.Idle) return
    ConvertDialog(this, s)
}

@Composable
private fun ConvertDialog(
    host: ConvertHost,
    state: ConvertState,
) {
    val cancelFocus = remember { FocusRequester() }
    if (state is ConvertState.Conflict) {
        LaunchedEffect(state.generation) { cancelFocus.requestFocus() }
    }
    val enter: () -> Boolean =
        {
            when (state) {
                is ConvertState.Editing -> if (state.request.source != null) host.start()
                is ConvertState.Conflict -> host.back()
                is ConvertState.Done -> host.close()
                is ConvertState.Failed -> host.back()
                is ConvertState.Running, is ConvertState.Idle -> Unit
            }
            true
        }
    val escape: () -> Boolean =
        {
            when (state) {
                is ConvertState.Conflict -> host.back()
                is ConvertState.Running, is ConvertState.Idle -> Unit
                is ConvertState.Editing, is ConvertState.Done, is ConvertState.Failed -> host.close()
            }
            true
        }
    AlertDialog(
        onDismissRequest = {
            when (state) {
                is ConvertState.Conflict -> host.back()
                is ConvertState.Running, is ConvertState.Idle -> Unit
                is ConvertState.Editing, is ConvertState.Done, is ConvertState.Failed -> host.close()
            }
        },
        modifier =
            Modifier.width(DIALOG_WIDTH_DP.dp).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> enter()
                        Key.Escape -> escape()
                        else -> false
                    }
                }
            },
        title = { Text(ConvertText.title(state)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is ConvertState.Editing -> EditingBody(host, state)
                    is ConvertState.Running -> RunningBody()
                    is ConvertState.Conflict -> ConflictBody(state)
                    is ConvertState.Done -> DoneBody(state)
                    is ConvertState.Failed -> FailedBody(state)
                    is ConvertState.Idle -> Unit
                }
            }
        },
        dismissButton = {
            when (state) {
                is ConvertState.Editing -> TextButton(onClick = { host.close() }) { Text(ConvertText.CANCEL) }
                is ConvertState.Conflict ->
                    TextButton(
                        onClick = { host.replaceConfirmed() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(ConvertText.replaceLabel(state.allConflicts))
                    }
                is ConvertState.Done -> TextButton(onClick = { host.back() }) { Text(ConvertText.BACK) }
                is ConvertState.Failed -> TextButton(onClick = { host.close() }) { Text(ConvertText.CLOSE) }
                is ConvertState.Running, is ConvertState.Idle -> Unit
            }
        },
        confirmButton = {
            when (state) {
                is ConvertState.Editing ->
                    Button(onClick = { host.start() }, enabled = state.request.source != null) {
                        Text(ConvertText.CONVERT)
                    }
                is ConvertState.Running -> Button(onClick = {}, enabled = false) { Text(ConvertText.CONVERTING) }
                is ConvertState.Conflict ->
                    Button(onClick = { host.back() }, modifier = Modifier.focusRequester(cancelFocus)) {
                        Text(ConvertText.CANCEL)
                    }
                is ConvertState.Done -> Button(onClick = { host.close() }) { Text(ConvertText.CLOSE) }
                is ConvertState.Failed -> Button(onClick = { host.back() }) { Text(ConvertText.BACK) }
                is ConvertState.Idle -> Unit
            }
        },
    )
}

@Composable
private fun EditingBody(
    host: ConvertHost,
    state: ConvertState.Editing,
) {
    val request = state.request
    val source = request.source
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            source?.let { DisplayName.escaped(it.toString()) } ?: ConvertText.SOURCE_PLACEHOLDER,
            fontSize = 13.sp,
            color = if (source == null) DIM else MaterialTheme.colorScheme.onSurface,
            softWrap = true,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { host.chooseFile(request) }) { Text(ConvertText.CHOOSE_FILE) }
        OutlinedButton(onClick = { host.chooseFolder(request) }) { Text(ConvertText.CHOOSE_FOLDER) }
    }
    for (target in listOf(ConvertTarget.KTS, ConvertTarget.OKF)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = request.target == target,
                onClick = { host.change(request.copy(target = target)) },
            )
            Column {
                Text(ConvertText.directionLabel(target), fontSize = 13.sp)
                Text(ConvertText.directionSubtitle(target), fontSize = 11.sp, color = DIM)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = request.outDir == null, onClick = { host.change(request.copy(outDir = null)) })
        Text(ConvertText.OUT_SAME, fontSize = 13.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = request.outDir != null,
            onClick = { if (request.outDir == null) host.chooseOut(request) },
        )
        Column(Modifier.weight(1f)) {
            Text(ConvertText.OUT_OTHER, fontSize = 13.sp)
            request.outDir?.let {
                Text(
                    DisplayName.escaped(it.toString()),
                    fontSize = 11.sp,
                    color = DIM,
                    softWrap = true,
                )
            }
        }
        OutlinedButton(onClick = { host.chooseOut(request) }) { Text(ConvertText.CHOOSE_OUT) }
    }
    if (host.sourceIsDir) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = request.recursive,
                onCheckedChange = { host.change(request.copy(recursive = it)) },
            )
            Text(ConvertText.INCLUDE_SUBFOLDERS, fontSize = 13.sp)
        }
    }
    if (request.target == ConvertTarget.OKF && !host.sourceIsDir) {
        OutlinedTextField(
            value = host.titleText,
            onValueChange = { host.change(request, titleChange = it) },
            label = { Text(ConvertText.TITLE_LABEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host.tagsText,
            onValueChange = { host.change(request, tagsChange = it) },
            label = { Text(ConvertText.TAGS_LABEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.error?.let { Text(it.text, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
}

@Composable
private fun RunningBody() {
    Text(ConvertText.CONVERTING, fontSize = 13.sp)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NameList(
    heading: String,
    names: List<String>,
    more: Int = 0,
) {
    if (names.isEmpty()) return
    Text(heading, fontSize = 12.sp, color = DIM)
    Column(Modifier.heightIn(max = LIST_MAX_DP.dp).verticalScroll(rememberScrollState())) {
        for (name in names) Text(name, fontSize = 13.sp, softWrap = true, modifier = Modifier.padding(vertical = 1.dp))
        ConvertText.more(more)?.let { Text(it, fontSize = 12.sp, color = DIM) }
    }
}

private fun issueLines(issues: List<ConvertIssue>): List<String> = issues.map { "${it.code} ${it.message}" }

@Composable
private fun ConflictBody(state: ConvertState.Conflict) {
    Text("Nothing was written yet. Replacing overwrites these files:", fontSize = 13.sp)
    Column(Modifier.heightIn(max = LIST_MAX_DP.dp).verticalScroll(rememberScrollState())) {
        for (name in state.shown) {
            Text(
                name,
                fontSize = 13.sp,
                softWrap = true,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
        ConvertText.more(state.more)?.let { Text(it, fontSize = 12.sp, color = DIM) }
    }
}

@Composable
private fun DoneBody(state: ConvertState.Done) {
    NameList(
        ConvertText.SECTION_WRITTEN,
        state.written.take(ConvertState.MAX_SHOWN).map { it.name },
        state.written.size - ConvertState.MAX_SHOWN,
    )
    NameList(
        ConvertText.SECTION_SKIPPED,
        state.skipped
            .take(
                ConvertState.MAX_SHOWN,
            ).map { "${it.source}: ${ConvertText.skipReason(it.reason, it.line)}" },
        state.skipped.size - ConvertState.MAX_SHOWN,
    )
    NameList(
        ConvertText.SECTION_WARNINGS,
        issueLines(state.warnings.take(ConvertState.MAX_SHOWN)),
        state.warnings.size - ConvertState.MAX_SHOWN,
    )
}

@Composable
private fun FailedBody(state: ConvertState.Failed) {
    NameList(ConvertText.SECTION_ERRORS, issueLines(state.errors), state.moreErrors)
    NameList(
        ConvertText.SECTION_ALREADY_WRITTEN,
        state.written.take(ConvertState.MAX_SHOWN).map { it.name },
        state.written.size - ConvertState.MAX_SHOWN,
    )
    NameList(
        ConvertText.SECTION_WARNINGS,
        issueLines(state.warnings.take(ConvertState.MAX_SHOWN)),
        state.warnings.size - ConvertState.MAX_SHOWN,
    )
}

/** A native chooser for one note or script; null if cancelled. The name filter is a convenience, not a check. */
private fun chooseSourceFile(parent: Frame): Path? {
    if (isMac()) {
        val dialog = FileDialog(parent, "Choose a note or a kSTEP script", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> ConvertText.isConvertSource(name) }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return Path.of(dialog.directory ?: "", name)
    }
    val chooser = javax.swing.JFileChooser()
    chooser.fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
    chooser.dialogTitle = "Choose a note or a kSTEP script"
    chooser.fileFilter =
        object : javax.swing.filechooser.FileFilter() {
            override fun accept(f: java.io.File): Boolean = f.isDirectory || ConvertText.isConvertSource(f.name)

            override fun getDescription(): String = "Notes (.md) and kSTEP scripts (.kstep.kts)"
        }
    return if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.toPath()
    } else {
        null
    }
}
