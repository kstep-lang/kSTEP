package dev.kstep.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.PartMode
import dev.kstep.desktop.pick.SelectionEvent
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.TreeKey
import dev.kstep.desktop.pick.TreeViewLogic
import dev.kstep.desktop.pick.TreeViewState
import dev.kstep.desktop.pick.VisibilityArrays
import dev.kstep.desktop.pick.VisibilityCounts
import dev.kstep.desktop.pick.visibilityHeaderText

internal val TREE_WIDTH = 240.dp
private val LABEL = Color(0xFF8A8A8A)
private val ROW_HEIGHT = 24.dp
private val HEADER_HEIGHT = 28.dp
private val INDENT = 14.dp
private val ARROW_ZONE = 16.dp
private val EYE_ZONE = 20.dp
private val ACCENT_BAR = 2.dp
private val ROW_PADDING = 8.dp
private const val MAX_INDENT_LEVEL = 10
private const val DOUBLE_CLICK_NANOS = 400_000_000L
private const val SCROLL_MARGIN_ROWS = 2
private const val DIM_ALPHA = 0.7f
private const val HIDDEN_TEXT_ALPHA = 0.45f
private const val SELECT_FOCUS_ALPHA = 0.22f
private const val SELECT_BLUR_ALPHA = 0.18f
private const val HOVER_ALPHA = 0.06f

/** The node of the current selection in [tree], `-1` when nothing (or nothing of this tree) is selected. */
internal fun selectedNodeOf(
    s: SelectionState,
    tree: AssemblyTree,
    table: PartTable,
): Int {
    if (s.selectedGroup != null) return tree.nodeOfKey(s.selectedGroup)
    val name = s.selected ?: return -1
    val part = table.indexOfName(name, s.occurrence)
    return if (part >= 0) tree.leafNodeOfPart(part) else -1
}

