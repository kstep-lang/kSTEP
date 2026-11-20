package dev.kstep.desktop.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.edit.CaretMapper
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.Indent
import dev.kstep.desktop.edit.LineIndex
import dev.kstep.desktop.edit.LineJump
import dev.kstep.desktop.edit.ScrollTarget
import dev.kstep.desktop.edit.SearchBarText
import dev.kstep.desktop.edit.TextReplace
import dev.kstep.desktop.edit.TextSearch
import dev.kstep.desktop.okf.NoteAnalyzer
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.ErrorBarText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val FONT_SIZE_SP = 13f
private const val LINE_HEIGHT_FACTOR = 1.45f
private const val SEARCH_DEBOUNCE_MS = 150L
private const val NOTICE_MS = 3000L
private const val MAX_DRAWN_MATCHES = 500

/** A request to bring a line into view (an error-bar click or a block jump); [nonce] makes equal lines distinct. */
internal data class EditorJump(
    val line: Int,
    val nonce: Int,
)

/**
 * The script editor (ADR-0052): a plain `BasicTextField` with syntax colouring as a visual transformation, a gutter
 * and one shared vertical scroll. Edits go through the [EditorSession] (its own undo history), never Compose's undo.
 * The line height is fixed, so the gutter lines up with the text without measuring the field.
 */
