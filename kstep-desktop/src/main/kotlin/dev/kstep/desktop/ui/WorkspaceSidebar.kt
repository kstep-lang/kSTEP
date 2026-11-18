package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.okf.DisplayName
import dev.kstep.desktop.okf.OkfLimits
import dev.kstep.desktop.okf.PartialReason
import dev.kstep.desktop.okf.ScanResult
import dev.kstep.desktop.okf.WorkspaceEntry
import dev.kstep.desktop.okf.WorkspaceState
import java.nio.file.Path
import java.util.Locale

internal val SIDEBAR_WIDTH = 240.dp
private const val BYTES_PER_MB = 1_048_576.0

/** Untrusted text with its suspicious characters shown as dimmed `‹U+202E›` markers. */
internal fun escapedText(
    raw: String,
    dim: Color,
    maxChars: Int = 256,
): AnnotatedString =
    buildAnnotatedString {
        for (span in DisplayName.spans(raw, maxChars)) {
            if (span.escaped) {
                pushStyle(SpanStyle(color = dim))
                append(span.text)
                pop()
            } else {
                append(span.text)
            }
        }
    }

internal fun entryTitle(entry: WorkspaceEntry): String =
    entry.title ?: entry.relative
        .substringAfterLast('/')
        .removeSuffix(".md")
        .removeSuffix(".MD")

internal fun entryFolder(entry: WorkspaceEntry): String = entry.relative.substringBeforeLast('/', "")

/** The list of notes of a knowledge workspace (ADR-0058, D2): title, folder, number of blocks; no icons. */
@Composable
internal fun WorkspaceSidebar(
    state: WorkspaceState,
    openFile: Path?,
    onOpen: (WorkspaceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
        when (state) {
            WorkspaceState.None -> {}
            is WorkspaceState.Scanning ->
                Text(
                    "Scanning…",
                    fontSize = 12.sp,
                    color = DIM,
                    modifier = Modifier.padding(12.dp),
                )
            is WorkspaceState.Open -> NoteList(state.scan, openFile, onOpen)
        }
    }
}

@Composable
private fun ColumnScope.NoteList(
    scan: ScanResult.Ok,
    openFile: Path?,
    onOpen: (WorkspaceEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    var selected by remember(
        scan,
    ) { mutableStateOf(scan.entries.indexOfFirst { it.path == openFile }.coerceAtLeast(0)) }
    LaunchedEffect(selected) {
        if (scan.entries.isNotEmpty()) listState.animateScrollToItem(selected.coerceIn(0, scan.entries.lastIndex))
    }
    if (scan.partial.isNotEmpty()) {
        Text(
            partialText(scan.partial),
            fontSize = 11.sp,
            color = WARN,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    LazyColumn(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        selected = (selected + 1).coerceAtMost(scan.entries.lastIndex)
                        true
                    }
                    Key.DirectionUp -> {
                        selected = (selected - 1).coerceAtLeast(0)
                        true
                    }
                    Key.Enter -> {
                        scan.entries
                            .getOrNull(selected)
                            ?.takeIf { !it.tooLarge }
                            ?.let(onOpen)
                        true
                    }
                    else -> false
                }
            },
        state = listState,
    ) {
        itemsIndexed(scan.entries, key = { _, e -> e.relative }) { index, entry ->
            NoteRow(entry, active = entry.path == openFile, focused = index == selected, onClick = {
                selected = index
                if (!entry.tooLarge) onOpen(entry)
            })
        }
    }
    val footer =
        listOfNotNull(
            if (scan.linksSkipped > 0) "${scan.linksSkipped} links skipped" else null,
        ).joinToString("  ·  ")
    if (footer.isNotEmpty()) Text(footer, fontSize = 11.sp, color = DIM, modifier = Modifier.padding(12.dp))
}

private fun partialText(reasons: Set<PartialReason>): String =
    when {
        PartialReason.FILES in reasons ->
            "Showing the first ${String.format(
                Locale.ROOT,
                "%,d",
                OkfLimits.MAX_MD_FILES,
            )} Markdown files (limit reached)."
        else -> "Showing a partial list (scan limit reached: ${reasons.joinToString { it.name.lowercase() }})."
    }

@Composable
private fun NoteRow(
    entry: WorkspaceEntry,
    active: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val dim = DIM
    val muted = entry.tooLarge || entry.blockCount == 0
    val primary = if (muted) dim else Color.Unspecified
    val background =
        when {
            active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            focused -> dim.copy(alpha = 0.12f)
            else -> Color.Transparent
        }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                background,
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                escapedText(entryTitle(entry), dim),
                fontSize = 13.sp,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary =
                when {
                    entry.tooLarge ->
                        "Too large (${mb(entry.size)} MB, max. ${mb(OkfLimits.MAX_FILE_BYTES)} MB)"
                    else ->
                        listOf(entryFolder(entry), if (entry.frontmatterIgnored) "Frontmatter ignored" else "")
                            .filter { it.isNotEmpty() }
                            .joinToString("  ·  ")
                }
            if (secondary.isNotEmpty()) {
                Text(
                    escapedText(secondary, dim),
                    fontSize = 11.sp,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.padding(start = 8.dp)) {
            Text(
                if (entry.tooLarge || entry.blockCount == 0) "–" else entry.blockCount.toString(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = dim,
            )
        }
    }
}

private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MB)
