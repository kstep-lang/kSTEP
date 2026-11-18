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
import androidx.compose.ui.graphics.Color
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
import dev.kstep.desktop.okf.NoteAnalyzer
import dev.kstep.desktop.run.DesktopError
import kotlinx.coroutines.delay

private const val FONT_SIZE_SP = 13f
private const val LINE_HEIGHT_FACTOR = 1.45f
private const val FLASH_MS = 600L
private const val FLASH_ALPHA = 0.15f

/** A request to bring a line into view and flash it (an error-bar click); [nonce] makes equal lines distinct. */
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

    val lineCount = remember(field.text) { LineIndex(field.text).lineCount }
    val marks = remember(errors, lineCount) { CaretMapper.markedLines(errors, lineCount) }
    var flashLine by remember { mutableStateOf<Int?>(null) }
    val blockBars =
        if (knowledge) {
            NoteAnalyzer.analyze(field.text).extraction.blocks.map { b ->
                (b.openLine..(b.closeLine ?: maxOf(b.contentEndLine, b.openLine))) to (b.index == activeBlock)
            }
        } else {
            emptyList()
        }

    Column(modifier) {
        EditorBanner(view, dark, onReload = session::reload, onKeepMine = session::keepMine)
        if (state is EditorState.NoFile) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No script open", fontSize = 12.sp, color = colors.gutter)
            }
            return@Column
        }
        val digits = maxOf(3, lineCount.toString().length)
        val digitWidth = remember(measurer, style) { measurer.measure("0", style).size.width }
        val gutterWidth = with(density) { (digitWidth * digits).toDp() } + 14.dp
        Row(Modifier.weight(1f).fillMaxSize()) {
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
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                val viewportWidth = maxWidth
                val viewportHeight = maxHeight
                val viewportHeightPx = with(density) { viewportHeight.toPx() }
                LaunchedEffect(jump) {
                    val request = jump ?: return@LaunchedEffect
                    runCatching { focus.requestFocus() }
                    val top = ((request.line - 1) * lineHeightPx).toInt()
                    val bottom = top + lineHeightPx.toInt()
                    if (top < vScroll.value || bottom > vScroll.value + viewportHeightPx) {
                        vScroll.scrollTo((top - viewportHeightPx / 2).toInt().coerceAtLeast(0))
                    }
                    flashLine = request.line
                    delay(FLASH_MS)
                    flashLine = null
                }
                Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
                    BasicTextField(
                        value = field,
                        onValueChange = { new ->
                            val changed = new.text != field.text
                            field = new
                            onCaret?.invoke(new.text, new.selection.end)
                            if (changed) {
                                session.onTextChanged(
                                    new.text,
                                    new.selection.end,
                                )
                            } else {
                                session.onCaretMoved(new.selection.end)
                            }
                        },
                        readOnly = editing == null,
                        textStyle = style,
                        cursorBrush = SolidColor(colors.text),
                        visualTransformation = transformation,
                        modifier =
                            Modifier
                                .widthIn(min = viewportWidth)
                                .heightIn(min = viewportHeight)
                                .padding(start = 8.dp)
                                .drawBehind {
                                    flashLine?.let { line ->
                                        drawRect(
                                            Color(0xFFFFC107).copy(alpha = FLASH_ALPHA),
                                            Offset(0f, (line - 1) * lineHeightPx),
                                            Size(size.width, lineHeightPx),
                                        )
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
