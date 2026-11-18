package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.ai.diff.DiffLine
import dev.kstep.desktop.ai.diff.DiffResult
import dev.kstep.desktop.ai.diff.LineDiff
import dev.kstep.desktop.ai.extract.DisplayText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private val ADDED = Color(0xFF2E9E4F)
private val REMOVED = Color(0xFFD64545)
private const val DIFF_DEBOUNCE_MS = 200L
private const val BG_ALPHA = 0.12f

/**
 * Unified diff of the editor text against the proposal (ADR-0056). Computed on a background dispatcher and debounced,
 * never on the UI thread. Model text is shown as plain text only: control and bidi characters are removed first.
 */
@Composable
internal fun ProposalDiffView(
    current: String,
    proposal: String,
    modifier: Modifier = Modifier,
) {
    val result by produceState<DiffResult?>(null, current, proposal) {
        delay(DIFF_DEBOUNCE_MS)
        value =
            withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                LineDiff.compute(current, proposal, cancelled = { !job.isActive })
            }
    }
    when (val r = result) {
        null -> Text("Computing diff…", fontSize = 12.sp, color = DIM, modifier = modifier)
        is DiffResult.TooLarge ->
            Text(
                "Too many changes for a line diff (${r.oldLines} → ${r.newLines} lines). Apply is still possible.",
                fontSize = 12.sp,
                color = DIM,
                modifier = modifier,
            )
        is DiffResult.Hunks -> {
            if (r.hunks.isEmpty()) {
                Text(
                    "The proposal is identical to the editor text.",
                    fontSize = 12.sp,
                    color = DIM,
                    modifier = modifier,
                )
            } else {
                val lines = r.hunks.flatMap { it.lines }
                LazyColumn(modifier) {
                    items(lines) { DiffRow(it) }
                }
            }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val (bg, mark, number, text) =
        when (line) {
            is DiffLine.Added -> DiffCells(ADDED.copy(alpha = BG_ALPHA), "+", line.newNo.toString(), line.text)
            is DiffLine.Removed -> DiffCells(REMOVED.copy(alpha = BG_ALPHA), "−", line.oldNo.toString(), line.text)
            is DiffLine.Context -> DiffCells(Color.Transparent, " ", line.newNo.toString(), line.text)
            is DiffLine.Collapsed -> DiffCells(Color.Transparent, " ", "", "… ${line.count} unchanged lines")
        }
    Row(Modifier.fillMaxWidth().background(bg)) {
        Text(
            number,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = DIM,
            modifier = Modifier.width(36.dp).padding(end = 4.dp),
        )
        Text(mark, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(14.dp))
        Text(DisplayText.sanitize(text), fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
    }
}

private data class DiffCells(
    val bg: Color,
    val mark: String,
    val number: String,
    val text: String,
)
