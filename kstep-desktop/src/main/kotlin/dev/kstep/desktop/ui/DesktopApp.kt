package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.aiwire.AiController
import dev.kstep.desktop.drop.DropAction
import dev.kstep.desktop.drop.DropEvaluator
import dev.kstep.desktop.drop.DropHint
import dev.kstep.desktop.drop.DropPaths
import dev.kstep.desktop.drop.PendingNote
import dev.kstep.desktop.drop.PendingResolution
import dev.kstep.desktop.edit.CaretMapper
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.PartSourceLocator
import dev.kstep.desktop.edit.SourceLocation
import dev.kstep.desktop.export.ExportController
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportNotice
import dev.kstep.desktop.export.ExportPreferencesStore
import dev.kstep.desktop.export.ExportPrefill
import dev.kstep.desktop.export.ExportState
import dev.kstep.desktop.export.ExportText
import dev.kstep.desktop.export.Overwrite
import dev.kstep.desktop.export.ScriptExportPreference
import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.okf.KnowledgeWorkspaceController
import dev.kstep.desktop.okf.PathCheck
import dev.kstep.desktop.okf.WorkspaceEntry
import dev.kstep.desktop.okf.WorkspaceState
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.GroupInspection
import dev.kstep.desktop.pick.HoverThrottle
import dev.kstep.desktop.pick.PartMode
import dev.kstep.desktop.pick.PickIndex
import dev.kstep.desktop.pick.SelectionEvent
import dev.kstep.desktop.pick.SelectionMachine
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.ShowInScriptMessage
import dev.kstep.desktop.pick.ShowInScriptMessages
import dev.kstep.desktop.pick.TreeViewLogic
import dev.kstep.desktop.pick.TreeViewState
import dev.kstep.desktop.pick.VisibilityCounts
import dev.kstep.desktop.pick.VisibilityMachine
import dev.kstep.desktop.pick.VisibilityScene
import dev.kstep.desktop.pick.VisibilityState
import dev.kstep.desktop.pick.visibilityStatusText
import dev.kstep.desktop.recent.RecentCheck
import dev.kstep.desktop.recent.RecentEntry
import dev.kstep.desktop.recent.RecentFilesStore
import dev.kstep.desktop.recent.RecentKind
import dev.kstep.desktop.recent.RecentList
import dev.kstep.desktop.recent.RecentMessages
import dev.kstep.desktop.recent.RecentOpenCheck
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.ErrorBarText
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.SandboxNotice
import dev.kstep.desktop.run.UserSandboxState
import dev.kstep.desktop.run.sandboxNotice
import dev.kstep.desktop.settings.LayoutPreferences
import dev.kstep.desktop.settings.LayoutPreferencesStore
import dev.kstep.desktop.status.StatusLineText
import dev.kstep.geometry.TriangleMesh
import dev.kstep.viewer.camera.ViewerCameraState
import dev.kstep.viewer.ui.CanvasHighlight
import dev.kstep.viewer.ui.CanvasPicking
import dev.kstep.viewer.ui.CanvasVisibility
import dev.kstep.viewer.ui.ShapeCanvas
import dev.kstep.viewer.ui.UnavailableNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

internal val DIM = Color(0xFF8A8A8A)
internal val WARN = Color(0xFFF9A825)
private const val PROGRESS_DELAY_MS = 300L
private const val NOTICE_MS = 6_000L
private const val EMPTY_RECENT_SHOWN = 5
private const val AI_TICK_MS = 1_000L
private const val AI_PANEL_DP = 360
private const val AI_MIN_WINDOW_DP = 900
private const val ERROR_BAR_MAX_FRACTION = 0.4f
private const val SCRIPT_FILTER_SUFFIX = ".kstep.kts"
private const val MIN_PANE_DP = 240
private const val TREE_DIVIDER_DP = 1
private const val EDITOR_DIVIDER_DP = 7 // the 1 dp line and the 6 dp drag handle between editor and viewer

