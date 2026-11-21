package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.ai.diff.DiffLine
import dev.kstep.desktop.ai.diff.HunkPlan
import dev.kstep.desktop.ai.diff.LineDiff
import dev.kstep.desktop.ai.diff.PlannedHunk
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
private const val DIM_ALPHA = 0.4f

/** The change groups of editor text against the proposal; computed off the UI thread and debounced (ADR-0069). */
@Composable
internal fun rememberHunkPlan(
    current: String,
    proposal: String,
): HunkPlan? =
    produceState<HunkPlan?>(null, current, proposal) {
        delay(DIFF_DEBOUNCE_MS)
        value =
            withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                LineDiff.plan(current, proposal, cancelled = { !job.isActive })
            }
    }.value

private sealed interface DiffItem {
    data class Header(
        val hunk: PlannedHunk,
    ) : DiffItem

    data class Row(
        val line: DiffLine,
        val dim: Boolean,
    ) : DiffItem

    data class Gap(
        val count: Int,
    ) : DiffItem
}

private fun buildItems(
    plan: HunkPlan.Ready,
    selected: Set<Int>,
): List<DiffItem> {
    val out = ArrayList<DiffItem>()
    for ((idx, h) in plan.hunks.withIndex()) {
        if (idx > 0) {
            val prev = plan.hunks[idx - 1]
            val shown =
                prev.display.lines
                    .takeLastWhile { it is DiffLine.Context }
                    .size +
                    h.display.lines
                        .takeWhile { it is DiffLine.Context }
                        .size
            out.add(DiffItem.Gap((h.oldStart - prev.oldEnd - shown).coerceAtLeast(0)))
        }
        out.add(DiffItem.Header(h))
        val dim = h.id !in selected
        h.display.lines.forEach { out.add(DiffItem.Row(it, dim)) }
    }
    return out
}

/**
 * Unified diff of the editor text against the proposal (ADR-0056), one selectable group per change (ADR-0069). The
 * [plan] comes from [rememberHunkPlan] so that what is shown and what is applied are the same groups. Model text is
 * shown as plain text only: control and bidi characters are removed first.
 */
@Composable
internal fun ProposalDiffView(
    plan: HunkPlan?,
    selected: Set<Int>,
    selectable: Boolean,
    locked: Boolean,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (plan) {
        null -> Text("Computing diff…", fontSize = 12.sp, color = DIM, modifier = modifier)
        is HunkPlan.TooLarge ->
            Text(
                "Too many changes for a line diff (${plan.oldLines} → ${plan.newLines} lines). Apply is still possible.",
                fontSize = 12.sp,
                color = DIM,
                modifier = modifier,
            )
        is HunkPlan.Ready -> {
            if (plan.hunks.isEmpty()) {
                Text(
                    "The proposal is identical to the editor text.",
                    fontSize = 12.sp,
                    color = DIM,
                    modifier = modifier,
                )
            } else {
                val shown = if (selectable) selected else plan.hunks.map { it.id }.toSet()
                val rows = buildItems(plan, shown)
                Column {
                    if (selectable) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${selected.size} of ${plan.hunks.size} changes chosen",
                                fontSize = 12.sp,
                                color = DIM,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onToggleAll, enabled = !locked) {
                                Text(if (selected.size == plan.hunks.size) "None" else "All")
                            }
                        }
                    }
                    LazyColumn(modifier) {
                        items(rows) { item ->
                            when (item) {
                                is DiffItem.Header ->
                                    HunkHeader(item.hunk, selectable, item.hunk.id in selected, locked, onToggle)
                                is DiffItem.Row ->
                                    Box(Modifier.alpha(if (item.dim) DIM_ALPHA else 1f)) {
                                        DiffRow(item.line, plain = item.dim)
                                    }
                                is DiffItem.Gap ->
                                    Text(
                                        "… ${item.count} unchanged lines",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = DIM,
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HunkHeader(
    hunk: PlannedHunk,
    selectable: Boolean,
    checked: Boolean,
    locked: Boolean,
    onToggle: (Int) -> Unit,
) {
    val label = "@ line ${hunk.oldStart + 1}  +${hunk.added} −${hunk.removed}"
    val rowModifier =
        if (selectable && !locked) {
            Modifier.fillMaxWidth().toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle(hunk.id) },
            )
        } else {
            Modifier.fillMaxWidth()
        }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (selectable) Checkbox(checked = checked, onCheckedChange = null, enabled = !locked)
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = DIM)
    }
}

@Composable
private fun DiffRow(
    line: DiffLine,
    plain: Boolean = false,
) {
    val (bg, mark, number, text) =
        when (line) {
            is DiffLine.Added -> DiffCells(ADDED.copy(alpha = BG_ALPHA), "+", line.newNo.toString(), line.text)
            is DiffLine.Removed -> DiffCells(REMOVED.copy(alpha = BG_ALPHA), "−", line.oldNo.toString(), line.text)
            is DiffLine.Context -> DiffCells(Color.Transparent, " ", line.newNo.toString(), line.text)
            is DiffLine.Collapsed -> DiffCells(Color.Transparent, " ", "", "… ${line.count} unchanged lines")
        }
    Row(Modifier.fillMaxWidth().background(if (plain) Color.Transparent else bg)) {
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