/**
 * The assembly tree beside the canvas (ADR-0054): rows of 24 dp, groups with a disclosure triangle, a quiet selection
 * with a 2 dp accent bar, keyboard navigation when focused. Open state, selection and the visible rows are pure logic
 * in the pick package; this file only draws them. Compose Desktop has no tree role, so rows describe themselves
 * through content and state descriptions.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun AssemblyTreePanel(
    tree: AssemblyTree,
    table: PartTable,
    view: TreeViewState,
    onView: (TreeViewState) -> Unit,
    selection: State<SelectionState>,
    dispatch: (SelectionEvent) -> Unit,
    dark: Boolean,
    background: Color,
    nodeVisibility: ByteArray?,
    counts: VisibilityCounts,
    onCycleNode: (Int) -> Unit,
    onCycleSelected: () -> Unit,
    modifier: Modifier = Modifier,
    /** A double click on a part (leaf) asks to show its name in the script (ADR-0068); groups keep fold/unfold. */
    onShowInScript: (String, Int) -> Unit = { _, _ -> },
) {
    val accent = if (dark) ACCENT_DARK else ACCENT_LIGHT
    val panelBackground = lerp(background, if (dark) Color.White else Color.Black, if (dark) 0.04f else 0.03f)
    val selectedNode by remember(tree, table) { derivedStateOf { selectedNodeOf(selection.value, tree, table) } }
    // depends on the tree and the open state only, never on selection or hover (30 hover events a second)
    val rows = remember(tree, view) { TreeViewLogic.visibleRows(tree, view) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var lastPressNode by remember { mutableStateOf(-1) }
    var lastPressAt by remember { mutableStateOf(0L) }

    fun selectNode(node: Int) {
        if (tree.isGroup(node)) {
            dispatch(SelectionEvent.SelectGroup(tree.key(node)))
        } else {
            val part = tree.partOf(node)
            dispatch(SelectionEvent.ClickAt(part, table.parts[part].name, table.occurrenceOf(part)))
        }
    }

    // a part chosen in the viewer opens its ancestors ...
    LaunchedEffect(selectedNode, tree) {
        if (selectedNode >= 0) onView(TreeViewLogic.expandAncestors(tree, view, selectedNode))
    }
    // ... and scrolls to its row, but only when the row is not completely visible
    LaunchedEffect(selectedNode, rows) {
        if (selectedNode < 0) return@LaunchedEffect
        val at = java.util.Arrays.binarySearch(rows, selectedNode)
        if (at < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == at }
        val fully =
            item != null &&
                item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset
        if (fully) return@LaunchedEffect
        val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
        val perView = maxOf(1, info.visibleItemsInfo.size - 1)
        val target =
            if (at < first) at - SCROLL_MARGIN_ROWS else at - (perView - 1 - SCROLL_MARGIN_ROWS)
        listState.scrollToItem(maxOf(0, target))
    }

    Column(
        modifier
            .width(TREE_WIDTH)
            .background(panelBackground)
            .semantics { contentDescription = "Assembly tree, ${table.parts.size} parts" }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return@onPreviewKeyEvent false
                // Spacebar cycles the visibility of the selection; without one the key stays free
                if (visibilityAction(event.key, event.isShiftPressed, false, false, false, true) ==
                    VisibilityAction.Cycle
                ) {
                    if (selectedNode < 0) return@onPreviewKeyEvent false
                    onCycleSelected()
                    return@onPreviewKeyEvent true
                }
                val key =
                    when (event.key) {
                        Key.DirectionUp -> TreeKey.Up
                        Key.DirectionDown -> TreeKey.Down
                        Key.DirectionLeft -> TreeKey.Left
                        Key.DirectionRight -> TreeKey.Right
                        Key.MoveHome -> TreeKey.Home
                        Key.MoveEnd -> TreeKey.End
                        Key.Enter, Key.NumPadEnter -> TreeKey.Enter
                        Key.Escape -> TreeKey.Escape
                        else -> return@onPreviewKeyEvent false
                    }
                // Escape only ends a selection; without one it must stay free for others
                if (key == TreeKey.Escape && selectedNode < 0) return@onPreviewKeyEvent false
                val step = TreeViewLogic.onKey(tree, view, rows, selectedNode, key)
                if (step.view !== view) onView(step.view)
                step.select?.let { selectNode(it.node) }
                if (step.clearSelection) dispatch(SelectionEvent.Escape)
                true
            }.onFocusChanged { focused = it.hasFocus }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Assembly", color = LABEL, fontSize = 11.sp, modifier = Modifier.semantics { heading() })
            val headerText = visibilityHeaderText(table.parts.size, counts)
            TooltipArea(
                tooltip = {
                    Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                        Text(headerText, color = Color.White, fontSize = 12.sp)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    headerText,
                    color = LABEL.copy(alpha = DIM_ALPHA),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (focused && selectedNode < 0) Modifier.border(1.dp, accent) else Modifier),
        ) {
            items(rows.size, key = { tree.key(rows[it]) }) { at ->
                val node = rows[at]
                TreeRow(
                    tree = tree,
                    node = node,
                    expanded = TreeViewLogic.isExpanded(tree, view, node),
                    selected = selectedNode == node,
                    panelFocused = focused,
                    accent = accent,
                    dark = dark,
                    visibility = nodeVisibility?.getOrNull(node) ?: PartMode.VISIBLE,
                    onToggle = { onView(TreeViewLogic.toggle(tree, view, node)) },
                    onEye = { onCycleNode(node) },
                    onPress = { zone, now ->
                        focusRequester.requestFocus()
                        if (zone == PressZone.Eye) {
                            // the eye changes the visibility only: no selection, no group toggle
                            onCycleNode(node)
                        } else if (zone == PressZone.Arrow) {
                            onView(TreeViewLogic.toggle(tree, view, node))
                        } else {
                            val doubleClick = lastPressNode == node && now - lastPressAt < DOUBLE_CLICK_NANOS
                            lastPressNode = node
                            lastPressAt = now
                            selectNode(node)
                            if (doubleClick) {
                                if (tree.isGroup(node)) {
                                    onView(TreeViewLogic.toggle(tree, view, node))
                                } else {
                                    onShowInScript(tree.name(node), tree.partOf(node))
                                }
                            }
                        }
                    },
                    onHover = { over ->
                        dispatch(
                            SelectionEvent.TreeHover(
                                if (over) tree.firstPart(node)..tree.lastPart(node) else null,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun TreeRow(
    tree: AssemblyTree,
    node: Int,
    expanded: Boolean,
    selected: Boolean,
    panelFocused: Boolean,
    accent: Color,
    dark: Boolean,
    visibility: Byte,
    onToggle: () -> Unit,
    onEye: () -> Unit,
    onPress: (zone: PressZone, nowNanos: Long) -> Unit,
    onHover: (Boolean) -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val isGroup = tree.isGroup(node)
    val level = tree.depth(node)
    val indent = INDENT * minOf(level, MAX_INDENT_LEVEL)
    val textColor = if (dark) Color(0xFFE6E6E6) else Color(0xFF1B1B1B)
    val density = LocalDensity.current
    val eyeStartPx = with(density) { ACCENT_BAR.toPx() }
    val eyeEndPx = with(density) { (ACCENT_BAR + EYE_ZONE).toPx() }
    val arrowStartPx = with(density) { (ACCENT_BAR + EYE_ZONE + ROW_PADDING + indent).toPx() }
    val arrowEndPx = with(density) { (ACCENT_BAR + EYE_ZONE + ROW_PADDING + indent + ARROW_ZONE).toPx() }
    val visibilityName = visibilityName(visibility)
    val rowBackground =
        when {
            selected && panelFocused -> accent.copy(alpha = SELECT_FOCUS_ALPHA)
            selected -> LABEL.copy(alpha = SELECT_BLUR_ALPHA)
            hovered -> textColor.copy(alpha = HOVER_ALPHA)
            else -> Color.Transparent
        }
    val occurrenceText =
        if (tree.hasDuplicateSiblingName(node)) ", occurrence ${tree.occurrence(node) + 1}" else ""
    val description =
        if (isGroup) {
            "${tree.name(node)}$occurrenceText, assembly, ${tree.leafCount(node)} parts, " +
                "${if (expanded) "expanded" else "collapsed"}, level ${level + 1}"
        } else {
            "${tree.name(node)}$occurrenceText, part, level ${level + 1}"
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(rowBackground)
            .onPointerEvent(PointerEventType.Enter) {
                hovered = true
                onHover(true)
            }.onPointerEvent(PointerEventType.Exit) {
                hovered = false
                onHover(false)
            }.onPointerEvent(PointerEventType.Press) { event ->
                if (!event.buttons.isPrimaryPressed) return@onPointerEvent
                val x =
                    event.changes
                        .firstOrNull()
                        ?.position
                        ?.x ?: return@onPointerEvent
                onPress(pressZone(x, eyeStartPx, eyeEndPx, arrowStartPx, arrowEndPx, isGroup), System.nanoTime())
            }.semantics(mergeDescendants = true) {
                contentDescription = "$description, $visibilityName"
                this.selected = selected
                if (isGroup) stateDescription = if (expanded) "expanded" else "collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(ACCENT_BAR).fillMaxHeight().background(if (selected) accent else Color.Transparent))
        Box(
            Modifier
                .size(EYE_ZONE)
                .semantics {
                    role = Role.Button
                    contentDescription = "Visibility"
                    stateDescription = visibilityName
                    onClick(label = "Change visibility") {
                        onEye()
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            EyeGlyph(visibility, textColor)
        }
        Spacer(Modifier.width(ROW_PADDING + indent))
        Box(
            Modifier
                .size(ARROW_ZONE)
                .semantics {
                    if (isGroup) {
                        role = Role.Button
                        contentDescription = if (expanded) "Collapse" else "Expand"
                        onClick(label = if (expanded) "Collapse" else "Expand") {
                            onToggle()
                            true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isGroup) DisclosureTriangle(expanded, textColor.copy(alpha = DIM_ALPHA))
        }
        val label = rowLabel(tree, node, textColor)
        val hiddenRow = visibility == PartMode.HIDDEN
        TooltipArea(
            tooltip = {
                Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                    Text(tree.displayPath(node), color = Color.White, fontSize = 12.sp)
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(
                label,
                color = if (hiddenRow) textColor.copy(alpha = HIDDEN_TEXT_ALPHA) else Color.Unspecified,
                fontSize = 12.sp,
                fontWeight = if (isGroup) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(end = ROW_PADDING),
            )
        }
    }
}

private fun rowLabel(
    tree: AssemblyTree,
    node: Int,
    textColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        append(tree.name(node))
        val dim = SpanStyle(color = textColor.copy(alpha = DIM_ALPHA), fontWeight = FontWeight.Normal)
        if (tree.isGroup(node)) withStyle(dim) { append(" (${tree.leafCount(node)})") }
        val suffix = tree.duplicateSuffix(node)
        if (suffix.isNotEmpty()) withStyle(dim) { append(suffix) }
    }

/** An 8 dp triangle: pointing right when closed, down when open. Drawn as a path, no icon font. */
@Composable
private fun DisclosureTriangle(
    expanded: Boolean,
    color: Color,
) {
    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
        val w = size.width
        val path =
            Path().apply {
                if (expanded) {
                    moveTo(0f, w * 0.25f)
                    lineTo(w, w * 0.25f)
                    lineTo(w / 2f, w * 0.85f)
                } else {
                    moveTo(w * 0.2f, 0f)
                    lineTo(w * 0.2f, w)
                    lineTo(w * 0.85f, w / 2f)
                }
                close()
            }
        drawPath(path, color)
    }
}

private fun visibilityName(state: Byte): String =
    when (state) {
        PartMode.TRANSPARENT -> "transparent"
        PartMode.HIDDEN -> "hidden"
        VisibilityArrays.MIXED -> "mixed visibility"
        else -> "visible"
    }