/**
 * The whole window content: toolbar, script editor | canvas with badge and progress line, error bar (ADR-0051,
 * ADR-0052). Opening a file and closing the window first ask about unsaved changes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopApp(
    controller: LiveRunController,
    session: EditorSession,
    initialFile: Path?,
    window: Frame,
    ai: AiController,
    exporter: ExportController,
    knowledge: KnowledgeWorkspaceController,
    userSandbox: StateFlow<UserSandboxState> = MutableStateFlow(UserSandboxState.Unknown),
) {
    val sandboxState by userSandbox.collectAsState()
    val sandboxNotice = sandboxNotice(sandboxState)
    val editorView by session.view.collectAsState()
    // Knowledge workspace (ADR-0058): a folder of notes, one fenced block previewed at a time.
    val workspace by knowledge.workspace.collectAsState()
    val blockHeader by knowledge.header.collectAsState()
    val knowledgeError by knowledge.error.collectAsState()
    val knowledgeOpen = workspace is WorkspaceState.Open
    var lastCaret by remember { mutableStateOf(0) }
    // Recent files and folders (ADR-0066): loaded off the UI thread, saved in order, checked only when clicked.
    val scope = rememberCoroutineScope()
    val recentStore = remember { RecentFilesStore() }
    var recent by remember { mutableStateOf(RecentList.empty()) }
    var recentSave by remember { mutableStateOf<Job?>(null) }
    val updateRecent: ((RecentList) -> RecentList) -> Unit = { change ->
        val next = change(recent)
        recent = next
        val before = recentSave
        recentSave =
            scope.launch(Dispatchers.IO) {
                before?.join()
                recentStore.save(next)
            }
    }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { recentStore.load() }
        // entries touched before the file was read stay in front
        recent = RecentList.of(recent.entries + loaded.entries)
    }
    var actionError by remember { mutableStateOf<DesktopError?>(null) }
    var transientNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(transientNotice) {
        if (transientNotice != null) {
            delay(NOTICE_MS)
            transientNotice = null
        }
    }
    var dragHint by remember { mutableStateOf<DropHint?>(null) }
    var pendingNote by remember { mutableStateOf<Path?>(null) }
    var pendingRoot by remember { mutableStateOf<Path?>(null) }
    var pendingSawScan by remember { mutableStateOf(false) }
    var pendingErrorBefore by remember { mutableStateOf<DesktopError?>(null) }
    // AI panel (ADR-0056): hidden by default; nothing is sent before the user asks and agrees.
    val aiUi by ai.ui.collectAsState()
    val aiRepair by ai.repair.collectAsState()
    var aiVisible by remember { mutableStateOf(false) }
    var aiFocus by remember { mutableStateOf(0) }
    var aiNow by remember { mutableStateOf(System.currentTimeMillis()) }
    val aiRunning = aiRepair is RepairState.Requesting || aiRepair is RepairState.Compiling
    LaunchedEffect(aiRunning) {
        while (aiRunning) {
            aiNow = System.currentTimeMillis()
            delay(AI_TICK_MS)
        }
    }
    // Export dialog (ADR-0057): the controller owns the run; the last format and folder live only for this session.
    val exportState by exporter.state.collectAsState()
    var exportVisible by remember { mutableStateOf(false) }
    var exportNow by remember { mutableStateOf(0L) }
    var exportSaveError by remember { mutableStateOf<String?>(null) }
    var exportNotice by remember { mutableStateOf<ExportNotice.Notice?>(null) }
    val exportPreferences = remember { ExportPreferencesStore() }
    val savedExportPreferences = remember { exportPreferences.load() }
    var lastExportFormat by remember { mutableStateOf(savedExportPreferences.lastFormat ?: ExportFormat.DEFAULT) }
    var lastExportDir by remember { mutableStateOf(savedExportPreferences.lastDir) }
    // the declared target (an `export { }` name) of the last started export, remembered per script (ADR-0070)
    var lastExportTargetName by remember { mutableStateOf<String?>(null) }
    val exportRunning = exportState is ExportState.Running
    LaunchedEffect(exportRunning) {
        while (exportRunning) {
            exportNow = System.currentTimeMillis()
            delay(AI_TICK_MS)
        }
    }
    var editorFocused by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf<EditorJump?>(null) }
    var jumpNonce by remember { mutableStateOf(0) }
    val layoutStore = remember { LayoutPreferencesStore() }
    var editorFraction by remember { mutableStateOf(layoutStore.load().editorFraction.toFloat()) }
    val dark = isSystemInDarkTheme()
    val state by controller.state.collectAsState()
    val openCount by controller.openCount.collectAsState()
    val camera = remember { mutableStateOf(ViewerCameraState.HOME) }
    var fitReference by remember(openCount) { mutableStateOf<TriangleMesh?>(null) }

    val display: DesktopModel? = if (state is DesktopState.NativeUnavailable) null else state.lastGood
    val displayMesh = display?.mesh?.mesh

    // Selection (ADR-0053): the part list and the pick index belong to the displayed model; the selection itself is
    // a part NAME, so it survives a re-run that keeps the part.
    val parts: PartTable? = display?.parts
    val selection = remember { mutableStateOf(SelectionState()) }
    val pickIndex =
        remember(display?.generation, openCount) {
            display?.mesh?.let { m ->
                parts?.let { PickIndex(m, it) }
            }
        }
    // Assembly tree (ADR-0054): the open state lives here so it survives re-runs; the panel draws it.
    val tree: AssemblyTree? = display?.tree
    var treeView by remember { mutableStateOf<TreeViewState?>(null) }
    var shownTree by remember { mutableStateOf<AssemblyTree?>(null) }
    var shownOpenCount by remember { mutableStateOf(openCount) }
    var treeVisible by remember { mutableStateOf(true) }
    // Visibility (ADR-0055): lives here, per session. A re-run of the same file keeps it (vanished parts are dropped),
    // another file starts clean. Derived arrays are built once per change, never per frame or per hover.
    var visibilityState by remember { mutableStateOf(VisibilityState()) }
    val visibilityScene = remember(parts, tree) { parts?.let { VisibilityScene.of(it, tree) } }
    val visibilityView: State<VisibilityView?> =
        remember(visibilityScene, tree, parts) {
            derivedStateOf {
                visibilityScene?.let { sc ->
                    parts?.let { VisibilityView.of(visibilityState, sc, tree, it) }
                }
            }
        }
    val canvasVisibility: State<CanvasVisibility?> =
        remember(visibilityView) { derivedStateOf { visibilityView.value?.canvas } }
    LaunchedEffect(display?.generation, openCount) {
        selection.value = SelectionMachine.reduce(selection.value, SelectionEvent.SceneReplaced(parts, tree))
        // a different file starts from the first-sight state, a re-run carries the open groups over
        val previous = if (shownOpenCount == openCount) shownTree else null
        treeView =
            tree?.let { TreeViewLogic.onTreeReplaced(previous, it, treeView ?: TreeViewState(emptySet(), emptySet())) }
        shownTree = tree
        // before shownOpenCount is updated, or a new file would never reset
        visibilityState =
            if (shownOpenCount != openCount) {
                VisibilityState()
            } else {
                VisibilityMachine.onRerun(visibilityState, visibilityScene)
            }
        shownOpenCount = openCount
    }
    val picking =
        remember(pickIndex) {
            if (pickIndex != null && parts != null) {
                DesktopPicking(pickIndex, parts, display?.generation ?: -1L, selection, visibilityView)
            } else {
                null
            }
        }
    LaunchedEffect(picking) {
        while (picking != null) {
            delay(HoverThrottle.DEFAULT_INTERVAL_MS)
            picking.tick()
        }
    }
    // a part that was just hidden must not stay "hovered" while the pointer rests
    LaunchedEffect(visibilityView.value) {
        val mode = visibilityView.value?.arrays?.partMode ?: return@LaunchedEffect
        val over = selection.value.hovered ?: return@LaunchedEffect
        if (over in mode.indices && mode[over] == PartMode.HIDDEN) {
            selection.value = SelectionMachine.reduce(selection.value, SelectionEvent.HoverAt(null))
        }
    }
    val accent = if (dark) ACCENT_DARK else ACCENT_LIGHT
    val highlight =
        remember(parts, tree, dark) {
            derivedStateOf<CanvasHighlight?> {
                parts?.let {
                    val chosen = SelectionMachine.highlightRange(selection.value, tree, it)
                    val over =
                        selection.value.treeHover
                            ?: selection.value.hovered?.let { h -> h..h }
                            ?: SelectionMachine.NONE
                    CanvasHighlight(
                        it.triangleToPartArray(),
                        selected = chosen.first,
                        hovered = over.first,
                        accentRgb =
                            doubleArrayOf(
                                accent.red.toDouble(),
                                accent.green.toDouble(),
                                accent.blue.toDouble(),
                            ),
                        selectedLast = chosen.last,
                        hoveredLast = over.last,
                    )
                }
            }
        }
    val selectedPart: PartInfo? =
        parts?.let { table ->
            SelectionMachine.selectedIndex(selection.value, table).takeIf { it >= 0 }?.let { table.parts[it] }
        }

    val selectedGroup =
        if (parts != null && tree != null) {
            selection.value.selectedGroup?.let { key ->
                tree.nodeOfKey(key).takeIf { it >= 0 }?.let { GroupInspection.of(tree, it, parts) }
            }
        } else {
            null
        }
    val treeAvailable = tree != null && tree.leafCount(0) >= 2

    // Visibility actions work on the part range of the selection (a group, a part, or nothing).
    val target = SelectionMachine.highlightRange(selection.value, tree, parts)
    val hasTarget = target != SelectionMachine.NONE
    val applyVisibility: (IntRange, (VisibilityState, VisibilityScene, IntRange) -> VisibilityState) -> Unit =
        { range, f -> visibilityScene?.let { visibilityState = f(visibilityState, it, range) } }
    val counts = visibilityView.value?.arrays?.counts
    val somethingChanged = counts != null && (counts.hidden > 0 || counts.transparent > 0)

    // The camera and the fit scale belong to the OPEN file: a re-run keeps both, so the view never jumps.
    LaunchedEffect(openCount) { camera.value = ViewerCameraState.HOME }
    LaunchedEffect(displayMesh, openCount) {
        if (fitReference == null && displayMesh != null) fitReference = displayMesh
    }
    // Trust question per folder and session (ADR-0058): a block is arbitrary Kotlin, a note is untrusted text.
    val openRoot = (workspace as? WorkspaceState.Open)?.scan?.root
    LaunchedEffect(openRoot) {
        if (openRoot != null && !knowledge.trusted) {
            val options = arrayOf("Run blocks", "Don't run")
            val answer =
                javax.swing.JOptionPane.showOptionDialog(
                    window,
                    "Run kstep blocks from this folder?\n\n" +
                        "A block is a program that runs with your user rights. " +
                        "Only allow it for folders you trust.\n" +
                        DisplayName.escaped(openRoot.toString(), 120),
                    "kSTEP Desktop",
                    javax.swing.JOptionPane.DEFAULT_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[1],
                )
            if (answer == 0) knowledge.trustFolder()
        }
    }
    val openPath: (Path) -> Boolean = { path ->
        if (confirmUnsaved(window, session)) {
            // a script replaces the workspace: the editor and the run belong to one mode at a time
            if (workspace !is WorkspaceState.None) knowledge.closeWorkspace()
            session.open(path)
            controller.open(path)
            true
        } else {
            false
        }
    }
    val openNote: (WorkspaceEntry) -> Unit = { entry ->
        if (confirmUnsaved(window, session)) {
            val checked = knowledge.resolveForOpen(entry.path)
            if (checked is PathCheck.Ok) {
                knowledge.clearError()
                knowledge.onFileOpened(checked.path)
                session.open(checked.path)
                controller.open(checked.path)
            }
        }
    }
    // false when the user kept the unsaved changes instead
    val openFolderPath: (Path) -> Boolean = { dir ->
        // a new folder replaces the old workspace: the open note of the old one must not stay in the editor or the run
        if (workspace is WorkspaceState.None) {
            knowledge.openFolder(dir)
            true
        } else if (confirmUnsaved(window, session)) {
            knowledge.closeWorkspace()
            session.clear()
            controller.clear()
            knowledge.openFolder(dir)
            true
        } else {
            false
        }
    }
    val chooseFolder: () -> Unit = { chooseFolderDialog(window)?.let { openFolderPath(it) } }
    val openFromRecent: (RecentEntry) -> Unit = { entry ->
        scope.launch {
            val check = withContext(Dispatchers.IO) { RecentOpenCheck.check(entry) }
            val problem = RecentMessages.forCheck(entry, check)
            if (problem != null) {
                // the editor stays as it is; the entry goes
                actionError = problem
                updateRecent { it.remove(entry.path) }
            } else {
                actionError = null
                if (entry.kind == RecentKind.SCRIPT) openPath(entry.path) else openFolderPath(entry.path)
            }
        }
    }
    val openDroppedNote: (Path) -> Boolean = { note ->
        val open = workspace as? WorkspaceState.Open
        if (open != null && note.startsWith(open.scan.root)) {
            val found = PendingNote.resolve(note, open.scan.root, open, false, false)
            if (found is PendingResolution.Open) {
                openNote(found.entry)
                true
            } else {
                actionError = DropEvaluator.reject(notInFolderText(note))
                false
            }
        } else {
            val parent = note.parent
            if (parent != null && openFolderPath(parent)) {
                pendingErrorBefore = knowledge.error.value
                pendingSawScan = false
                pendingRoot = parent
                pendingNote = note
                true
            } else {
                false
            }
        }
    }
    val handleDrop: (List<Path>) -> Unit = { dropped ->
        scope.launch {
            val decision = withContext(Dispatchers.IO) { DropEvaluator.evaluate(dropped) }
            val started =
                when (val action = decision.action) {
                    is DropAction.Rejected -> {
                        actionError = action.error
                        false
                    }
                    is DropAction.OpenScript -> openPath(action.path).also { if (it) actionError = null }
                    is DropAction.OpenFolder -> openFolderPath(action.path).also { if (it) actionError = null }
                    is DropAction.OpenNote -> openDroppedNote(action.path).also { if (it) actionError = null }
                }
            if (started) {
                val name =
                    when (val action = decision.action) {
                        is DropAction.OpenScript -> action.path
                        is DropAction.OpenFolder -> action.path
                        is DropAction.OpenNote -> action.path
                        is DropAction.Rejected -> null
                    }?.fileName?.toString().orEmpty()
                DropEvaluator.openedNotice(name, decision.ignoredCount)?.let { transientNotice = it }
            }
        }
    }
    val currentHandleDrop by rememberUpdatedState(handleDrop)
    val dropTarget =
        remember {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    // some platforms hide the names while the pointer is still over the window: then the hint stays neutral
                    val names =
                        DropPaths
                            .fromTransferable(
                                event.awtTransferable,
                            ).map { it.fileName?.toString().orEmpty() }
                    dragHint = DropEvaluator.hint(names)
                }

                override fun onExited(event: DragAndDropEvent) {
                    dragHint = null
                }

                override fun onEnded(event: DragAndDropEvent) {
                    dragHint = null
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    dragHint = null
                    val paths = DropPaths.fromTransferable(event.awtTransferable)
                    currentHandleDrop(paths)
                    return paths.isNotEmpty()
                }
            }
        }
    // a note dropped from outside the workspace waits for the scan of its folder
    LaunchedEffect(workspace, pendingNote, knowledgeError) {
        val note = pendingNote ?: return@LaunchedEffect
        if (workspace is WorkspaceState.Scanning) pendingSawScan = true
        val newError = knowledgeError != null && knowledgeError !== pendingErrorBefore
        when (val r = PendingNote.resolve(note, pendingRoot, workspace, newError, pendingSawScan)) {
            is PendingResolution.Open -> {
                pendingNote = null
                openNote(r.entry)
            }
            PendingResolution.Drop -> {
                pendingNote = null
                actionError = DropEvaluator.reject(notInFolderText(note))
            }
            PendingResolution.Wait -> Unit
        }
    }
    // Recent (ADR-0066): a script counts once it is really shown, a folder once its scan has succeeded.
    val shownScript = (editorView.state as? EditorState.Editing)?.path
    LaunchedEffect(shownScript) {
        val path = shownScript ?: return@LaunchedEffect
        if (path.fileName
                ?.toString()
                ?.lowercase()
                ?.endsWith(SCRIPT_FILTER_SUFFIX) != true
        ) {
            return@LaunchedEffect
        }
        val ok = withContext(Dispatchers.IO) { RecentOpenCheck.checkPath(path, false) is RecentCheck.Ok }
        if (ok) updateRecent { it.touch(RecentEntry(path, RecentKind.SCRIPT)) }
    }
    LaunchedEffect(openRoot) {
        openRoot?.let { root -> updateRecent { it.touch(RecentEntry(root, RecentKind.FOLDER)) } }
    }
    val closeWorkspace: () -> Unit = {
        if (confirmUnsaved(window, session)) {
            knowledge.closeWorkspace()
            session.clear()
            controller.clear()
        }
    }
    val jumpBlock: (Boolean) -> Unit = { forward ->
        knowledge.neighbourCaret(editorView.text, lastCaret, forward)?.let { offset ->
            session.requestCaret(offset)
            jumpNonce++
            jump = EditorJump(CaretMapper.lineColumn(editorView.text, offset).first, jumpNonce)
        }
    }
    LaunchedEffect(Unit) {
        initialFile?.let {
            session.open(it)
            controller.open(it)
        }
    }

    var showProgress by remember { mutableStateOf(false) }
    val running = state as? DesktopState.Running
    LaunchedEffect(running?.generation) {
        showProgress = false
        if (running != null) {
            delay(PROGRESS_DELAY_MS)
            showProgress = true
        }
    }

    val openFile: () -> Unit = { chooseScript(window)?.let(openPath) }
    val exportScript: Path? = state.file
    val openExport: () -> Unit = {
        if (exportScript != null && editorView.state is EditorState.Editing && !exportRunning) {
            exportNotice =
                ExportNotice.retain(exportNotice, state.file, state, knowledgeOpen, exportDialogOpened = true)
            exportSaveError = null
            val declared = (state as? DesktopState.Ok)?.model?.scriptExportTargets.orEmpty()
            val saved = exportPreferences.load()
            val prefill =
                ExportPrefill.resolve(
                    declared,
                    saved.scripts[ExportPreferencesStore.scriptKey(exportScript)],
                    saved.copy(
                        lastDir = lastExportDir ?: saved.lastDir,
                        lastFormat =
                            saved.lastFormat ?: lastExportFormat,
                    ),
                )
            exporter.begin(
                exportScript,
                prefill.format,
                prefill.dir ?: exportScript.toAbsolutePath().parent ?: exportScript.toAbsolutePath(),
                declared,
                prefill.selectedScriptTarget,
            )
            exportVisible = true
        }
    }
    val closeExport: () -> Unit = {
        exportVisible = false
        exporter.closeDialog()
    }
    LaunchedEffect(exportState) {
        val done = exportState as? ExportState.Succeeded ?: return@LaunchedEffect
        // Only with a shown script (a notice for no file would never be visible); the generation is taken NOW so the
        // run the export's own save may have started does not clear it (ADR-0064).
        state.file?.let {
            exportNotice =
                ExportNotice.onExportSucceeded(
                    ExportText.successLine(done.fileName, done.bytes),
                    done.dir,
                    it,
                    state.generation,
                )
        }
        lastExportDir = done.dir
        val script = state.file
        exportPreferences.remember(
            lastExportFormat,
            done.dir,
            script?.let(ExportPreferencesStore::scriptKey),
            script?.let {
                ScriptExportPreference(
                    lastExportFormat,
                    done.dir,
                    lastExportTargetName,
                    System.currentTimeMillis() / 1_000,
                )
            },
        )
        exportVisible = false
    }
    LaunchedEffect(state, knowledgeOpen) {
        exportNotice = ExportNotice.retain(exportNotice, state.file, state, knowledgeOpen, exportDialogOpened = false)
    }
    LaunchedEffect(exportState.generation, exportState is ExportState.Running) {
        (exportState as? ExportState.Running)?.let { run ->
            lastExportFormat = run.format
            lastExportTargetName = run.selectedScriptTarget?.let { run.scriptTargets.getOrNull(it)?.fileName }
        }
    }
    var selectedError by remember(state) { mutableStateOf<DesktopError?>(null) }
    val selectError: (DesktopError) -> Unit = { error ->
        val offset = CaretMapper.toOffset(editorView.text, error.line, error.column)
        if (offset != null) {
            selectedError = error
            session.requestCaret(offset)
            jumpNonce++
            jump = EditorJump(CaretMapper.lineColumn(editorView.text, offset).first, jumpNonce)
        }
    }
    // "Show in Script" (ADR-0068): a name heuristic on the live buffer, only ever started by the user.
    var showNotice by remember { mutableStateOf<Pair<String, ShowInScriptMessage>?>(null) }
    var findRequest by remember { mutableStateOf<FindRequest?>(null) }
    var findNonce by remember { mutableStateOf(0) }
    LaunchedEffect(showNotice) {
        if (showNotice != null) {
            delay(NOTICE_MS)
            showNotice = null
        }
    }
    val showInScriptReason: (String) -> String? = { name ->
        when {
            knowledgeOpen -> ShowInScriptMessages.DISABLED_KNOWLEDGE
            editorView.state !is EditorState.Editing -> ShowInScriptMessages.DISABLED_NO_SCRIPT
            name.isEmpty() -> ShowInScriptMessages.DISABLED_NO_NAME
            else -> null
        }
    }
    val showInScript: (String) -> Unit = { name ->
        if (showInScriptReason(name) == null) {
            val snapshot = editorView
            val text = snapshot.text
            val differs = snapshot.dirty
            showNotice = null
            scope.launch {
                val location = withContext(Dispatchers.Default) { PartSourceLocator.locate(text, name) }
                // the buffer may have changed while searching: offsets would no longer be valid
                if (session.view.value.revision == snapshot.revision) {
                    if (location is SourceLocation.Found) {
                        session.requestCaret(location.offset)
                        jumpNonce++
                        jump = EditorJump(CaretMapper.lineColumn(text, location.offset).first, jumpNonce)
                    }
                    ShowInScriptMessages.of(location, name, differs)?.let { showNotice = name to it }
                }
            }
        }
    }
    val showInScriptUi: (String) -> ShowInScriptUi = { name ->
        val reason = showInScriptReason(name)
        ShowInScriptUi(
            enabled = reason == null,
            disabledReason = reason,
            onClick = { showInScript(name) },
            message = showNotice?.takeIf { it.first == name }?.second,
            onFind = { query ->
                findNonce++
                findRequest = FindRequest(query, findNonce)
            },
        )
    }
    val home: () -> Unit = {
        camera.value = ViewerCameraState.HOME
        fitReference = displayMesh
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(
            Modifier
                .fillMaxSize()
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val command = event.isMetaPressed || event.isCtrlPressed
                    when {
                        command && event.isShiftPressed && event.key == Key.O -> {
                            chooseFolder()
                            true
                        }
                        knowledgeOpen &&
                            command &&
                            event.isAltPressed &&
                            (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
                            jumpBlock(event.key == Key.DirectionDown)
                            true
                        }
                        command && event.key == Key.O -> {
                            openFile()
                            true
                        }
                        command && event.key == Key.R -> {
                            controller.forceRun()
                            true
                        }
                        command && event.key == Key.S -> {
                            session.save()
                            true
                        }
                        command && event.key == Key.E -> {
                            if (!knowledgeOpen) openExport()
                            true
                        }
                        command && event.key == Key.K -> {
                            if (!knowledgeOpen) {
                                aiVisible = true
                                aiFocus++
                            }
                            true
                        }
                        !command && !editorFocused && event.key == Key.H -> {
                            home()
                            true
                        }
                        else ->
                            when (
                                visibilityAction(
                                    event.key,
                                    event.isShiftPressed,
                                    command,
                                    event.isAltPressed,
                                    editorFocused,
                                    inTree = false,
                                )
                            ) {
                                // a key that has no selection to act on is not consumed
                                VisibilityAction.ToggleTransparent ->
                                    hasTarget.also {
                                        if (it) {
                                            applyVisibility(
                                                target,
                                                VisibilityMachine::toggleTransparent,
                                            )
                                        }
                                    }
                                VisibilityAction.ToggleHidden ->
                                    hasTarget.also { if (it) applyVisibility(target, VisibilityMachine::toggleHidden) }
                                VisibilityAction.Isolate ->
                                    hasTarget.also { if (it) applyVisibility(target, VisibilityMachine::isolate) }
                                VisibilityAction.ShowAll ->
                                    somethingChanged.also {
                                        if (it) {
                                            visibilityState =
                                                VisibilityMachine.showAll(visibilityState)
                                        }
                                    }
                                else -> false
                            }
                    }
                },
        ) {
            if (exportVisible) {
                ExportDialog(
                    sandboxNotice = sandboxNotice,
                    state = exportState,
                    context =
                        ExportDialogContext(
                            scriptName = exportScript?.fileName?.toString().orEmpty(),
                            dirty = editorView.dirty,
                            nativeUnavailable = state is DesktopState.NativeUnavailable,
                            saveError = exportSaveError,
                            tick = exportNow,
                            elapsedSince = exporter::elapsedSince,
                        ),
                    onFormat = exporter::chooseFormat,
                    onScriptTarget = exporter::chooseScriptTarget,
                    onChoose = {
                        val current = exportState as? ExportState.Configuring
                        val start = current?.target?.parent ?: lastExportDir
                        chooseExportTarget(
                            window,
                            start,
                            current?.target?.fileName?.toString(),
                        )?.let(exporter::chooseTarget)
                    },
                    onExport = { saveFirst ->
                        exportSaveError = null
                        if (saveFirst && !session.saveAndWait()) {
                            exportSaveError = "The script could not be saved (KSTEP-D-011). Nothing was exported."
                        } else {
                            if ((exportState as? ExportState.Configuring)?.overwrite is Overwrite.NeedsConfirm) {
                                exporter.confirmReplace()
                            }
                            exporter.start()
                        }
                    },
                    onStop = exporter::stop,
                    onClose = closeExport,
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val errorBarMax = maxHeight * ERROR_BAR_MAX_FRACTION
                val windowWidth = maxWidth
                Column(Modifier.fillMaxSize()) {
                    Toolbar(
                        state,
                        editorView,
                        onOpen = openFile,
                        onExport = openExport,
                        exportEnabled =
                            !knowledgeOpen && exportScript != null && editorView.state is EditorState.Editing,
                        knowledgeOpen = knowledgeOpen,
                        knowledgeLabel =
                            (workspace as? WorkspaceState.Open)?.scan?.root?.let {
                                "Knowledge · " + DisplayName.escaped(it.fileName?.toString() ?: it.toString(), 48)
                            },
                        onOpenFolder = chooseFolder,
                        recent = recent.entries,
                        onRecent = openFromRecent,
                        onClearRecent = { updateRecent { it.clear() } },
                        transientNotice = transientNotice,
                        onCloseWorkspace = closeWorkspace,
                        sandboxNotice = sandboxNotice,
                        exportNotice = exportNotice,
                        onShowInFolder = { exportNotice?.let { showInFolder(it.dir) } },
                        onHome = home,
                        treeAvailable = treeAvailable,
                        treeOn = treeVisible,
                        onTree = { treeVisible = !treeVisible },
                        visibilityEnabled = visibilityScene != null,
                        counts = counts ?: VisibilityCounts(0, 0),
                        hasSelection = hasTarget,
                        alphaStep = visibilityState.alphaStep,
                        onShowAll = { visibilityState = VisibilityMachine.showAll(visibilityState) },
                        onIsolate = { applyVisibility(target, VisibilityMachine::isolate) },
                        onHide = { applyVisibility(target, VisibilityMachine::hide) },
                        onAlpha = { visibilityState = VisibilityMachine.withAlpha(visibilityState, it) },
                        aiOn = aiVisible && !knowledgeOpen,
                        onAi = {
                            aiVisible = !aiVisible
                            if (aiVisible) aiFocus++
                        },
                        aiStatus = aiStatusText(aiRepair, aiNow, ai.limitsInUse.maxAttempts),
                    )

                    val totalPx = with(LocalDensity.current) { windowWidth.toPx() }
                    val minFraction = (MIN_PANE_DP.dp / windowWidth).coerceIn(0.1f, 0.5f)
                    val effectiveFraction = editorFraction.coerceIn(minFraction, 1f - minFraction)
                    val errors = (state as? DesktopState.Error)?.errors.orEmpty()
                    val sidebarWidth = if (workspace is WorkspaceState.None) 0.dp else SIDEBAR_WIDTH + 1.dp
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (workspace !is WorkspaceState.None) {
                            WorkspaceSidebar(workspace, state.file, openNote)
                            Box(Modifier.width(1.dp).fillMaxHeight().background(DIM.copy(alpha = 0.5f)))
                        }
                        ScriptEditor(
                            session,
                            editorView,
                            errors,
                            dark,
                            jump,
                            onFocusChanged = { editorFocused = it },
                            modifier = Modifier.width(windowWidth * effectiveFraction).fillMaxHeight(),
                            errorLine = selectedError?.line,
                            findRequest = findRequest,
                            onUserEdit = { selectedError = null },
                            onEscape = { selectedError = null },
                            knowledge = knowledgeOpen && state.file?.let { knowledge.isWorkspaceFile(it) } == true,
                            activeBlock = blockHeader?.takeIf { it.count > 0 }?.index,
                            onCaret =
                                if (knowledgeOpen) {
                                    { text, caret ->
                                        lastCaret = caret
                                        knowledge.onBuffer(text, caret)
                                    }
                                } else {
                                    null
                                },
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(DIM.copy(alpha = 0.5f))
                                .pointerHoverIcon(PointerIcon.Crosshair),
                        )
                        Box(
                            Modifier
                                .width(6.dp)
                                .fillMaxHeight()
                                .pointerHoverIcon(PointerIcon.Hand)
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    state =
                                        rememberDraggableState { dx ->
                                            editorFraction =
                                                (effectiveFraction + dx / totalPx).coerceIn(
                                                    minFraction,
                                                    1f - minFraction,
                                                )
                                        },
                                    onDragStopped = {
                                        val saved = editorFraction.toDouble()
                                        scope.launch(Dispatchers.IO) { layoutStore.save(LayoutPreferences(saved)) }
                                    },
                                ),
                        )
                        // the tree needs its own width and still leaves the canvas at least the minimum pane width
                        val viewerWidth = windowWidth * (1f - effectiveFraction) - EDITOR_DIVIDER_DP.dp - sidebarWidth
                        val showTree =
                            treeVisible &&
                                treeAvailable &&
                                parts != null &&
                                viewerWidth >= TREE_WIDTH + TREE_DIVIDER_DP.dp + MIN_PANE_DP.dp
                        if (showTree && tree != null && parts != null) {
                            AssemblyTreePanel(
                                tree = tree,
                                table = parts,
                                view = treeView ?: remember(tree) { TreeViewLogic.initial(tree) },
                                onView = { treeView = it },
                                selection = selection,
                                dispatch = { selection.value = SelectionMachine.reduce(selection.value, it) },
                                dark = dark,
                                background = MaterialTheme.colorScheme.background,
                                nodeVisibility = visibilityView.value?.arrays?.nodeState,
                                counts = counts ?: VisibilityCounts(0, 0),
                                onCycleNode = { node ->
                                    applyVisibility(tree.firstPart(node)..tree.lastPart(node), VisibilityMachine::cycle)
                                },
                                onCycleSelected = { applyVisibility(target, VisibilityMachine::cycle) },
                                modifier = Modifier.fillMaxHeight(),
                                onShowInScript = showInScript,
                            )
                            Box(Modifier.width(TREE_DIVIDER_DP.dp).fillMaxHeight().background(DIM.copy(alpha = 0.5f)))
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (knowledgeOpen) {
                                BlockPreviewHeader(
                                    blockHeader,
                                    onPrevious = { jumpBlock(false) },
                                    onNext = { jumpBlock(true) },
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                Canvas(
                                    state,
                                    display,
                                    camera,
                                    fitReference,
                                    openFile,
                                    highlight,
                                    picking,
                                    canvasVisibility,
                                    if (workspace is WorkspaceState.None) recent.entries else emptyList(),
                                    openFromRecent,
                                )
                                Badge(state)
                                selectedPart?.let {
                                    InspectionPanel(
                                        it,
                                        MaterialTheme.colorScheme.background,
                                        Modifier.align(Alignment.TopEnd).padding(12.dp),
                                        action = showInScriptUi(it.name),
                                    )
                                }
                                selectedGroup?.let {
                                    GroupInspectionPanel(
                                        it,
                                        MaterialTheme.colorScheme.background,
                                        Modifier.align(Alignment.TopEnd).padding(12.dp),
                                        action = showInScriptUi(it.name),
                                    )
                                }
                                if (showProgress) {
                                    LinearProgressIndicator(
                                        Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                                    )
                                }
                            }
                        }
                        if (aiVisible && !knowledgeOpen && windowWidth >= AI_MIN_WINDOW_DP.dp) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(DIM.copy(alpha = 0.5f)))
                            AiPanel(
                                controller = ai,
                                ui = aiUi,
                                repair = aiRepair,
                                editorText = editorView.text,
                                hasFile = editorView.state is EditorState.Editing,
                                focusNonce = aiFocus,
                                onApply = session::applyProposal,
                                onApplyIf = session::applyProposalIf,
                                onEscapeToEditor = {},
                                modifier =
                                    Modifier
                                        .width(
                                            AI_PANEL_DP.dp,
                                        ).fillMaxHeight()
                                        .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    (state as? DesktopState.Error)?.let { error ->
                        ErrorBar(
                            error.errors,
                            dark,
                            maxHeight = errorBarMax,
                            onSelect = selectError,
                            selected = selectedError,
                        )
                    }
                    knowledgeError?.let { ErrorBar(listOf(it), dark, maxHeight = errorBarMax) }
                    actionError?.let { ErrorBar(listOf(it), dark, maxHeight = errorBarMax) }
                    // A non-fatal problem of the shown model, e.g. an invalid part list (KSTEP-D-012): the run stays "ok".
                    val okWarnings = (state as? DesktopState.Ok)?.model?.warnings.orEmpty()
                    if (okWarnings.isNotEmpty()) {
                        ErrorBar(okWarnings, dark, maxHeight = errorBarMax, warning = true)
                    }
                }
            }
            dragHint?.let { DropOverlay(it, editorView.dirty) }
        }
    }
}

@Composable
private fun Toolbar(
    state: DesktopState,
    editor: EditorView,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    exportEnabled: Boolean,
    exportNotice: ExportNotice.Notice?,
    onShowInFolder: () -> Unit,
    onHome: () -> Unit,
    treeAvailable: Boolean,
    treeOn: Boolean,
    onTree: () -> Unit,
    visibilityEnabled: Boolean,
    counts: VisibilityCounts,
    hasSelection: Boolean,
    alphaStep: Int,
    onShowAll: () -> Unit,
    onIsolate: () -> Unit,
    onHide: () -> Unit,
    onAlpha: (Float) -> Unit,
    aiOn: Boolean,
    onAi: () -> Unit,
    aiStatus: String,
    knowledgeOpen: Boolean,
    knowledgeLabel: String?,
    onOpenFolder: () -> Unit,
    recent: List<RecentEntry>,
    onRecent: (RecentEntry) -> Unit,
    onClearRecent: () -> Unit,
    transientNotice: String?,
    onCloseWorkspace: () -> Unit,
    sandboxNotice: SandboxNotice? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpen) { Text("Open", fontSize = 13.sp) }
        WithTip(openFolderTip()) { TextButton(onClick = onOpenFolder) { Text("Open Folder…", fontSize = 13.sp) } }
        RecentMenu(recent, onRecent, onClearRecent)
        if (knowledgeOpen) {
            TextButton(onClick = onCloseWorkspace) { Text("Close Workspace", fontSize = 13.sp) }
        }
        WithTip(
            if (knowledgeOpen) {
                KNOWLEDGE_UNAVAILABLE
            } else if (exportEnabled) {
                null
            } else {
                "Open a script first"
            },
        ) {
            TextButton(onClick = onExport, enabled = exportEnabled) { Text("Export…", fontSize = 13.sp) }
        }
        TextButton(onClick = onHome) { Text("Home", fontSize = 13.sp) }
        if (treeAvailable) {
            TextButton(onClick = onTree) {
                Text("Tree", fontSize = 13.sp, color = if (treeOn) Color.Unspecified else DIM)
            }
        }
        WithTip(if (knowledgeOpen) KNOWLEDGE_UNAVAILABLE else null) {
            TextButton(onClick = onAi, enabled = !knowledgeOpen) {
                Text("AI", fontSize = 13.sp, color = if (aiOn) Color.Unspecified else DIM)
            }
        }
        if (visibilityEnabled) {
            VisibilityControls(counts, hasSelection, alphaStep, onShowAll, onIsolate, onHide, onAlpha)
        }
        StatusLine(
            fileName = state.file?.fileName?.toString(),
            exportText = exportNotice?.text?.takeIf { !knowledgeOpen },
            onShowInFolder = onShowInFolder,
            sandboxNotice = sandboxNotice,
            statusText =
                listOfNotNull(
                    knowledgeLabel,
                    statusText(state),
                    transientNotice,
                    aiStatus,
                    visibilityStatusText(counts),
                ).filter { it.isNotEmpty() }.joinToString(StatusLineText.SEPARATOR) +
                    if (editor.dirty) "  ·  unsaved changes  ·  preview shows saved file" else "",
        )
    }
}

private fun statusText(state: DesktopState): String =
    when (state) {
        DesktopState.Idle -> ""
        is DesktopState.Running -> "● Running…"
        is DesktopState.Ok ->
            listOfNotNull(
                ErrorBarText.statusOk(state.model.durationMs, state.model.triangles),
                ErrorBarText.triangleWarning(state.model.triangles),
                state.model.notes.firstOrNull(),
            ).joinToString("  ·  ")
        is DesktopState.Error -> "● Error"
        is DesktopState.NativeUnavailable -> "● Geometry unavailable"
        is DesktopState.Waiting -> state.hint
    }

@Composable
private fun Canvas(
    state: DesktopState,
    display: DesktopModel?,
    camera: androidx.compose.runtime.MutableState<ViewerCameraState>,
    fitReference: TriangleMesh?,
    onOpen: () -> Unit,
    highlight: State<CanvasHighlight?>,
    picking: CanvasPicking?,
    visibility: State<CanvasVisibility?>,
    recent: List<RecentEntry>,
    onRecent: (RecentEntry) -> Unit,
) {
    val modifier = Modifier.fillMaxSize()
    when {
        state is DesktopState.Idle ->
            Column(
                modifier,
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(onClick = onOpen) { Text("Open a .kstep.kts script") }
                Text(
                    if (System
                            .getProperty("os.name")
                            .orEmpty()
                            .lowercase()
                            .contains("mac")
                    ) {
                        "⌘O"
                    } else {
                        "Ctrl+O"
                    },
                    color = DIM,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (recent.isNotEmpty()) {
                    Text(
                        "Recent",
                        color = DIM,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                    recent.take(EMPTY_RECENT_SHOWN).forEach { entry ->
                        TextButton(onClick = { onRecent(entry) }) { RecentLabel(entry) }
                    }
                }
            }
        state is DesktopState.NativeUnavailable ->
            UnavailableNotice(
                state.reason,
                modifier,
                heading = "kSTEP Desktop cannot show geometry: the native OCCT bridge is unavailable.",
            )
        display == null ->
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(
                    when (state) {
                        is DesktopState.Running -> "Running the script…"
                        is DesktopState.Waiting -> state.hint
                        else -> "No result yet"
                    },
                    color = DIM,
                )
            }
        display.mesh == null ->
            UnavailableNotice(
                "Script has no shape(...)",
                modifier,
                heading = "kSTEP Desktop has no geometry to show.",
                showInstallInstructions = false,
            )
        else -> ShapeCanvas(display.mesh, modifier, camera, fitReference, highlight, picking, visibility)
    }
}

/** Marks a canvas that shows an outdated result: a yellow dot and the time of the last good run (Zhuo). */
@Composable
private fun Badge(state: DesktopState) {
    val error = state as? DesktopState.Error ?: return
    val lastGood = error.lastGood ?: return
    Box(Modifier.padding(12.dp)) {
        Row(
            Modifier.background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(RectangleShape).background(WARN))
            Text(
                ErrorBarText.lastGoodBadge(lastGood.finishedAt),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private fun notInFolderText(note: Path): String =
    "${DisplayName.escaped(note.fileName?.toString() ?: note.toString(), 80)} is not part of the scanned folder."

/** A Recent line: the name in normal colour, the shortened folder dimmed. Names are escaped, never raw. */
@Composable
private fun RecentLabel(entry: RecentEntry) {
    val label = RecentMessages.label(entry)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.name, fontSize = 13.sp)
        if (label.parent.isNotEmpty()) {
            Text(
                label.parent,
                fontSize = 11.sp,
                color = DIM,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun RecentMenu(
    recent: List<RecentEntry>,
    onRecent: (RecentEntry) -> Unit,
    onClear: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        WithTip(if (recent.isEmpty()) "No recent files" else null) {
            TextButton(onClick = { open = true }, enabled = recent.isNotEmpty()) { Text("Recent ▾", fontSize = 13.sp) }
        }
        DropdownMenu(expanded = open && recent.isNotEmpty(), onDismissRequest = { open = false }) {
            recent.forEach { entry ->
                DropdownMenuItem(
                    text = { RecentLabel(entry) },
                    onClick = {
                        open = false
                        onRecent(entry)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Clear Recent", fontSize = 13.sp) },
                onClick = {
                    open = false
                    onClear()
                },
            )
        }
    }
}

/** The hint while items are dragged over the window: a frame and one line on a faint background, never a full cover. */
@Composable
private fun DropOverlay(
    hint: DropHint,
    dirty: Boolean,
) {
    val ok = hint == DropHint.ACCEPT
    val frame = if (ok) MaterialTheme.colorScheme.primary else DIM
    Box(Modifier.fillMaxSize().border(2.dp, frame), contentAlignment = Alignment.Center) {
        Text(
            when {
                !ok -> "Only .kstep.kts, .md or folders"
                dirty -> "Drop to open — unsaved changes will be asked about"
                else -> "Drop to open"
            },
            fontSize = 14.sp,
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private const val KNOWLEDGE_UNAVAILABLE = "Not available in a knowledge workspace yet (planned)"

private fun openFolderTip(): String = "Open a folder of Markdown notes (${if (isMac()) "⇧⌘O" else "Ctrl+Shift+O"})"

private fun isMac(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("mac")

/**
 * A native folder dialog; `null` if cancelled. On macOS the AWT file dialog only chooses folders with a system
 * property, which is reset right after (or Open... would stop choosing scripts); elsewhere a Swing chooser.
 */
private fun chooseFolderDialog(parent: Frame): Path? {
    if (isMac()) {
        val key = "apple.awt.fileDialogForDirectories"
        System.setProperty(key, "true")
        try {
            val dialog = FileDialog(parent, "Open a folder of notes", FileDialog.LOAD)
            dialog.isVisible = true
            val name = dialog.file ?: return null
            return Path.of(dialog.directory ?: "", name)
        } finally {
            System.setProperty(key, "false")
        }
    }
    val chooser = javax.swing.JFileChooser()
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = "Open a folder of notes"
    return if (chooser.showOpenDialog(parent) ==
        javax.swing.JFileChooser.APPROVE_OPTION
    ) {
        chooser.selectedFile.toPath()
    } else {
        null
    }
}

/** A native file dialog for a `*.kstep.kts` script; `null` if cancelled. */
private fun chooseScript(parent: Frame): Path? {
    val dialog = FileDialog(parent, "Open a kSTEP script", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(SCRIPT_FILTER_SUFFIX) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return Path.of(dialog.directory ?: "", name)
}

/** Opens the folder of an exported file in the system file manager; no-op where the platform has no such support. */
private fun showInFolder(dir: Path) {
    try {
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else return
        if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) desktop.open(dir.toFile())
    } catch (_: Exception) {
        // a missing file manager is not an error of the export
    }
}

/** A native save dialog for an export; `null` if cancelled. The suggested name is only a suggestion. */
private fun chooseExportTarget(
    parent: Frame,
    startDir: Path?,
    suggestedName: String?,
): Path? {
    val dialog = FileDialog(parent, "Export to", FileDialog.SAVE)
    startDir?.let { dialog.directory = it.toString() }
    suggestedName?.let { dialog.file = it }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return Path.of(dialog.directory ?: "", name)
}
