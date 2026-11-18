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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.okf.BlockHeader

/** The line over the canvas of a knowledge workspace: which block is shown (ADR-0058, D5). */
internal fun blockHeaderText(header: BlockHeader?): String {
    if (header == null) return "Place the cursor in a ```kstep block"
    if (header.count == 0) return header.hint?.text().orEmpty()
    val base = "Block ${header.index + 1} of ${header.count} · lines ${header.firstLine}–${header.lastLine}"
    val parts = listOfNotNull(base, if (header.cursorOutside) "cursor outside block" else null, header.hint?.text())
    return parts.joinToString(" · ")
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
        Text(blockHeaderText(header), fontSize = 12.sp, color = DIM, fontFamily = FontFamily.Monospace, maxLines = 1)
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