@Composable
internal fun ScriptEditor(
    session: EditorSession,
    view: EditorView,
    errors: List<DesktopError>,
    dark: Boolean,
    jump: EditorJump?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Knowledge workspace (ADR-0058): colour a Markdown note and bar its `kstep` blocks. */
    knowledge: Boolean = false,
    activeBlock: Int? = null,
    /** Called with the text and caret after every change of either (knowledge mode only). */
    onCaret: ((String, Int) -> Unit)? = null,
    /** The line to tint after an error-bar click (ADR-0067); it stays until the text changes or Esc is pressed. */
    errorLine: Int? = null,
    /** The user typed, pasted or replaced text: marks that refer to the saved file are stale. */
    onUserEdit: () -> Unit = {},
    onEscape: () -> Unit = {},
) {
    val colors = if (dark) EditorColors.DARK else EditorColors.LIGHT
    val state = view.state
    val editing = state as? EditorState.Editing
    val density = LocalDensity.current
    val style =
        remember(colors) {
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = FONT_SIZE_SP.sp,
                lineHeight = (FONT_SIZE_SP * LINE_HEIGHT_FACTOR).sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                color = colors.text,
            )
        }
    val lineHeightPx = with(density) { (FONT_SIZE_SP * LINE_HEIGHT_FACTOR).sp.toPx() }
    val measurer = rememberTextMeasurer()
    val transformation =
        remember(colors, knowledge) {
            if (knowledge) KnowledgeSyntaxTransformation(colors) else SyntaxTransformation(colors)
        }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val focus = remember { FocusRequester() }

    var field by remember {
        mutableStateOf(
            TextFieldValue(view.text, TextRange(view.caret.coerceIn(0, view.text.length))),
        )
    }
    LaunchedEffect(view.revision) {
        val text = view.text
        field = TextFieldValue(text, TextRange(view.caret.coerceIn(0, text.length)))
        onCaret?.invoke(text, field.selection.end)
    }

    val lineIndex = remember(field.text) { LineIndex(field.text) }
    val lineCount = lineIndex.lineCount
    val marks = remember(errors, lineCount) { CaretMapper.markedLines(errors, lineCount) }
    val tooltips = remember(errors, lineCount) { ErrorBarText.tooltipsByLine(errors, lineCount) }

    // Find and go to line (ADR-0067). The bar state lives here: it belongs to the editor, not to the window.
    var bar by remember { mutableStateOf<EditorBar>(EditorBar.None) }
    var barNonce by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var goToInput by remember { mutableStateOf("") }
    var search by remember { mutableStateOf<TextSearch.Result?>(null) }
    var current by remember { mutableStateOf(-1) }
    var pendingNavigate by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var barFocus by remember { mutableStateOf(BarFocus()) }
    var scrollRequest by remember { mutableStateOf<ScrollRequest?>(null) }
    var scrollNonce by remember { mutableStateOf(0) }
    val layoutHolder = remember { LayoutHolder() }
    val findOpen = bar is EditorBar.Find

    LaunchedEffect(findOpen, query, matchCase, field.text) {
        if (!findOpen || query.isEmpty()) {
            search = null
            current = -1
            return@LaunchedEffect
        }
        if (!pendingNavigate) delay(SEARCH_DEBOUNCE_MS)
        val text = field.text
        val found =
            withContext(Dispatchers.Default) { TextSearch.find(text, query, matchCase, shouldContinue = { isActive }) }
        search = found
        current = found.firstAtOrAfter(field.selection.start)
        if (pendingNavigate) {
            pendingNavigate = false
            if (current >= 0) {
                session.requestCaret(found.starts[current])
                scrollNonce++
                scrollRequest = ScrollRequest(lineIndex.lineOf(found.starts[current]), scrollNonce, focusEditor = false)
            }
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }
    val navigate: (Int) -> Unit = { index ->
        val found = search
        if (found != null && index in 0 until found.count) {
            current = index
            session.requestCaret(found.starts[index])
            scrollNonce++
            scrollRequest = ScrollRequest(lineIndex.lineOf(found.starts[index]), scrollNonce, focusEditor = false)
        }
    }
    val closeBar: () -> Unit = {
        bar = EditorBar.None
        barFocus = BarFocus()
        runCatching { focus.requestFocus() }
    }
    val openFind: (Boolean) -> Unit = { withReplace ->
        barNonce++
        val selected = field.selection
        if (!selected.collapsed && selected.length <= TextSearch.MAX_QUERY_CHARS) {
            val picked = TextSearch.sanitizeQuery(field.text.substring(selected.min, selected.max))
            if (picked.isNotEmpty() && picked == field.text.substring(selected.min, selected.max)) query = picked
        }
        bar = EditorBar.Find(withReplace, barNonce)
        pendingNavigate = query.isNotEmpty()
    }
    val doReplace: () -> Unit = {
        if (editing != null && query.isNotEmpty()) {
            session.onTextChanged(field.text, field.selection.end)
            val fresh = TextSearch.find(field.text, query, matchCase)
            val idx = fresh.firstAtOrAfter(field.selection.start)
            if (idx >= 0 && !fresh.truncated) {
                val (text, caret) = TextReplace.replaceAt(field.text, fresh.starts[idx], fresh.length, replacement)
                if (TextReplace.withinLimits(text)) {
                    session.replaceText(text, caret)
                    onUserEdit()
                    pendingNavigate = true
                } else {
                    notice = SearchBarText.replaceError(TextReplace.Outcome.TooLarge)
                }
            }
        }
    }
    val doReplaceAll: () -> Unit = {
        if (editing != null && query.isNotEmpty()) {
            session.onTextChanged(field.text, field.selection.end)
            when (val outcome = TextReplace.replaceAllChecked(field.text, query, matchCase, replacement)) {
                is TextReplace.Outcome.Ok -> {
                    session.replaceText(outcome.text, outcome.caret.coerceAtMost(outcome.text.length))
                    onUserEdit()
                    notice = SearchBarText.replaced(outcome.count)
                }
                else -> notice = SearchBarText.replaceError(outcome)
            }
        }
    }
    val commitGoTo: () -> Unit = {
        when (val parsed = LineJump.parse(goToInput, lineCount)) {
            is LineJump.Result.Valid -> {
                session.requestCaret(lineIndex.lineStart(parsed.line))
                scrollNonce++
                scrollRequest = ScrollRequest(parsed.line, scrollNonce, focusEditor = true)
                bar = EditorBar.None
                barFocus = BarFocus()
                goToInput = ""
            }
            else -> Unit
        }
    }
    val blockBars =
        if (knowledge) {
            NoteAnalyzer.analyze(field.text).extraction.blocks.map { b ->
                (b.openLine..(b.closeLine ?: maxOf(b.contentEndLine, b.openLine))) to (b.index == activeBlock)
            }
        } else {
            emptyList()
        }

    val enterAction: (Boolean) -> BarEnterAction? = { shift ->
        barEnterAction(bar is EditorBar.Find, bar is EditorBar.GoTo, barFocus, shift)
    }

    Column(
        modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || state is EditorState.NoFile) return@onPreviewKeyEvent false
            val command = event.isMetaPressed || event.isCtrlPressed
            val open = bar
            when {
                command && event.key == Key.F && event.isAltPressed && event.isMetaPressed -> {
                    openFind(true)
                    true
                }
                command && !event.isAltPressed && event.key == Key.F -> {
                    openFind(false)
                    true
                }
                event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && event.key == Key.H -> {
                    openFind(true)
                    true
                }
                command && !event.isAltPressed && event.key == Key.L -> {
                    barNonce++
                    goToInput = ""
                    bar = EditorBar.GoTo(barNonce)
                    true
                }
                open is EditorBar.Find && event.key == Key.F3 -> {
                    navigate(if (event.isShiftPressed) search?.previous(current) ?: -1 else search?.next(current) ?: -1)
                    true
                }
                open is EditorBar.Find && command && event.key == Key.G -> {
                    navigate(if (event.isShiftPressed) search?.previous(current) ?: -1 else search?.next(current) ?: -1)
                    true
                }
                event.key == Key.Enter && enterAction(event.isShiftPressed) != null -> {
                    when (enterAction(event.isShiftPressed)) {
                        BarEnterAction.Replace -> doReplace()
                        BarEnterAction.NextMatch -> navigate(search?.next(current) ?: -1)
                        BarEnterAction.PreviousMatch -> navigate(search?.previous(current) ?: -1)
                        BarEnterAction.CommitGoTo -> commitGoTo()
                        null -> Unit
                    }
                    true
                }
                open !is EditorBar.None && event.key == Key.Escape -> {
                    closeBar()
                    onEscape()
                    true
                }
                open is EditorBar.None && event.key == Key.Escape -> {
                    onEscape()
                    false
                }
                else -> false
            }
        },
    ) {
        EditorBanner(view, dark, onReload = session::reload, onKeepMine = session::keepMine)
        if (state is EditorState.NoFile) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No script open", fontSize = 12.sp, color = colors.gutter)
            }
            return@Column
        }
        when (val open = bar) {
            is EditorBar.Find ->
                FindBar(
                    bar = open,
                    initialQuery = query,
                    initialReplacement = replacement,
                    colors = colors,
                    counter = SearchBarText.counter(query, search, current),
                    matchCase = matchCase,
                    canReplace = editing != null && query.isNotEmpty(),
                    replaceAllEnabled = search?.truncated != true,
                    replaceAllHint = if (search?.truncated == true) "Too many matches (over 10,000)" else null,
                    notice = notice,
                    onQuery = {
                        query = it
                        pendingNavigate = true
                    },
                    onReplacement = { replacement = it },
                    onMatchCase = {
                        matchCase = it
                        pendingNavigate = true
                    },
                    onPrevious = { navigate(search?.previous(current) ?: -1) },
                    onNext = { navigate(search?.next(current) ?: -1) },
                    onReplace = doReplace,
                    onReplaceAll = doReplaceAll,
                    onClose = closeBar,
                    onQueryFocus = { focused -> barFocus = barFocus.copy(query = focused) },
                    onReplaceFocus = { focused -> barFocus = barFocus.copy(replace = focused) },
                )
            is EditorBar.GoTo ->
                GoToBar(open, lineCount, colors, { goToInput = it }, goToInput, { focused ->
                    barFocus =
                        barFocus.copy(goTo = focused)
                }, closeBar)
            EditorBar.None -> Unit
        }
        val digits = maxOf(3, lineCount.toString().length)
        val digitWidth = remember(measurer, style) { measurer.measure("0", style).size.width }
        val gutterWidth = with(density) { (digitWidth * digits).toDp() } + 14.dp
        Row(Modifier.weight(1f).fillMaxSize()) {
            Box {
                EditorGutter(
                    lineCount = lineCount,
                    marks = marks,
                    stale = view.dirty,
                    scroll = vScroll,
                    lineHeightPx = lineHeightPx,
                    style = style.copy(color = colors.gutter),
                    measurer = measurer,
                    width = gutterWidth,
                    blockBars = blockBars,
                )
                GutterTooltips(tooltips, vScroll, lineHeightPx, gutterWidth)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                val viewportWidth = maxWidth
                val viewportHeight = maxHeight
                val viewportHeightPx = with(density) { viewportHeight.toPx() }
                LaunchedEffect(jump) {
                    val request = jump ?: return@LaunchedEffect
                    runCatching { focus.requestFocus() }
                    ScrollTarget
                        .scrollFor(request.line, lineCount, vScroll.value, viewportHeightPx.toInt(), lineHeightPx)
                        ?.let { vScroll.scrollTo(it) }
                }
                LaunchedEffect(scrollRequest) {
                    val request = scrollRequest ?: return@LaunchedEffect
                    if (request.focusEditor) runCatching { focus.requestFocus() }
                    ScrollTarget
                        .scrollFor(request.line, lineCount, vScroll.value, viewportHeightPx.toInt(), lineHeightPx)
                        ?.let { vScroll.scrollTo(it) }
                }
                Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
                    BasicTextField(
                        value = field,
                        onValueChange = { new ->
                            val changed = new.text != field.text
                            field = new
                            onCaret?.invoke(new.text, new.selection.end)
                            if (changed) {
                                onUserEdit()
                                session.onTextChanged(
                                    new.text,
                                    new.selection.end,
                                )
                            } else {
                                session.onCaretMoved(new.selection.end)
                            }
                        },
                        readOnly = editing == null,
                        onTextLayout = { layoutHolder.layout = it },
                        textStyle = style,
                        cursorBrush = SolidColor(colors.text),
                        visualTransformation = transformation,
                        modifier =
                            Modifier
                                .widthIn(min = viewportWidth)
                                .heightIn(min = viewportHeight)
                                .padding(start = 8.dp)
                                .drawBehind {
                                    val pad = 8.dp.toPx()
                                    errorLine?.takeIf { it in 1..lineCount }?.let { line ->
                                        drawRect(
                                            colors.errorLineBackground,
                                            Offset(-pad, (line - 1) * lineHeightPx),
                                            Size(size.width + pad, lineHeightPx),
                                        )
                                    }
                                    val found = search
                                    val textLayout = layoutHolder.layout
                                    if (findOpen && found != null && textLayout != null && found.count > 0) {
                                        val firstLine = (vScroll.value / lineHeightPx).toInt() + 1
                                        val lastLine = ((vScroll.value + viewportHeightPx) / lineHeightPx).toInt() + 1
                                        val from = lineIndex.lineStart(firstLine)
                                        val to = lineIndex.lineEnd(lastLine.coerceAtMost(lineCount))
                                        val shown = found.inRange(from, to, MAX_DRAWN_MATCHES)
                                        for (i in shown) {
                                            val start = found.starts[i]
                                            if (start + found.length > textLayout.layoutInput.text.length) continue
                                            drawPath(
                                                textLayout.getPathForRange(start, start + found.length),
                                                if (i ==
                                                    current
                                                ) {
                                                    colors.currentMatchBackground
                                                } else {
                                                    colors.matchBackground
                                                },
                                            )
                                        }
                                    }
                                }.focusRequester(focus)
                                .onFocusChanged { onFocusChanged(it.isFocused) }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown ||
                                        editing == null
                                    ) {
                                        return@onPreviewKeyEvent false
                                    }
                                    val command = event.isMetaPressed || event.isCtrlPressed
                                    when {
                                        command && event.key == Key.Z -> {
                                            if (event.isShiftPressed) session.redo() else session.undo()
                                            true
                                        }
                                        command && event.key == Key.Y -> {
                                            session.redo()
                                            true
                                        }
                                        !command && !event.isAltPressed && event.key == Key.Enter -> {
                                            val (text, caret) =
                                                Indent.onEnter(
                                                    field.text,
                                                    field.selection.start,
                                                    field.selection.end,
                                                )
                                            session.replaceText(text, caret)
                                            true
                                        }
                                        !command && !event.isShiftPressed && event.key == Key.Tab -> {
                                            val (text, caret) =
                                                Indent.onTab(
                                                    field.text,
                                                    field.selection.start,
                                                    field.selection.end,
                                                )
                                            session.replaceText(text, caret)
                                            true
                                        }
                                        else -> false
                                    }
                                },
                    )
                }
            }
        }
    }
}

private class LayoutHolder {
    var layout: TextLayoutResult? = null
}

private data class ScrollRequest(
    val line: Int,
    val nonce: Int,
    val focusEditor: Boolean,
)
