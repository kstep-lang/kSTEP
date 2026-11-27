package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.edit.LineJump
import dev.kstep.desktop.edit.SearchBarText
import dev.kstep.desktop.edit.TextReplace
import dev.kstep.desktop.edit.TextSearch

/** Which bar the editor shows under its banner (ADR-0067): at most one. */
internal sealed interface EditorBar {
    data object None : EditorBar

    data class Find(
        val withReplace: Boolean,
        val nonce: Int,
    ) : EditorBar

    data class GoTo(
        val nonce: Int,
    ) : EditorBar
}

internal val BAR_HEIGHT = 32.dp
private val ERROR_RED = Color(0xFFC62828)

/** A small text button (the bar is 32 dp high, a material button would be taller). */
@Composable
private fun BarButton(
    label: String,
    colors: EditorColors,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .height(24.dp)
            .background(if (active) colors.currentMatchBackground else Color.Transparent, shape)
            .border(1.dp, colors.gutter, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = if (enabled) colors.text else colors.gutter)
    }
}

/** A one-line input field of a bar; [onValue] receives the (sanitised) text. */
@Composable
private fun BarField(
    initial: String,
    hint: String,
    colors: EditorColors,
    sanitize: (String) -> String,
    onValue: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    invalid: Boolean = false,
    selectAllOnOpen: Boolean = false,
    openKey: Int = 0,
) {
    var value by remember(openKey) {
        mutableStateOf(
            TextFieldValue(initial, if (selectAllOnOpen) TextRange(0, initial.length) else TextRange(initial.length)),
        )
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .height(24.dp)
            .border(1.dp, if (invalid) ERROR_RED else colors.gutter, shape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.text.isEmpty()) Text(hint, fontSize = 12.sp, color = colors.gutter, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = { new ->
                val clean = sanitize(new.text)
                value = if (clean == new.text) new else TextFieldValue(clean, TextRange(clean.length))
                onValue(clean)
            },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.text),
            cursorBrush = SolidColor(colors.text),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFocus(it.isFocused) }
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
        )
    }
}

/**
 * The find bar (and, with [withReplace], the replace row): a 32 dp line that pushes the text down (no overlay).
 * Three labelled toggles follow the field: "Aa" (match case), `\b` (whole word) and `.*` (regular expression, the safe
 * subset of ADR-0090). An invalid pattern turns the field border red and replaces the counter by the message.
 */
@Composable
internal fun FindBar(
    bar: EditorBar.Find,
    initialQuery: String,
    colors: EditorColors,
    counter: String,
    matchCase: Boolean,
    wholeWord: Boolean,
    regex: Boolean,
    queryInvalid: Boolean,
    canReplace: Boolean,
    replaceAllEnabled: Boolean,
    replaceAllHint: String?,
    notice: String?,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onMatchCase: (Boolean) -> Unit,
    onWholeWord: (Boolean) -> Unit,
    onRegex: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    onQueryFocus: (Boolean) -> Unit,
    onReplaceFocus: (Boolean) -> Unit,
    initialReplacement: String,
) {
    val queryFocus = remember(bar.nonce) { FocusRequester() }
    LaunchedEffect(bar.nonce) { runCatching { queryFocus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(colors.bar)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarField(
            initialQuery,
            SearchBarText.findPlaceholder(regex),
            colors,
            TextSearch::sanitizeQuery,
            onQuery,
            onQueryFocus,
            queryFocus,
            Modifier.width(220.dp),
            invalid = queryInvalid,
            selectAllOnOpen = true,
            openKey = bar.nonce,
        )
        BarButton("Aa", colors, active = matchCase, onClick = { onMatchCase(!matchCase) })
        BarButton("\\b", colors, active = wholeWord, onClick = { onWholeWord(!wholeWord) })
        BarButton(".*", colors, active = regex, onClick = { onRegex(!regex) })
        Box(Modifier.width(8.dp))
        BarButton("‹", colors, onClick = onPrevious)
        BarButton("›", colors, onClick = onNext)
        Text(
            notice ?: counter,
            fontSize = 12.sp,
            color = colors.text,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        BarButton("×", colors, onClick = onClose)
    }
    if (bar.withReplace) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .background(colors.bar)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarField(
                initialReplacement,
                SearchBarText.replacePlaceholder(regex),
                colors,
                TextReplace::sanitizeReplacement,
                onReplacement,
                onReplaceFocus,
                null,
                Modifier.width(220.dp),
            )
            BarButton("Replace", colors, enabled = canReplace, onClick = onReplace)
            BarButton("Replace all", colors, enabled = canReplace && replaceAllEnabled, onClick = onReplaceAll)
            if (replaceAllHint != null) {
                Text(replaceAllHint, fontSize = 12.sp, color = colors.gutter, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The go-to-line bar: a field with live validation; the message sits next to the field so nothing jumps. */
@Composable
internal fun GoToBar(
    bar: EditorBar.GoTo,
    lineCount: Int,
    colors: EditorColors,
    onInput: (String) -> Unit,
    input: String,
    onFocus: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember(bar.nonce) { FocusRequester() }
    LaunchedEffect(bar.nonce) { runCatching { focus.requestFocus() } }
    val message = SearchBarText.goToMessage(LineJump.parse(input, lineCount))
    Row(
        Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(colors.bar)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarField(
            "",
            SearchBarText.goToHint(lineCount),
            colors,
            { it.filter { c -> c != '\n' && c != '\r' }.take(32) },
            onInput,
            onFocus,
            focus,
            Modifier.width(160.dp),
            invalid = message != null,
            openKey = bar.nonce,
        )
        if (message != null) {
            Text(
                message,
                fontSize = 12.sp,
                color =
                    if (colors.bar.red >
                        0.5f
                    ) {
                        ERROR_RED
                    } else {
                        Color(0xFFFF8A80)
                    },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(Modifier.weight(1f))
        BarButton("×", colors, onClick = onClose)
    }
}
