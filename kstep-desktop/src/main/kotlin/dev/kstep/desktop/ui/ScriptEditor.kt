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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalClipboard
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
import dev.kstep.desktop.edit.BracketMatch
import dev.kstep.desktop.edit.BracketMatcher
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.ErrorAnchorTracker
import dev.kstep.desktop.edit.FixedLineGeometry
import dev.kstep.desktop.edit.Indent
import dev.kstep.desktop.edit.LineGeometry
import dev.kstep.desktop.edit.LineIndex
import dev.kstep.desktop.edit.LineJump
import dev.kstep.desktop.edit.SafeRegex
import dev.kstep.desktop.edit.ScrollTarget
import dev.kstep.desktop.edit.SearchBarText
import dev.kstep.desktop.edit.SearchPattern
import dev.kstep.desktop.edit.TableLineGeometry
import dev.kstep.desktop.edit.TextReplace
import dev.kstep.desktop.edit.TextSearch
import dev.kstep.desktop.menu.ClipboardKeys
import dev.kstep.desktop.menu.EditAction
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.menu.MenuReasons
import dev.kstep.desktop.menu.PasteText
import dev.kstep.desktop.menu.ShortcutKey
import dev.kstep.desktop.okf.NoteAnalyzer
import dev.kstep.desktop.run.DesktopError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FONT_SIZE_SP = 13f
private const val LINE_HEIGHT_FACTOR = 1.45f
private const val SEARCH_DEBOUNCE_MS = 150L
private const val NOTICE_MS = 3000L
private const val PATTERN_ERROR_DELAY_MS = 300L
private const val MAX_DRAWN_MATCHES = 500

/** A request to bring a line into view (an error-bar click or a block jump); [nonce] makes equal lines distinct. */
internal data class EditorJump(
    val line: Int,
    val nonce: Int,
)

/** What an Edit-menu command asks of the editor. */
internal enum class EditorCommandKind { FIND, REPLACE, GO_TO, REFOCUS, CUT, COPY, PASTE, SELECT_ALL }

/** Ctrl+H stays the Replace key on macOS, where the menu uses Option+Cmd+F (ADR-0074). */
internal fun isMacReplaceAlias(
    key: Key,
    ctrl: Boolean,
    meta: Boolean,
    alt: Boolean,
    os: MenuOs,
): Boolean = os == MenuOs.MAC && ctrl && !meta && !alt && key == Key.H

/** An Edit-menu command for the editor; the nonce makes two equal commands in a row distinct. */
internal data class EditorCommand(
    val kind: EditorCommandKind,
    val nonce: Int,
)

/** The Redo keys the menu does not own (ADR-0074): Cmd+Y on macOS, Ctrl+Shift+Z elsewhere. */
internal fun isRedoAlias(
    key: Key,
    shift: Boolean,
    os: MenuOs,
): Boolean = if (os == MenuOs.MAC) key == Key.Y && !shift else key == Key.Z && shift

/**
 * A request to open the find bar with [query] filled in (ADR-0068, "Show in Script" on an ambiguous name). The caret
 * does not move; the find bar counts the matches and steps through them. [nonce] makes equal queries distinct.
 */
