package dev.kstep.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.aiwire.AiController
import dev.kstep.desktop.edit.CaretMapper
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.GroupInspection
import dev.kstep.desktop.pick.HoverThrottle
import dev.kstep.desktop.pick.PartMode
import dev.kstep.desktop.pick.PickIndex
import dev.kstep.desktop.pick.SelectionEvent
import dev.kstep.desktop.pick.SelectionMachine
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.TreeViewLogic
import dev.kstep.desktop.pick.TreeViewState
import dev.kstep.desktop.pick.VisibilityCounts
import dev.kstep.desktop.pick.VisibilityMachine
import dev.kstep.desktop.pick.VisibilityScene
import dev.kstep.desktop.pick.VisibilityState
import dev.kstep.desktop.pick.visibilityStatusText
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.ErrorBarText
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.geometry.TriangleMesh
import dev.kstep.viewer.camera.ViewerCameraState
import dev.kstep.viewer.ui.CanvasHighlight
import dev.kstep.viewer.ui.CanvasPicking
import dev.kstep.viewer.ui.CanvasVisibility
import dev.kstep.viewer.ui.ShapeCanvas
import dev.kstep.viewer.ui.UnavailableNotice
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

internal val DIM = Color(0xFF8A8A8A)
internal val WARN = Color(0xFFF9A825)
private const val PROGRESS_DELAY_MS = 300L
private const val AI_TICK_MS = 1_000L
private const val AI_PANEL_DP = 360
private const val AI_MIN_WINDOW_DP = 900
private const val ERROR_BAR_MAX_FRACTION = 0.4f
private const val SCRIPT_FILTER_SUFFIX = ".kstep.kts"
private const val INITIAL_EDITOR_FRACTION = 0.45f
private const val MIN_PANE_DP = 240
private const val TREE_DIVIDER_DP = 1
private const val EDITOR_DIVIDER_DP = 7 // the 1 dp line and the 6 dp drag handle between editor and viewer

/**
 * The whole window content: toolbar, script editor | canvas with badge and progress line, error bar (ADR-0051,
 * ADR-0052). Opening a file and closing the window first ask about unsaved changes.
 */
