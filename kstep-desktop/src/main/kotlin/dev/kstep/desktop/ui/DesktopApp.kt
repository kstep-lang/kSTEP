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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import dev.kstep.cli.kstepVersion
import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.aiwire.AiController
import dev.kstep.desktop.convert.ConvertState
import dev.kstep.desktop.drop.DropAction
import dev.kstep.desktop.drop.DropEvaluator
import dev.kstep.desktop.drop.DropHint
import dev.kstep.desktop.drop.DropPaths
import dev.kstep.desktop.drop.PendingNote
import dev.kstep.desktop.drop.PendingResolution
import dev.kstep.desktop.drop.StartRequest
import dev.kstep.desktop.edit.BannerActions
import dev.kstep.desktop.edit.CaretMapper
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.PartSourceResolution
import dev.kstep.desktop.edit.PartSourceResolver
import dev.kstep.desktop.export.ExportController
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportNotice
import dev.kstep.desktop.export.ExportPreferencesStore
import dev.kstep.desktop.export.ExportPrefill
import dev.kstep.desktop.export.ExportState
import dev.kstep.desktop.export.ExportText
import dev.kstep.desktop.export.Overwrite
import dev.kstep.desktop.export.ScriptExportPreference
import dev.kstep.desktop.mcp.AgentNoticeKind
import dev.kstep.desktop.mcp.DesktopMcpServerManager
import dev.kstep.desktop.mcp.McpActionHost
import dev.kstep.desktop.mcp.McpActionScene
import dev.kstep.desktop.mcp.McpActivityTracker
import dev.kstep.desktop.mcp.McpRunMode
import dev.kstep.desktop.mcp.McpRunOutcome
import dev.kstep.desktop.mcp.McpServerStatus
import dev.kstep.desktop.mcp.McpSnapshotPublisher
import dev.kstep.desktop.mcp.McpViewInput
import dev.kstep.desktop.mcp.SnapshotHost
import dev.kstep.desktop.menu.AboutInfo
import dev.kstep.desktop.menu.EditAction
import dev.kstep.desktop.menu.FileAction
import dev.kstep.desktop.menu.FocusTarget
import dev.kstep.desktop.menu.HelpAction
import dev.kstep.desktop.menu.MenuContext
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.menu.ShortcutKey
import dev.kstep.desktop.menu.ShortcutRouting
import dev.kstep.desktop.menu.ViewAction
import dev.kstep.desktop.menu.aiToolbarState
import dev.kstep.desktop.menu.exportToolbarState
import dev.kstep.desktop.menu.homeToolbarState
import dev.kstep.desktop.menu.isPlainKey
import dev.kstep.desktop.menu.openToolbarState
import dev.kstep.desktop.menu.stateOf
import dev.kstep.desktop.menu.treeToolbarState
import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.okf.KnowledgeWorkspaceController
import dev.kstep.desktop.okf.PathCheck
import dev.kstep.desktop.okf.WorkspaceEntry
import dev.kstep.desktop.okf.WorkspaceState
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.CopyNames
import dev.kstep.desktop.pick.GroupInspection
import dev.kstep.desktop.pick.HoverThrottle
import dev.kstep.desktop.pick.MaskMemo
import dev.kstep.desktop.pick.MenuAction
import dev.kstep.desktop.pick.MenuModel
import dev.kstep.desktop.pick.MultiInspection
import dev.kstep.desktop.pick.PartContextMenu
import dev.kstep.desktop.pick.PartMode
import dev.kstep.desktop.pick.PickIndex
import dev.kstep.desktop.pick.SelectionEvent
import dev.kstep.desktop.pick.SelectionMachine
import dev.kstep.desktop.pick.SelectionNotices
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.ShowInScriptInput
import dev.kstep.desktop.pick.ShowInScriptMessage
import dev.kstep.desktop.pick.ShowInScriptMessages
import dev.kstep.desktop.pick.TreeViewLogic
import dev.kstep.desktop.pick.TreeViewState
import dev.kstep.desktop.pick.VisibilityCounts
import dev.kstep.desktop.pick.VisibilityMachine
import dev.kstep.desktop.pick.VisibilityScene
import dev.kstep.desktop.pick.VisibilityState
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
import dev.kstep.desktop.run.PackagedHint
import dev.kstep.desktop.run.PreviewOrigin
import dev.kstep.desktop.run.SandboxNotice
import dev.kstep.desktop.run.UserSandboxState
import dev.kstep.desktop.run.sandboxNotice
import dev.kstep.desktop.settings.LayoutPreferences
import dev.kstep.desktop.settings.LayoutPreferencesStore
import dev.kstep.desktop.status.AgentNotice
import dev.kstep.desktop.status.PreviewOriginLabel
import dev.kstep.desktop.status.StatusSegment
import dev.kstep.desktop.status.StatusSegments
import dev.kstep.desktop.status.previewOriginLabel
import dev.kstep.geometry.TriangleMesh
import dev.kstep.viewer.camera.ViewerCameraState
import dev.kstep.viewer.ui.CanvasHighlight
import dev.kstep.viewer.ui.CanvasPicking
import dev.kstep.viewer.ui.CanvasVisibility
import dev.kstep.viewer.ui.OcctInstallHint
import dev.kstep.viewer.ui.ShapeCanvas
import dev.kstep.viewer.ui.UnavailableNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
private const val DIMMED_PICTURE_ALPHA = 0.6f
private const val EDITOR_DIVIDER_DP = 7 // the 1 dp line and the 6 dp drag handle between editor and viewer