internal data class FindRequest(
    val query: String,
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
    findRequest: FindRequest? = null,
    /** An Edit-menu command for the editor (ADR-0074): open a bar, or take the focus back. */
    editorCommand: EditorCommand? = null,
    /** The editor selection became empty or not (ADR-0075): Cut and Copy need a selection. */
    onSelectionChanged: (Boolean) -> Unit = {},
    /** The clipboard is known to hold text after a copy or cut. */
    onClipboardChanged: (Boolean) -> Unit = {},
    /** A message for the status line (a failed or refused clipboard command). */
    onNotice: (String) -> Unit = {},
    /** The user chose "Open" on the banner of a draft of a file that is not open (ADR-0084). */
    onOpenDraft: (java.nio.file.Path) -> Unit = {},
    /** Long lines wrap at the editor edge instead of scrolling sideways (ADR-0085). */
    softWrap: Boolean = false,
    /** Counts "Banner Actions" presses (ADR-0086): the banner takes the keyboard focus. */
    bannerFocusSignal: Int = 0,
    /** The text the [errors] were found in when that was an unsaved buffer (ADR-0086); `null`: the saved text. */
    errorBase: String? = null,
) {
    val os = remember { MenuOs.current() }
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

    val layoutHolder = remember { LayoutHolder() }
    val lineIndex = remember(field.text) { LineIndex(field.text) }
    val lineCount = lineIndex.lineCount
    // Error marks move with the text (ADR-0085): the tracker replays every change of the buffer onto the anchors.
    val tracker = remember(editing?.path) { ErrorAnchorTracker() }
    val shownErrors = if (editing == null) emptyList() else errors
    val anchors = tracker.update(shownErrors, errorBase ?: editing?.base ?: "", field.text)
    val lineMarks = remember(anchors, field.text) { anchors.lineMarks(field.text) }
    val marks = lineMarks.severities
    val tooltips = lineMarks.tooltips

    // One position table for gutter, tooltips, error tint and scrolling (ADR-0085). It is rebuilt when the text layout
    // changes; the version is a state so that gutter and tooltips redraw (the layout itself is a plain field).
    var layoutVersion by remember { mutableStateOf(0) }
    val wrapSwitch = remember { WrapSwitch(softWrap) }
    val geometry: LineGeometry =
        remember(layoutVersion, lineIndex, softWrap, lineHeightPx) {
            buildGeometry(layoutHolder.layout, lineIndex, lineHeightPx, softWrap)
        }
    if (wrapSwitch.wrap != softWrap) {
        // keep the logical line at the top when wrap is switched: remember it now, restore after the new layout
        wrapSwitch.restoreLine =
            Snapshot.withoutReadObservation { wrapSwitch.geometry.lineAt(vScroll.value.toFloat()) }
        wrapSwitch.restoreVersion = layoutVersion
        wrapSwitch.wrap = softWrap
    }
    wrapSwitch.geometry = geometry
    layoutHolder.track = softWrap || wrapSwitch.restoreLine != null
    LaunchedEffect(softWrap) {
        if (softWrap) hScroll.scrollTo(0)
        delay(WRAP_RESTORE_TIMEOUT_MS)
        wrapSwitch.restoreLine = null
    }
    LaunchedEffect(layoutVersion) {
        val line = wrapSwitch.restoreLine
        if (line != null && layoutVersion > wrapSwitch.restoreVersion) {
            wrapSwitch.restoreLine = null
            vScroll.scrollTo(geometry.top(line).toInt())
        }
    }
    val bracketCache = remember { BracketCache() }

    // Find and go to line (ADR-0067). The bar state lives here: it belongs to the editor, not to the window.
    var bar by remember { mutableStateOf<EditorBar>(EditorBar.None) }
    var barNonce by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var goToInput by remember { mutableStateOf("") }
    var search by remember { mutableStateOf<TextSearch.Result?>(null) }
    var current by remember { mutableStateOf(-1) }
    var pendingNavigate by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var barFocus by remember { mutableStateOf(BarFocus()) }
    var scrollRequest by remember { mutableStateOf<ScrollRequest?>(null) }
    var scrollNonce by remember { mutableStateOf(0) }
    val findOpen = bar is EditorBar.Find
    // The compiled pattern is part of every key below: the case folding is compiled into a regex program (ADR-0090).
    val compiled =
        remember(query, regex, matchCase, wholeWord) { SearchPattern.compile(query, regex, matchCase, wholeWord) }
    val pattern = (compiled as? SearchPattern.Compile.Ok)?.pattern
    var patternError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(compiled) {
        patternError = null
        if (compiled is SearchPattern.Compile.Rejected) {
            // Frame time, not wall-clock time: the delay is the same in the app and in a headless scene test.
            val started = withFrameNanos { it }
            while (withFrameNanos { it } - started < PATTERN_ERROR_DELAY_MS * 1_000_000L) Unit
            patternError = SearchBarText.patternError(compiled.reason, compiled.escapeChar)
        }
    }
    // A text taken from the document (selection, "show in script") is a literal: with regex on it is quoted, never
    // interpreted, and the switch is left alone. Too long once quoted: not taken over.
    val asQuery: (String) -> String? = { raw ->
        val clean = TextSearch.sanitizeQuery(raw)
        val q = if (regex) SafeRegex.quote(clean) else clean
        if (q.length <= TextSearch.MAX_QUERY_CHARS) q else null
    }
    LaunchedEffect(findRequest?.nonce) {
        val request = findRequest ?: return@LaunchedEffect
        query = asQuery(request.query) ?: query
        barNonce++
        pendingNavigate = false
        bar = EditorBar.Find(false, barNonce)
    }

    LaunchedEffect(findOpen, pattern, field.text) {
        if (!findOpen || query.isEmpty() || pattern == null) {
            search = null
            current = -1
            return@LaunchedEffect
        }
        if (!pendingNavigate) delay(SEARCH_DEBOUNCE_MS)
        val text = field.text
        val found =
            withContext(Dispatchers.Default) { TextSearch.find(text, pattern, shouldContinue = { isActive }) }
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
            val raw = field.text.substring(selected.min, selected.max)
            val picked = TextSearch.sanitizeQuery(raw)
            if (picked.isNotEmpty() && picked == raw) query = asQuery(raw) ?: query
        }
        bar = EditorBar.Find(withReplace, barNonce)
        pendingNavigate = query.isNotEmpty()
    }
    val openGoTo: () -> Unit = {
        barNonce++
        goToInput = ""
        bar = EditorBar.GoTo(barNonce)
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(field.selection.collapsed) { onSelectionChanged(!field.selection.collapsed) }
    // One path for the menu click and the key (ADR-0075): the semantics cannot drift apart.
    val runClipboard: (EditAction) -> Unit = { kind ->
        if (editing != null) {
            scope.launch {
                val sel = field.selection
                when (kind) {
                    EditAction.COPY ->
                        if (!sel.collapsed) {
                            if (ClipboardText.write(clipboard, field.text.substring(sel.min, sel.max))) {
                                onClipboardChanged(true)
                            } else {
                                onNotice(ClipboardNotices.NOT_COPIED)
                            }
                        }
                    EditAction.CUT ->
                        if (!sel.collapsed) {
                            if (ClipboardText.write(clipboard, field.text.substring(sel.min, sel.max))) {
                                onClipboardChanged(true)
                                session.onTextChanged(field.text, field.selection.end)
                                session.replaceText(field.text.removeRange(sel.min, sel.max), sel.min)
                                onUserEdit()
                            } else {
                                onNotice(ClipboardNotices.NOT_COPIED)
                            }
                        }
                    EditAction.PASTE ->
                        when (val read = ClipboardText.read(clipboard)) {
                            is ClipboardRead.Text -> {
                                val text = PasteText.normalize(read.text)
                                if (text == null) {
                                    onNotice(ClipboardNotices.TOO_LARGE)
                                } else {
                                    session.onTextChanged(field.text, field.selection.end)
                                    val result = field.text.replaceRange(sel.min, sel.max, text)
                                    if (TextReplace.withinLimits(result)) {
                                        session.replaceText(result, sel.min + text.length)
                                        onUserEdit()
                                    } else {
                                        onNotice(
                                            SearchBarText.replaceError(TextReplace.Outcome.TooLarge)
                                                ?: ClipboardNotices.TOO_LARGE,
                                        )
                                    }
                                }
                            }
                            ClipboardRead.Empty -> {
                                onClipboardChanged(false)
                                onNotice(MenuReasons.CLIPBOARD_EMPTY)
                            }
                            ClipboardRead.Failed -> onNotice(ClipboardNotices.UNREADABLE)
                        }
                    EditAction.SELECT_ALL -> field = field.copy(selection = TextRange(0, field.text.length))
                    else -> Unit
                }
                runCatching { focus.requestFocus() }
            }
        }
    }
    LaunchedEffect(editorCommand?.nonce) {
        when (editorCommand?.kind) {
            EditorCommandKind.CUT -> runClipboard(EditAction.CUT)
            EditorCommandKind.COPY -> runClipboard(EditAction.COPY)
            EditorCommandKind.PASTE -> runClipboard(EditAction.PASTE)
            EditorCommandKind.SELECT_ALL -> runClipboard(EditAction.SELECT_ALL)
            EditorCommandKind.FIND -> openFind(false)
            EditorCommandKind.REPLACE -> openFind(true)
            EditorCommandKind.GO_TO -> openGoTo()
            EditorCommandKind.REFOCUS -> runCatching { focus.requestFocus() }
            null -> Unit
        }
    }
    // Replace runs off the UI thread (a regex search can take a while); the result is only applied when the text is
    // still the one it was computed from (ADR-0090).
    var replaceBusy by remember { mutableStateOf(false) }
    val applyReplace: (String, TextReplace.Outcome, Boolean) -> Unit = { snapshot, outcome, all ->
        if (field.text != snapshot) {
            notice = SearchBarText.TEXT_CHANGED
        } else {
            when (outcome) {
                is TextReplace.Outcome.Ok -> {
                    session.replaceText(outcome.text, outcome.caret.coerceAtMost(outcome.text.length))
                    onUserEdit()
                    if (all) notice = SearchBarText.replaced(outcome.count) else pendingNavigate = true
                }
                TextReplace.Outcome.NoMatches -> if (all) notice = SearchBarText.replaceError(outcome)
                else -> notice = SearchBarText.replaceError(outcome)
            }
        }
    }
    val doReplace: () -> Unit = {
        val pat = pattern
        if (editing != null && query.isNotEmpty() && pat != null && !replaceBusy) {
            session.onTextChanged(field.text, field.selection.end)
            val snapshot = field.text
            val from = field.selection.start
            val repl = replacement
            replaceBusy = true
            scope.launch {
                try {
                    val outcome =
                        withContext(Dispatchers.Default) {
                            TextReplace.replaceOne(snapshot, pat, repl, from, shouldContinue = { isActive })
                        }
                    applyReplace(snapshot, outcome, false)
                } finally {
                    replaceBusy = false
                }
            }
        }
    }
    val doReplaceAll: () -> Unit = {
        val pat = pattern
        if (editing != null && query.isNotEmpty() && pat != null && !replaceBusy) {
            session.onTextChanged(field.text, field.selection.end)
            val snapshot = field.text
            val repl = replacement
            replaceBusy = true
            scope.launch {
                try {
                    val outcome =
                        withContext(Dispatchers.Default) {
                            TextReplace.replaceAllChecked(snapshot, pat, repl, shouldContinue = { isActive })
                        }
                    applyReplace(snapshot, outcome, true)
                } finally {
                    replaceBusy = false
                }
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
                // Ctrl+H stays the Replace key on macOS, where the menu uses Option+Cmd+F (ADR-0074).
                isMacReplaceAlias(event.key, event.isCtrlPressed, event.isMetaPressed, event.isAltPressed, os) -> {
                    openFind(true)
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
        EditorBanner(
            view,
            dark,
            onReload = session::reload,
            onKeepMine = session::keepMine,
            onRestore = session::restoreDraft,
            onDiscard = session::discardDraft,
            onOpenDraft = onOpenDraft,
            onDismissNotice = session::dismissNotice,
            focusSignal = bannerFocusSignal,
            onLeave = { runCatching { focus.requestFocus() } },
        )
        if (state is EditorState.NoFile) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No script open", fontSize = 12.sp, color = colors.gutter)
            }
            return@Column
        }
        val templateError =
            if (pattern == null) {
                null
            } else {
                (TextReplace.compileTemplate(replacement, pattern) as? TextReplace.TemplateCompile.MissingGroup)?.group
            }
        when (val open = bar) {
            is EditorBar.Find ->
                FindBar(
                    bar = open,
                    initialQuery = query,
                    initialReplacement = replacement,
                    colors = colors,
                    counter =
                        if (compiled is SearchPattern.Compile.Rejected) {
                            ""
                        } else {
                            SearchBarText.counter(
                                query,
                                search,
                                current,
                            )
                        },
                    matchCase = matchCase,
                    wholeWord = wholeWord,
                    regex = regex,
                    queryInvalid = patternError != null,
                    canReplace = editing != null && query.isNotEmpty() && pattern != null && templateError == null,
                    replaceAllEnabled = search?.truncated != true,
                    replaceAllHint =
                        when {
                            templateError != null ->
                                SearchBarText.replaceError(
                                    TextReplace.Outcome.BadTemplate(templateError),
                                )
                            search?.limit == TextSearch.Limit.STEP_BUDGET ->
                                SearchBarText.replaceError(TextReplace.Outcome.Incomplete)
                            search?.truncated == true -> "Too many matches (over 10,000)"
                            else -> null
                        },
                    notice = notice ?: patternError,
                    onQuery = {
                        query = it
                        pendingNavigate = true
                    },
                    onReplacement = { replacement = it },
                    onMatchCase = {
                        matchCase = it
                        pendingNavigate = true
                    },
                    onWholeWord = {
                        wholeWord = it
                        pendingNavigate = true
                    },
                    onRegex = {
                        regex = it
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
                    marks = marks,
                    hollow = lineMarks.hollow,
                    scroll = vScroll,
                    geometry = geometry,
                    style = style.copy(color = colors.gutter),
                    measurer = measurer,
                    width = gutterWidth,
                    blockBars = blockBars,
                )
                GutterTooltips(tooltips, vScroll, geometry, gutterWidth)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                val viewportWidth = maxWidth
                val viewportHeight = maxHeight
                val viewportHeightPx = with(density) { viewportHeight.toPx() }
                LaunchedEffect(jump) {
                    val request = jump ?: return@LaunchedEffect
                    runCatching { focus.requestFocus() }
                    ScrollTarget
                        .scrollFor(request.line, geometry, vScroll.value, viewportHeightPx.toInt(), lineHeightPx)
                        ?.let { vScroll.scrollTo(it) }
                }
                LaunchedEffect(scrollRequest) {
                    val request = scrollRequest ?: return@LaunchedEffect
                    if (request.focusEditor) runCatching { focus.requestFocus() }
                    ScrollTarget
                        .scrollFor(request.line, geometry, vScroll.value, viewportHeightPx.toInt(), lineHeightPx)
                        ?.let { vScroll.scrollTo(it) }
                }
                val scrollModifier =
                    if (softWrap) {
                        Modifier.fillMaxSize().verticalScroll(vScroll)
                    } else {
                        Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)
                    }
                Box(scrollModifier) {
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
                        onTextLayout = {
                            if (layoutHolder.layout !== it) {
                                layoutHolder.layout = it
                                if (layoutHolder.track) layoutVersion++
                            }
                        },
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
                                            Offset(-pad, geometry.top(line)),
                                            Size(size.width + pad, geometry.height(line)),
                                        )
                                    }
                                    val textLayout = layoutHolder.layout
                                    // the bracket next to the caret and its partner (ADR-0085); scripts only
                                    val selection = field.selection
                                    val scriptTransformation = transformation as? SyntaxTransformation
                                    if (textLayout != null && selection.collapsed && scriptTransformation != null) {
                                        val shown = field.text
                                        val tokens = scriptTransformation.tokensFor(shown)
                                        val bracket =
                                            if (tokens !=
                                                null
                                            ) {
                                                bracketCache.match(shown, tokens, selection.end)
                                            } else {
                                                null
                                            }
                                        val limit = textLayout.layoutInput.text.length
                                        val offsets =
                                            when (bracket) {
                                                is BracketMatch.Pair -> listOf(bracket.open, bracket.close)
                                                is BracketMatch.Unmatched -> listOf(bracket.at)
                                                null -> emptyList()
                                            }
                                        for (o in offsets) {
                                            if (o + 1 > limit) continue
                                            drawPath(
                                                textLayout.getPathForRange(o, o + 1),
                                                if (bracket is BracketMatch.Pair) {
                                                    colors.bracketMatchBackground
                                                } else {
                                                    colors.errorLineBackground
                                                },
                                            )
                                        }
                                    }
                                    val found = search
                                    if (findOpen && found != null && textLayout != null && found.count > 0) {
                                        val firstLine = geometry.lineAt(vScroll.value.toFloat())
                                        val lastLine = geometry.lineAt(vScroll.value + viewportHeightPx)
                                        val from = lineIndex.lineStart(firstLine)
                                        val to = lineIndex.lineEnd(lastLine)
                                        val shown = found.inRange(from, to, MAX_DRAWN_MATCHES)
                                        for (i in shown) {
                                            val start = found.starts[i]
                                            // an empty match is drawn as a one-character marker
                                            val end = if (found.lengthOf(i) == 0) start + 1 else found.ends[i]
                                            if (end > textLayout.layoutInput.text.length) continue
                                            drawPath(
                                                textLayout.getPathForRange(start, end),
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
                                    val primary = if (os == MenuOs.MAC) event.isMetaPressed else event.isCtrlPressed
                                    val clipboardAction =
                                        if (primary && !event.isShiftPressed && !event.isAltPressed) {
                                            clipboardLetter(event.key)?.let(ClipboardKeys::actionOf)
                                        } else {
                                            null
                                        }
                                    when {
                                        // Cut, Copy, Paste, Select All (ADR-0075): the menu let the key through.
                                        clipboardAction != null -> {
                                            if (ClipboardKeys.of(os) == ClipboardKeys.Handling.DISPATCH) {
                                                runClipboard(clipboardAction)
                                            }
                                            true
                                        }
                                        // Aliases the menu does not own (ADR-0074): Redo on the other key of the platform.
                                        command && isRedoAlias(event.key, event.isShiftPressed, os) -> {
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

    /** Count layouts (state) only while someone needs the measured positions: soft wrap or a pending restore. */
    var track: Boolean = false
}

private const val WRAP_RESTORE_TIMEOUT_MS = 1000L

/** What the editor needs to keep the top line where it was when soft wrap is switched (ADR-0085). */
private class WrapSwitch(
    var wrap: Boolean,
) {
    var geometry: LineGeometry = FixedLineGeometry(1, 1f)
    var restoreLine: Int? = null
    var restoreVersion: Int = 0
}

/** The answer of the bracket search for the last asked text and caret: drawing asks on every frame. */
private class BracketCache {
    private var text: String? = null
    private var caret = -1
    private var result: BracketMatch? = null

    fun match(
        text: String,
        tokens: List<dev.kstep.desktop.edit.Token>,
        caret: Int,
    ): BracketMatch? {
        if (text !== this.text || caret != this.caret) {
            result = BracketMatcher.match(text, tokens, caret)
            this.text = text
            this.caret = caret
        }
        return result
    }
}

/**
 * The positions of the logical lines. Without soft wrap no line wraps, so the fixed line height is exact (and cheaper);
 * with soft wrap they come from the measured text layout, as long as that layout belongs to the current text.
 */
private fun buildGeometry(
    layout: TextLayoutResult?,
    index: LineIndex,
    lineHeightPx: Float,
    softWrap: Boolean,
): LineGeometry {
    if (!softWrap || layout == null || layout.layoutInput.text.length != index.textLength) {
        return FixedLineGeometry(index.lineCount, lineHeightPx)
    }
    val count = index.lineCount
    val tops = FloatArray(count)
    for (line in 1..count) tops[line - 1] = layout.getLineTop(layout.getLineForOffset(index.lineStart(line)))
    return TableLineGeometry.of(count, tops, layout.size.height.toFloat())
}

private data class ScrollRequest(
    val line: Int,
    val nonce: Int,
    val focusEditor: Boolean,
)

private fun clipboardLetter(key: Key): ShortcutKey? =
    when (key) {
        Key.X -> ShortcutKey.Letter('X')
        Key.C -> ShortcutKey.Letter('C')
        Key.V -> ShortcutKey.Letter('V')
        Key.A -> ShortcutKey.Letter('A')
        else -> null
    }