@Composable
fun DesktopApp(
    controller: LiveRunController,
    session: EditorSession,
    initialFile: Path?,
    window: Frame,
    ai: AiController,
) {
    val editorView by session.view.collectAsState()
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
    var editorFocused by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf<EditorJump?>(null) }
    var jumpNonce by remember { mutableStateOf(0) }
    var editorFraction by remember { mutableStateOf(INITIAL_EDITOR_FRACTION) }
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
    val openPath: (Path) -> Unit = { path ->
        if (confirmUnsaved(window, session)) {
            session.open(path)
            controller.open(path)
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
    val selectError: (DesktopError) -> Unit = { error ->
        val offset = CaretMapper.toOffset(editorView.text, error.line, error.column)
        if (offset != null) {
            session.requestCaret(offset)
            jumpNonce++
            jump = EditorJump(CaretMapper.lineColumn(editorView.text, offset).first, jumpNonce)
        }
    }
    val home: () -> Unit = {
        camera.value = ViewerCameraState.HOME
        fitReference = displayMesh
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val command = event.isMetaPressed || event.isCtrlPressed
                when {
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
                    command && event.key == Key.K -> {
                        aiVisible = true
                        aiFocus++
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
                                hasTarget.also { if (it) applyVisibility(target, VisibilityMachine::toggleTransparent) }
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
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val errorBarMax = maxHeight * ERROR_BAR_MAX_FRACTION
                val windowWidth = maxWidth
                Column(Modifier.fillMaxSize()) {
                    Toolbar(
                        state,
                        editorView,
                        onOpen = openFile,
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
                        aiOn = aiVisible,
                        onAi = {
                            aiVisible = !aiVisible
                            if (aiVisible) aiFocus++
                        },
                        aiStatus = aiStatusText(aiRepair, aiNow, ai.limitsInUse.maxAttempts),
                    )

                    val totalPx = with(LocalDensity.current) { windowWidth.toPx() }
                    val minFraction = (MIN_PANE_DP.dp / windowWidth).coerceIn(0.1f, 0.5f)
                    val errors = (state as? DesktopState.Error)?.errors.orEmpty()
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        ScriptEditor(
                            session,
                            editorView,
                            errors,
                            dark,
                            jump,
                            onFocusChanged = { editorFocused = it },
                            modifier = Modifier.width(windowWidth * editorFraction).fillMaxHeight(),
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
                                                (editorFraction + dx / totalPx).coerceIn(minFraction, 1f - minFraction)
                                        },
                                ),
                        )
                        // the tree needs its own width and still leaves the canvas at least the minimum pane width
                        val viewerWidth = windowWidth * (1f - editorFraction) - EDITOR_DIVIDER_DP.dp
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
                            )
                            Box(Modifier.width(TREE_DIVIDER_DP.dp).fillMaxHeight().background(DIM.copy(alpha = 0.5f)))
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            Canvas(state, display, camera, fitReference, openFile, highlight, picking, canvasVisibility)
                            Badge(state)
                            selectedPart?.let {
                                InspectionPanel(
                                    it,
                                    MaterialTheme.colorScheme.background,
                                    Modifier.align(Alignment.TopEnd).padding(12.dp),
                                )
                            }
                            selectedGroup?.let {
                                GroupInspectionPanel(
                                    it,
                                    MaterialTheme.colorScheme.background,
                                    Modifier.align(Alignment.TopEnd).padding(12.dp),
                                )
                            }
                            if (showProgress) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter))
                            }
                        }
                        if (aiVisible && windowWidth >= AI_MIN_WINDOW_DP.dp) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(DIM.copy(alpha = 0.5f)))
                            AiPanel(
                                controller = ai,
                                ui = aiUi,
                                repair = aiRepair,
                                editorText = editorView.text,
                                hasFile = editorView.state is EditorState.Editing,
                                focusNonce = aiFocus,
                                onApply = session::applyProposal,
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
                        ErrorBar(error.errors, dark, maxHeight = errorBarMax, onSelect = selectError)
                    }
                    // A non-fatal problem of the shown model, e.g. an invalid part list (KSTEP-D-012): the run stays "ok".
                    val okWarnings = (state as? DesktopState.Ok)?.model?.warnings.orEmpty()
                    if (okWarnings.isNotEmpty()) {
                        ErrorBar(okWarnings, dark, maxHeight = errorBarMax, warning = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    state: DesktopState,
    editor: EditorView,
    onOpen: () -> Unit,
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
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpen) { Text("Open", fontSize = 13.sp) }
        TextButton(onClick = onHome) { Text("Home", fontSize = 13.sp) }
        if (treeAvailable) {
            TextButton(onClick = onTree) {
                Text("Tree", fontSize = 13.sp, color = if (treeOn) Color.Unspecified else DIM)
            }
        }
        TextButton(onClick = onAi) { Text("AI", fontSize = 13.sp, color = if (aiOn) Color.Unspecified else DIM) }
        if (visibilityEnabled) {
            VisibilityControls(counts, hasSelection, alphaStep, onShowAll, onIsolate, onHide, onAlpha)
        }
        Text(
            state.file?.fileName?.toString() ?: "No file",
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                listOf(
                    statusText(state),
                    aiStatus,
                    visibilityStatusText(counts),
                ).filter { it.isNotEmpty() }.joinToString("  ·  ") +
                    if (editor.dirty) "  ·  unsaved changes  ·  preview shows saved file" else "",
                fontSize = 12.sp,
                color = DIM,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
            }
        state is DesktopState.NativeUnavailable ->
            UnavailableNotice(
                state.reason,
                modifier,
                heading = "kSTEP Desktop cannot show geometry: the native OCCT bridge is unavailable.",
            )
        display == null ->
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(if (state is DesktopState.Running) "Running the script…" else "No result yet", color = DIM)
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

/** A native file dialog for a `*.kstep.kts` script; `null` if cancelled. */
private fun chooseScript(parent: Frame): Path? {
    val dialog = FileDialog(parent, "Open a kSTEP script", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(SCRIPT_FILTER_SUFFIX) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return Path.of(dialog.directory ?: "", name)
}