/**
 * The whole window content: toolbar, script editor | canvas with badge and progress line, error bar (ADR-0051,
 * ADR-0052). Opening a file and closing the window first ask about unsaved changes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FrameWindowScope.DesktopApp(
    controller: LiveRunController,
    session: EditorSession,
    initialRequest: StartRequest,
    window: Frame,
    ai: AiController,
    exporter: ExportController,
    knowledge: KnowledgeWorkspaceController,
    mcp: DesktopMcpServerManager,
    mcpHost: SnapshotHost,
    mcpActivity: McpActivityTracker,
    mcpActions: McpActionHost,
    onQuit: () -> Unit,
    userSandbox: StateFlow<UserSandboxState> = MutableStateFlow(UserSandboxState.Unknown),
    resolveUserSandbox: () -> UserSandboxState = { userSandbox.value },
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
    // a notice an agent caused is shorter; the nonce restarts the timer when the same text comes again (ADR-0097)
    var noticeNonce by remember { mutableStateOf(0) }
    LaunchedEffect(transientNotice, noticeNonce) {
        if (transientNotice != null) {
            val agent = transientNotice == AgentNotice.EDITED || transientNotice == AgentNotice.RUN
            delay(if (agent) AgentNotice.DURATION_MS else NOTICE_MS)
            transientNotice = null
        }
    }
    var dragHint by remember { mutableStateOf<DropHint?>(null) }
    var pendingNote by remember { mutableStateOf<Path?>(null) }
    var pendingRoot by remember { mutableStateOf<Path?>(null) }
    // a note opened on its own (drop, start argument, Recent) becomes a Recent entry once it is shown (ADR-0083)
    var recentNoteCandidate by remember { mutableStateOf<RecentEntry?>(null) }
    var pendingSawScan by remember { mutableStateOf(false) }
    var pendingErrorBefore by remember { mutableStateOf<DesktopError?>(null) }
    // AI panel (ADR-0056): hidden by default; nothing is sent before the user asks and agrees.
    val aiUi by ai.ui.collectAsState()
    val aiRepair by ai.repair.collectAsState()
    var aiVisible by remember { mutableStateOf(false) }
    // The MCP server (ADR-0095): its dialog, the "MCP" status segment and the quiet light while an agent is calling.
    var mcpDialogVisible by remember { mutableStateOf(false) }
    val mcpStatus by mcp.status.collectAsState()
    val mcpAllowActions by mcp.allowActions.collectAsState()
    val mcpLastLit by mcpActivity.lastLitAtMs.collectAsState()
    var mcpLit by remember { mutableStateOf(false) }
    LaunchedEffect(mcpLastLit) {
        if (McpActivityTracker.isLit(mcpLastLit, System.currentTimeMillis())) {
            mcpLit = true
            delay(McpActivityTracker.LIT_MS)
            mcpLit = false
        }
    }
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
    var helpDialog by remember { mutableStateOf<HelpAction?>(null) }
    val aboutInfo = remember { AboutInfo.of(kstepVersion()) }
    LaunchedEffect(Unit) { installMacAboutHandler { helpDialog = HelpAction.ABOUT } }
    var exportVisible by remember { mutableStateOf(false) }
    val convertHost = rememberConvertHost(window)
    val convertState by convertHost.state.collectAsState()
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
    var editorFocusTarget by remember { mutableStateOf(false) }
    var textSelected by remember { mutableStateOf(false) }
    var clipboardHasText by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val refreshClipboard: () -> Unit = { scope.launch { clipboardHasText = ClipboardText.hasText(clipboard) } }
    LaunchedEffect(windowFocused) {
        editorFocusTarget = FocusTarget.next(editorFocusTarget, editorFocused, windowFocused)
        if (windowFocused) clipboardHasText = ClipboardText.hasText(clipboard)
    }
    var editorCommand by remember { mutableStateOf<EditorCommand?>(null) }
    var editorCommandNonce by remember { mutableStateOf(0) }
    var jump by remember { mutableStateOf<EditorJump?>(null) }
    var jumpNonce by remember { mutableStateOf(0) }
    val layoutStore = remember { LayoutPreferencesStore() }
    val initialLayout = remember { layoutStore.load() }
    var editorFraction by remember { mutableStateOf(initialLayout.editorFraction.toFloat()) }
    var softWrap by remember { mutableStateOf(initialLayout.softWrap) }
    var previewUnsaved by remember { mutableStateOf(initialLayout.previewUnsaved) }
    var bannerFocusSignal by remember { mutableStateOf(0) }
    // All three settings share one file: every save carries the current value of the other one (ADR-0085).
    val saveLayout: () -> Unit = {
        val preferences = LayoutPreferences(editorFraction.toDouble(), softWrap, previewUnsaved)
        scope.launch(Dispatchers.IO) { layoutStore.save(preferences) }
    }
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
    // Context menu (ADR-0092): one request at a time, from the canvas or from the tree.
    val menuRequest = remember { mutableStateOf<MenuRequest?>(null) }
    val canvasFocus = remember { FocusRequester() }
    val treeFocus = remember { FocusRequester() }
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
    val visibilityHolder = remember { mutableStateOf(VisibilityState()) }
    var visibilityState by visibilityHolder
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
        menuRequest.value = null
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
                DesktopPicking(
                    pickIndex,
                    parts,
                    display?.generation ?: -1L,
                    selection,
                    visibilityView,
                    tree = tree,
                    menu = menuRequest,
                    onNotice = { transientNotice = SelectionNotices.text(it) },
                )
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
    // The viewer sits on the surface colour of the Material scheme chosen in `MaterialTheme(...)` below; the
    // outline halo (ADR-0093) is drawn in that colour so the line stays readable over any part colour.
    val viewerBackground = if (dark) darkColorScheme().surface else lightColorScheme().surface
    // The multi-selection mask is built when the selection changes, never on a hover (the list instance is kept).
    val maskMemo = remember(parts) { MaskMemo() }
    val multiMask =
        remember(parts) {
            derivedStateOf<BooleanArray?> { parts?.let { maskMemo.of(selection.value, it) } }
        }
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
                        selectedMask = multiMask.value,
                        haloRgb =
                            doubleArrayOf(
                                viewerBackground.red.toDouble(),
                                viewerBackground.green.toDouble(),
                                viewerBackground.blue.toDouble(),
                            ),
                    )
                }
            }
        }
    val multiSelected = selection.value.more.isNotEmpty()
    val selectedPart: PartInfo? =
        parts?.takeIf { !multiSelected }?.let { table ->
            SelectionMachine.selectedIndex(selection.value, table).takeIf { it >= 0 }?.let { table.parts[it] }
        }
    val multiInfo =
        if (multiSelected && parts != null) {
            MultiInspection.of(SelectionMachine.partIndices(selection.value, tree, parts), parts)
        } else {
            null
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
    // [root]: the workspace folder a Recent note was opened under; without it the note's own folder is used
    val openDroppedNote: (Path, Path?) -> Boolean = { note, explicitRoot ->
        val open = workspace as? WorkspaceState.Open
        if (open != null && note.startsWith(open.scan.root)) {
            val found = PendingNote.resolve(note, open.scan.root, open, false, false)
            if (found is PendingResolution.Open) {
                recentNoteCandidate = RecentEntry(note, RecentKind.NOTE, open.scan.root)
                openNote(found.entry)
                true
            } else {
                actionError = DropEvaluator.reject(notInFolderText(note))
                false
            }
        } else {
            val parent = explicitRoot ?: note.parent
            if (parent != null && openFolderPath(parent)) {
                recentNoteCandidate = RecentEntry(note, RecentKind.NOTE, parent)
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
                when (entry.kind) {
                    RecentKind.SCRIPT -> openPath(entry.path)
                    RecentKind.FOLDER -> openFolderPath(entry.path)
                    RecentKind.NOTE -> openDroppedNote(entry.path, entry.root)
                }
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
                    is DropAction.OpenNote -> openDroppedNote(action.path, null).also { if (it) actionError = null }
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
        val candidate = recentNoteCandidate
        if (candidate != null &&
            path.toAbsolutePath().normalize() == candidate.path.toAbsolutePath().normalize()
        ) {
            recentNoteCandidate = null
            val ok = withContext(Dispatchers.IO) { RecentOpenCheck.checkPath(path, false) is RecentCheck.Ok }
            if (ok) updateRecent { it.touch(candidate) }
            return@LaunchedEffect
        }
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
        when (val request = initialRequest) {
            StartRequest.None -> session.lookForDraftsElsewhere()
            is StartRequest.Open -> {
                // the same path as a drop: type, ending and links are judged by DropEvaluator
                handleDrop(listOf(request.path))
            }
            is StartRequest.Rejected -> actionError = request.error
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
    // One expression for the toolbar and the menu: they must never disagree.
    val exportEnabled = !knowledgeOpen && exportScript != null && editorView.state is EditorState.Editing
    val openExport: () -> Unit = {
        val canOpenExport =
            exportScript != null &&
                editorView.state is EditorState.Editing &&
                !exportRunning &&
                convertState is ConvertState.Idle
        if (canOpenExport) {
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
    val showInScript: (String, Int) -> Unit = { name, part ->
        if (showInScriptReason(name) == null) {
            val snapshot = editorView
            val text = snapshot.text
            val differs = snapshot.dirty
            // ADR-0091: the part's script line is only trusted against the exact text that was evaluated
            val info = parts?.parts?.getOrNull(part)?.takeIf { it.name == name }
            val usable = ShowInScriptInput.positionUsable(info, display?.sourceText, text)
            val sameCount = parts?.countOfName(name) ?: 0
            showNotice = null
            scope.launch {
                val resolution =
                    withContext(Dispatchers.Default) {
                        PartSourceResolver.resolve(text, name, info?.sourceLine, usable, sameCount)
                    }
                // the buffer may have changed while searching: offsets would no longer be valid
                if (session.view.value.revision == snapshot.revision) {
                    val range =
                        when (resolution) {
                            is PartSourceResolution.Name -> resolution.start to resolution.end
                            is PartSourceResolution.Line -> resolution.start to resolution.end
                            is PartSourceResolution.Heuristic -> null
                        }
                    if (range != null && session.requestSelection(range.first, range.second, snapshot.revision)) {
                        jumpNonce++
                        jump = EditorJump(CaretMapper.lineColumn(text, range.first).first, jumpNonce)
                    }
                    ShowInScriptMessages.of(resolution, name, differs)?.let { showNotice = name to it }
                }
            }
        }
    }
    val showInScriptUi: (String, Int) -> ShowInScriptUi = { name, part ->
        val reason = showInScriptReason(name)
        ShowInScriptUi(
            enabled = reason == null,
            disabledReason = reason,
            onClick = { showInScript(name, part) },
            message = showNotice?.takeIf { it.first == name }?.second,
            onFind = { query ->
                findNonce++
                findRequest = FindRequest(query, findNonce)
            },
        )
    }
    // Context menu actions (ADR-0092). The selection is not changed by Hide, Isolate or Show All.
    val menuPartsOrNull = parts
    val menuModel: MenuModel? =
        menuRequest.value?.let { req ->
            if (menuPartsOrNull == null || visibilityScene == null) {
                null
            } else {
                val groupName =
                    selection.value.selectedGroup
                        ?.let {
                            tree?.nodeOfKey(
                                it,
                            )
                        }?.takeIf { it >= 0 }
                        ?.let { tree?.name(it) }
                PartContextMenu.build(
                    selection.value,
                    menuPartsOrNull,
                    tree,
                    visibilityState,
                    visibilityScene,
                    showInScriptReason(selection.value.selected ?: groupName ?: ""),
                    req.overPart,
                )
            }
        }
    val onMenuAction: (MenuAction) -> Unit = { action ->
        val table = parts
        val sel = selection.value
        val indices = SelectionMachine.partIndices(sel, tree, table)
        val groupNode = sel.selectedGroup?.let { tree?.nodeOfKey(it) } ?: -1
        when (action) {
            MenuAction.ShowInScript ->
                if (groupNode >= 0 && tree != null) {
                    showInScript(tree.name(groupNode), -1)
                } else if (sel.selected != null && table != null) {
                    showInScript(sel.selected, table.indexOfName(sel.selected, sel.occurrence))
                }
            MenuAction.Hide ->
                visibilityScene?.let {
                    visibilityState =
                        VisibilityMachine.hideParts(visibilityState, it, indices)
                }
            MenuAction.Isolate ->
                visibilityScene?.let { visibilityState = VisibilityMachine.isolateParts(visibilityState, it, indices) }
            MenuAction.ShowAll -> visibilityState = VisibilityMachine.showAll(visibilityState)
            MenuAction.CopyName -> {
                val text =
                    when {
                        groupNode >= 0 && tree != null -> CopyNames.text(tree.name(groupNode))
                        table != null -> CopyNames.text(sel.parts, table)
                        else -> ""
                    }
                // only ever on this user action
                scope.launch {
                    if (!ClipboardText.write(
                            clipboard,
                            text,
                        )
                    ) {
                        transientNotice = ClipboardNotices.NOT_COPIED
                    }
                }
            }
            MenuAction.SelectChildren ->
                if (groupNode >= 0 && tree != null && table != null) {
                    val refs = SelectionMachine.groupRefs(tree, groupNode, table)
                    selection.value =
                        SelectionMachine.reduce(
                            selection.value,
                            SelectionEvent.SelectParts(refs, tree.leafCount(groupNode)),
                        )
                }
        }
    }
    val home: () -> Unit = {
        camera.value = ViewerCameraState.HOME
        fitReference = displayMesh
    }

    val menuOs = remember { MenuOs.current() }
    val menuContext =
        MenuContext(
            scriptOpen = state.file != null && editorView.state is EditorState.Editing,
            knowledgeOpen = knowledgeOpen,
            exportEnabled = exportEnabled,
            editorOpen = editorView.state is EditorState.Editing,
            editorFocused = editorFocused,
            canUndo = editorView.canUndo,
            canRedo = editorView.canRedo,
            fileOpen = state.file != null,
            visibilityAvailable = visibilityScene != null,
            hasSelection = hasTarget,
            nothingChanged = !somethingChanged,
            textSelected = textSelected,
            clipboardHasText = clipboardHasText,
            editorFocusTarget = editorFocusTarget,
            convertRunning = convertState is ConvertState.Running,
            exportDialogVisible = exportVisible,
            convertDialogVisible = convertState !is ConvertState.Idle,
            softWrap = softWrap,
            previewUnsaved = previewUnsaved,
            bannerHasActions = BannerActions.hasActions(editorView),
            editorIsScript = !(knowledgeOpen && state.file?.let { knowledge.isWorkspaceFile(it) } == true),
            editorDirty = editorView.dirty,
            draftOfferOpen = editorView.draftOffer != null,
        )
    // The tools of the MCP server only read this snapshot; the window never waits for them (ADR-0095).
    val mcpPublisher = remember(mcpHost) { McpSnapshotPublisher(mcpHost) }
    LaunchedEffect(mcpPublisher, editorView, state, knowledgeOpen, menuContext.editorIsScript) {
        mcpPublisher.onEditorOrRun(editorView, state, knowledgeOpen, menuContext.editorIsScript)
    }
    // Selection, visibility and settings are read here, outside the composition, and without hover (ADR-0096 D2):
    // a pointer move changes nothing that is compared, so it neither publishes nor recomposes.
    val softWrapNow = rememberUpdatedState(softWrap)
    val previewUnsavedNow = rememberUpdatedState(previewUnsaved)
    LaunchedEffect(mcpPublisher) {
        snapshotFlow {
            McpViewInput.of(selection.value, visibilityState, softWrapNow.value, previewUnsavedNow.value)
        }.distinctUntilChanged().collect { mcpPublisher.onView(it) }
    }
    // The action tools of the MCP server reach the window through this port (ADR-0097); it is set while the window lives.
    val menuContextNow = rememberUpdatedState(menuContext)
    val knowledgeOpenNow = rememberUpdatedState(knowledgeOpen)
    val partsNowState = rememberUpdatedState(parts)
    val treeNowState = rememberUpdatedState(tree)
    val sceneNowState = rememberUpdatedState(visibilityScene)
    val displayNowState = rememberUpdatedState(display)
    DisposableEffect(mcpActions, session, controller) {
        mcpActions.port =
            DesktopMcpActionPort(
                session = session,
                menuContext = { menuContextNow.value },
                knowledgeOpen = { knowledgeOpenNow.value },
                runStarter = { mode ->
                    when (mode) {
                        McpRunMode.SAVED -> controller.forceRun()
                        McpRunMode.UNSAVED -> {
                            val editing = session.view.value.state as? EditorState.Editing
                            if (editing != null) controller.runBuffer(editing.path, editing.text)
                        }
                    }
                },
                generation = { controller.state.value.generation },
                runAwaiter = { after, timeoutMs ->
                    val ended =
                        runBlocking {
                            withTimeoutOrNull(timeoutMs) {
                                controller.state.first {
                                    it.generation > after && it !is DesktopState.Running && it !is DesktopState.Idle
                                }
                            }
                        }
                    when (ended) {
                        null -> null
                        is DesktopState.Error -> McpRunOutcome.Failed(ended.errors.size)
                        is DesktopState.NativeUnavailable -> McpRunOutcome.Failed(0)
                        else -> McpRunOutcome.Finished
                    }
                },
                selectionGet = { selection.value },
                selectionSet = { selection.value = it },
                visibilityGet = { visibilityHolder.value },
                visibilitySet = { visibilityHolder.value = it },
                sceneNow = {
                    val p = partsNowState.value
                    val vs = sceneNowState.value
                    if (p != null && vs != null) McpActionScene(p, treeNowState.value, vs) else null
                },
                partsNow = { partsNowState.value },
                displaySourceText = { displayNowState.value?.sourceText },
                jumpTo = { line ->
                    jumpNonce++
                    jump = EditorJump(line, jumpNonce, focusEditor = false)
                },
                sandboxed = { resolveUserSandbox() is UserSandboxState.Active },
                autoPreview = { previewUnsavedNow.value },
                notice = { kind ->
                    transientNotice = if (kind == AgentNoticeKind.EDITED) AgentNotice.EDITED else AgentNotice.RUN
                    noticeNonce++
                },
            )
        onDispose { mcpActions.port = null }
    }
    AutoPreviewDriver(
        enabled = previewUnsaved,
        state = state,
        editorView = editorView,
        windowFocused = windowFocused,
        isScript = menuContext.editorIsScript && !knowledgeOpen,
        onRun = { file, text -> controller.runBuffer(file, text, auto = true) },
        onTooLarge = { file -> controller.bufferTooLarge(file) },
    )
    val onEditAction: (EditAction) -> Unit = {
        editorCommandNonce++
        val kind =
            when (it) {
                EditAction.UNDO -> {
                    session.undo()
                    EditorCommandKind.REFOCUS
                }
                EditAction.REDO -> {
                    session.redo()
                    EditorCommandKind.REFOCUS
                }
                EditAction.CUT -> EditorCommandKind.CUT
                EditAction.COPY -> EditorCommandKind.COPY
                EditAction.PASTE -> EditorCommandKind.PASTE
                EditAction.SELECT_ALL -> EditorCommandKind.SELECT_ALL
                EditAction.FIND -> EditorCommandKind.FIND
                EditAction.REPLACE -> EditorCommandKind.REPLACE
                EditAction.GO_TO_LINE -> EditorCommandKind.GO_TO
            }
        // Undo and Redo hand the focus back to the editor (a click on a menu may have taken it, ADR-0074).
        editorCommand = EditorCommand(kind, editorCommandNonce)
    }
    val onViewAction: (ViewAction) -> Unit = {
        when (it) {
            ViewAction.RUN -> controller.forceRun()
            ViewAction.RUN_UNSAVED -> {
                // judged again here: the menu state may be a moment old when the key arrives
                val editing = editorView.state as? EditorState.Editing
                if (editing != null && stateOf(ViewAction.RUN_UNSAVED, menuContext).enabled) {
                    controller.runBuffer(editing.path, editing.text)
                }
            }
            ViewAction.HOME -> home()
            ViewAction.PREVIOUS_BLOCK -> jumpBlock(false)
            ViewAction.NEXT_BLOCK -> jumpBlock(true)
            ViewAction.TRANSPARENT -> applyVisibility(target, VisibilityMachine::toggleTransparent)
            ViewAction.HIDE -> applyVisibility(target, VisibilityMachine::toggleHidden)
            ViewAction.ISOLATE -> applyVisibility(target, VisibilityMachine::isolate)
            ViewAction.SHOW_ALL -> visibilityState = VisibilityMachine.showAll(visibilityState)
            ViewAction.AI_PANEL -> {
                aiVisible = true
                aiFocus++
            }
            ViewAction.MCP_SERVER -> mcpDialogVisible = true
            ViewAction.SOFT_WRAP -> {
                softWrap = !softWrap
                saveLayout()
            }
            ViewAction.PREVIEW_UNSAVED -> {
                // judged again here, like Run Unsaved: switching on is refused where the preview is saved-only
                if (stateOf(ViewAction.PREVIEW_UNSAVED, menuContext).enabled || previewUnsaved) {
                    previewUnsaved = !previewUnsaved
                    saveLayout()
                }
            }
            ViewAction.FOCUS_BANNER -> bannerFocusSignal++
        }
    }
    val onFileAction: (FileAction) -> Unit = {
        when (it) {
            FileAction.OPEN -> openFile()
            FileAction.OPEN_FOLDER -> chooseFolder()
            FileAction.CLOSE_WORKSPACE -> closeWorkspace()
            FileAction.SAVE -> session.save()
            FileAction.EXPORT -> openExport()
            FileAction.CONVERT -> convertHost.open(convertHost.prefill(state.file, openRoot))
            FileAction.QUIT -> onQuit()
        }
    }
    FileMenuBar(
        os = menuOs,
        ctx = menuContext,
        recent = recent,
        onAction = onFileAction,
        onRecent = openFromRecent,
        onClearRecent = { updateRecent { it.clear() } },
        onEdit = onEditAction,
        onView = onViewAction,
        onHelp = { helpDialog = it },
    )

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(
            Modifier
                .fillMaxSize()
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val command = event.isMetaPressed || event.isCtrlPressed
                    // The shortcuts of the File, Edit and View entries belong to the menu (ADR-0072, ADR-0074): never run here.
                    val menuRoute =
                        menuKey(event.key)?.let { key ->
                            ShortcutRouting.route(
                                key,
                                if (menuOs == MenuOs.MAC) event.isMetaPressed else event.isCtrlPressed,
                                event.isShiftPressed,
                                event.isAltPressed,
                                menuOs,
                                menuContext,
                            )
                        }
                    when {
                        menuRoute is ShortcutRouting.Route.PassToMenu -> false
                        menuRoute is ShortcutRouting.Route.ShowReason -> {
                            transientNotice = menuRoute.reason
                            true
                        }
                        isPlainKey(command, event.isAltPressed) && !editorFocused && event.key == Key.H -> {
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
            when (helpDialog) {
                HelpAction.SHORTCUTS -> ShortcutsDialog(menuOs) { helpDialog = null }
                HelpAction.ABOUT -> AboutDialog(aboutInfo) { helpDialog = null }
                null -> Unit
            }
            convertHost.ConvertDialogHost()
            if (mcpDialogVisible) McpServerDialog(mcp) { mcpDialogVisible = false }
            if (exportVisible) {
                ExportDialog(
                    sandboxNotice = sandboxNotice,
                    state = exportState,
                    context =
                        ExportDialogContext(
                            scriptName = exportScript?.fileName?.toString().orEmpty(),
                            dirty = editorView.dirty,
                            previewIsBuffer = state.lastGood?.origin is PreviewOrigin.Buffer,
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
                        menuContext = menuContext,
                        knowledgeOpen = knowledgeOpen,
                        knowledgeLabel =
                            (workspace as? WorkspaceState.Open)?.scan?.root?.let {
                                "Knowledge · " + DisplayName.escaped(it.fileName?.toString() ?: it.toString(), 48)
                            },
                        transientNotice = transientNotice,
                        sandboxNotice = sandboxNotice,
                        exportNotice = exportNotice,
                        onShowInFolder = { exportNotice?.let { showInFolder(it.dir) } },
                        onHome = home,
                        treeAvailable = treeAvailable,
                        treeOn = treeVisible,
                        onTree = { treeVisible = !treeVisible },
                        visibilityEnabled = visibilityScene != null,
                        counts = counts ?: VisibilityCounts(0, 0),
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
                        mcpRunning = mcpStatus as? McpServerStatus.Running,
                        mcpEditable = mcpAllowActions,
                        mcpLit = mcpLit,
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
                            // marks follow the open file only: an error list of another file must not be pinned here
                            if (state.file == editorView.state.path) errors else emptyList(),
                            dark,
                            jump,
                            onFocusChanged = {
                                editorFocused = it
                                editorFocusTarget = FocusTarget.next(editorFocusTarget, it, windowFocused)
                                if (it) refreshClipboard()
                            },
                            onSelectionChanged = { textSelected = it },
                            onClipboardChanged = { clipboardHasText = it },
                            onNotice = { transientNotice = it },
                            onOpenDraft = { path ->
                                // the path comes from a file that could have been planted: judge it like any other
                                val name =
                                    path.fileName
                                        ?.toString()
                                        ?.lowercase()
                                        .orEmpty()
                                if (path.isAbsolute && path == path.normalize() && name.endsWith(".kstep.kts")) {
                                    openPath(path)
                                }
                            },
                            modifier = Modifier.width(windowWidth * effectiveFraction).fillMaxHeight(),
                            errorLine = selectedError?.line,
                            softWrap = softWrap,
                            bannerFocusSignal = bannerFocusSignal,
                            errorBase = ((state as? DesktopState.Error)?.origin as? PreviewOrigin.Buffer)?.text,
                            findRequest = findRequest,
                            editorCommand = editorCommand,
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
                                    onDragStopped = { saveLayout() },
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
                            Box(Modifier.fillMaxHeight()) {
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
                                        applyVisibility(
                                            tree.firstPart(node)..tree.lastPart(node),
                                            VisibilityMachine::cycle,
                                        )
                                    },
                                    onCycleSelected = { applyVisibility(target, VisibilityMachine::cycle) },
                                    modifier = Modifier.fillMaxHeight(),
                                    onShowInScript = showInScript,
                                    multiMask = multiMask,
                                    onContext = { menuRequest.value = it },
                                    focusRequester = treeFocus,
                                )
                                menuRequest.value?.takeIf { it.origin == MenuOrigin.Tree }?.let { req ->
                                    menuModel?.let { model ->
                                        MenuAnchor(req) {
                                            PartContextMenuPopup(
                                                initial = model,
                                                onAction = onMenuAction,
                                                onDismiss = { menuRequest.value = null },
                                                restoreFocus = { runCatching { treeFocus.requestFocus() } },
                                            )
                                        }
                                    }
                                }
                            }
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
                                // while an unsaved buffer is being run the old picture is dimmed (ADR-0086)
                                val dimmed =
                                    (state as? DesktopState.Running)?.let {
                                        it.origin is PreviewOrigin.Buffer && it.lastGood != null
                                    } == true
                                Box(Modifier.fillMaxSize().alpha(if (dimmed) DIMMED_PICTURE_ALPHA else 1f)) {
                                    Canvas(
                                        state,
                                        display,
                                        camera,
                                        fitReference,
                                        openFile,
                                        highlight,
                                        picking,
                                        canvasVisibility,
                                        canvasFocus,
                                        if (workspace is WorkspaceState.None) recent.entries else emptyList(),
                                        openFromRecent,
                                    )
                                }
                                Badge(
                                    state,
                                    panelShown =
                                        selectedPart != null || multiInfo != null || selectedGroup != null,
                                )
                                selectedPart?.let {
                                    InspectionPanel(
                                        it,
                                        MaterialTheme.colorScheme.background,
                                        Modifier.align(Alignment.TopEnd).padding(ViewerOverlayLayout.MARGIN_DP.dp),
                                        action = showInScriptUi(it.name, parts?.parts?.indexOf(it) ?: -1),
                                    )
                                }
                                multiInfo?.let {
                                    val reason = PartContextMenu.reasonSeveralParts(it.partCount)
                                    MultiInspectionPanel(
                                        it,
                                        MultiInspection.nameLines(selection.value.parts),
                                        MaterialTheme.colorScheme.background,
                                        Modifier.align(Alignment.TopEnd).padding(ViewerOverlayLayout.MARGIN_DP.dp),
                                        action = ShowInScriptUi(enabled = false, disabledReason = reason),
                                    )
                                }
                                menuRequest.value?.takeIf { it.origin == MenuOrigin.Viewer }?.let { req ->
                                    menuModel?.let { model ->
                                        MenuAnchor(req) {
                                            PartContextMenuPopup(
                                                initial = model,
                                                onAction = onMenuAction,
                                                onDismiss = { menuRequest.value = null },
                                                restoreFocus = { runCatching { canvasFocus.requestFocus() } },
                                            )
                                        }
                                    }
                                }
                                selectedGroup?.let {
                                    GroupInspectionPanel(
                                        it,
                                        MaterialTheme.colorScheme.background,
                                        Modifier.align(Alignment.TopEnd).padding(ViewerOverlayLayout.MARGIN_DP.dp),
                                        action = showInScriptUi(it.name, -1),
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
    menuContext: MenuContext,
    exportNotice: ExportNotice.Notice?,
    onShowInFolder: () -> Unit,
    onHome: () -> Unit,
    treeAvailable: Boolean,
    treeOn: Boolean,
    onTree: () -> Unit,
    visibilityEnabled: Boolean,
    counts: VisibilityCounts,
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
    transientNotice: String?,
    sandboxNotice: SandboxNotice? = null,
    mcpRunning: McpServerStatus.Running? = null,
    mcpEditable: Boolean = false,
    mcpLit: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.testTag("toolbar-buttons"),
            horizontalArrangement = Arrangement.spacedBy(TOOLBAR_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTooltipButton(openToolbarState(menuContext), onOpen)
            IconTooltipButton(exportToolbarState(menuContext), onExport)
            ToolbarDivider()
            IconTooltipButton(homeToolbarState(menuContext), onHome)
            if (treeAvailable) IconTooltipButton(treeToolbarState(treeOn), onTree)
            IconTooltipButton(aiToolbarState(menuContext, aiOn), onAi)
            if (visibilityEnabled) {
                ToolbarDivider()
                VisibilityControls(menuContext, counts, alphaStep, onShowAll, onIsolate, onHide, onAlpha)
            }
        }
        StatusLine(
            fileName = state.file?.fileName?.toString(),
            exportText = exportNotice?.text?.takeIf { !knowledgeOpen },
            onShowInFolder = onShowInFolder,
            sandboxNotice = sandboxNotice,
            statusSegments =
                statusSegmentsFor(
                    state,
                    knowledgeLabel,
                    transientNotice,
                    aiStatus,
                    counts,
                    editor.dirty,
                    mcpRunning?.let { "MCP" },
                    mcpEditable && mcpRunning != null,
                ),
            previewLabel =
                previewOriginLabel(state.lastGood?.origin, editor.text.takeIf { editor.state is EditorState.Editing })
                    ?.takeIf { editor.dirty || it != PreviewOriginLabel.SAVED },
            accent = if (isSystemInDarkTheme()) ACCENT_DARK else ACCENT_LIGHT,
            mcpLit = mcpLit,
            mcpTooltip =
                mcpRunning?.let {
                    "MCP server running on 127.0.0.1:${it.port}" +
                        (if (mcpEditable) " — agents may make changes" else "") + " — View › MCP Server…"
                },
        )
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** The run status as segments (ADR-0094); the state text is made here, the status package only gets plain values. */
private fun statusSegmentsFor(
    state: DesktopState,
    knowledgeLabel: String?,
    transientNotice: String?,
    aiStatus: String?,
    counts: VisibilityCounts,
    dirty: Boolean,
    mcp: String? = null,
    mcpEditable: Boolean = false,
): List<StatusSegment> {
    val ok = state as? DesktopState.Ok
    val runState =
        when (state) {
            DesktopState.Idle -> null
            is DesktopState.Running -> "● Running…"
            is DesktopState.Ok -> "● Ok"
            is DesktopState.Error -> "● Error"
            is DesktopState.NativeUnavailable -> "● Geometry unavailable"
            is DesktopState.Waiting -> state.hint
        }
    return StatusSegments.build(
        knowledgeLabel = knowledgeLabel,
        runState = runState,
        durationMs = ok?.model?.durationMs,
        triangles = ok?.model?.triangles,
        triangleWarning = ok?.let { ErrorBarText.triangleWarning(it.model.triangles) },
        note = ok?.model?.notes?.firstOrNull(),
        transientNotice = transientNotice,
        aiStatus = aiStatus,
        hidden = counts.hidden,
        transparent = counts.transparent,
        dirty = dirty,
        mcp = mcp,
        mcpEditable = mcpEditable,
    )
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
    canvasFocus: FocusRequester,
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
                installHint = if (PackagedHint.active()) PackagedHint.lines() else OcctInstallHint.forCurrentOs(),
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
        else -> ShapeCanvas(display.mesh, modifier, camera, fitReference, highlight, picking, visibility, canvasFocus)
    }
}

