package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.okf.BlockHeader

/** The line over the canvas of a knowledge workspace: which block is shown (ADR-0058, D5). */
internal fun blockHeaderText(header: BlockHeader?): String =
    blockHeaderSegments(header).joinToString(" · ") { it.first }

/** The parts of the header line; the second value is true for the name warning, which is drawn in the warning colour. */
internal fun blockHeaderSegments(header: BlockHeader?): List<Pair<String, Boolean>> {
    if (header == null) return listOf("Place the cursor in a ```kstep block" to false)
    if (header.count == 0) return listOf(header.hint?.text().orEmpty() to false)
    val named = header.name?.let { " · $it" }.orEmpty()
    val base = "Block ${header.index + 1} of ${header.count}$named · lines ${header.firstLine}–${header.lastLine}"
    return listOfNotNull(
        base to false,
        if (header.cursorOutside) "cursor outside block" to false else null,
        header.hint?.text()?.let { it to false },
        header.nameHint?.text()?.let { it to true },
    )
}

@Composable
internal fun BlockPreviewHeader(
    header: BlockHeader?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val text =
            buildAnnotatedString {
                blockHeaderSegments(header).forEachIndexed { i, (part, warn) ->
                    if (i > 0) append(" · ")
                    if (warn) withStyle(SpanStyle(color = WARN)) { append(part) } else append(part)
                }
            }
        Text(text, fontSize = 12.sp, color = DIM, fontFamily = FontFamily.Monospace, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WithTip("Previous block (${shortcutLabel()}↑)") {
                TextButton(onClick = onPrevious, enabled = (header?.count ?: 0) > 1) { Text("‹", fontSize = 14.sp) }
            }
            WithTip("Next block (${shortcutLabel()}↓)") {
                TextButton(onClick = onNext, enabled = (header?.count ?: 0) > 1) { Text("›", fontSize = 14.sp) }
            }
        }
    }
}

private fun shortcutLabel(): String =
    if (System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
    ) {
        "⌥⌘"
    } else {
        "Ctrl+Alt+"
    }