/** Marks a canvas that shows an outdated result: a yellow dot and the time of the last good run (Zhuo). */
@Composable
private fun Badge(
    state: DesktopState,
    panelShown: Boolean,
) {
    val error = state as? DesktopState.Error ?: return
    val lastGood = error.lastGood ?: return
    BadgeBox(ErrorBarText.lastGoodBadge(lastGood.finishedAt), panelShown)
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

/** The key of the menu shortcuts (letters and arrows); any other key is not menu-owned. */
private fun menuKey(key: Key): ShortcutKey? =
    when (key) {
        Key.O -> ShortcutKey.Letter('O')
        Key.S -> ShortcutKey.Letter('S')
        Key.E -> ShortcutKey.Letter('E')
        Key.Z -> ShortcutKey.Letter('Z')
        Key.Y -> ShortcutKey.Letter('Y')
        Key.F -> ShortcutKey.Letter('F')
        Key.H -> ShortcutKey.Letter('H')
        Key.L -> ShortcutKey.Letter('L')
        Key.R -> ShortcutKey.Letter('R')
        Key.K -> ShortcutKey.Letter('K')
        Key.X -> ShortcutKey.Letter('X')
        Key.C -> ShortcutKey.Letter('C')
        Key.V -> ShortcutKey.Letter('V')
        Key.A -> ShortcutKey.Letter('A')
        Key.DirectionUp -> ShortcutKey.Up
        Key.DirectionDown -> ShortcutKey.Down
        else -> null
    }

internal fun isMac(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("mac")

/**
 * A native folder dialog; `null` if cancelled. On macOS the AWT file dialog only chooses folders with a system
 * property, which is reset right after (or Open... would stop choosing scripts); elsewhere a Swing chooser.
 */
internal fun chooseFolderDialog(
    parent: Frame,
    title: String = "Open a folder of Markdown notes",
): Path? {
    if (isMac()) {
        val key = "apple.awt.fileDialogForDirectories"
        System.setProperty(key, "true")
        try {
            val dialog = FileDialog(parent, title, FileDialog.LOAD)
            dialog.isVisible = true
            val name = dialog.file ?: return null
            return Path.of(dialog.directory ?: "", name)
        } finally {
            System.setProperty(key, "false")
        }
    }
    val chooser = javax.swing.JFileChooser()
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = title
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
