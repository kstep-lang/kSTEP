package dev.kstep.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.run.SandboxNotice
import dev.kstep.desktop.status.PreviewOriginLabel
import dev.kstep.desktop.status.StatusLineText

internal const val TAG_STATUS_FILE = "status.file"
internal const val TAG_STATUS_EXPORT = "status.export"
internal const val TAG_STATUS_SANDBOX = "status.sandbox"
internal const val TAG_STATUS_RUN = "status.run"
internal const val TAG_STATUS_ORIGIN = "status.origin"
private const val FILE_MIN_DP = 64
private const val FILE_MAX_DP = 220
private const val GROUP_WEIGHT = 2f

private fun Modifier.tagged(
    tag: String,
    probe: ((String, Rect) -> Unit)?,
): Modifier =
    testTag(tag).let { tagged ->
        if (probe == null) tagged else tagged.onGloballyPositioned { probe(tag, it.boundsInRoot()) }
    }

/**
 * The right part of the toolbar (ADR-0064): file name, then the export line, the sandbox notice and the run status.
 * Separators only stand between things that are shown. When the room is short the file name gives way first (never
 * below 64 dp), then the run status, then the export line; "Show in Folder" and the sandbox notice are never cut.
 * [probe] reports the bounds of the tagged parts (tests only).
 */
@Composable
internal fun RowScope.StatusLine(
    fileName: String?,
    exportText: String?,
    onShowInFolder: () -> Unit,
    sandboxNotice: SandboxNotice?,
    statusText: String,
    probe: ((String, Rect) -> Unit)? = null,
    /** What the picture came from (ADR-0086); drawn in the accent colour when it is an unsaved buffer. */
    previewLabel: PreviewOriginLabel? = null,
    accent: Color = ACCENT_LIGHT,
) {
    val hasRight = exportText != null || sandboxNotice != null || statusText.isNotEmpty() || previewLabel != null
    Text(
        StatusLineText.fileLabel(fileName),
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .padding(start = 8.dp)
                .widthIn(min = FILE_MIN_DP.dp, max = FILE_MAX_DP.dp)
                .weight(1f, fill = false)
                .tagged(TAG_STATUS_FILE, probe),
    )
    if (!hasRight) return
    Text(StatusLineText.SEPARATOR, fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false)
    Row(
        Modifier.weight(GROUP_WEIGHT),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (exportText != null) {
            Text(
                exportText,
                fontSize = 12.sp,
                color = DIM,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).tagged(TAG_STATUS_EXPORT, probe),
            )
            TextButton(onClick = onShowInFolder) { Text("Show in Folder", fontSize = 12.sp, softWrap = false) }
            if (sandboxNotice != null || statusText.isNotEmpty()) {
                Text(StatusLineText.SEPARATOR, fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false)
            }
        }
        if (sandboxNotice != null) {
            SandboxMark(
                sandboxNotice,
                warningTextColor(isSystemInDarkTheme()),
                Modifier.tagged(TAG_STATUS_SANDBOX, probe),
            )
            if (statusText.isNotEmpty() || previewLabel != null) {
                Text(StatusLineText.SEPARATOR, fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false)
            }
        }
        if (statusText.isNotEmpty()) {
            Text(
                statusText,
                fontSize = 12.sp,
                color = DIM,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).tagged(TAG_STATUS_RUN, probe),
            )
        }
        if (previewLabel != null) {
            if (statusText.isNotEmpty()) {
                Text(StatusLineText.SEPARATOR, fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false)
            }
            Text(
                previewLabel.text,
                fontSize = 12.sp,
                color = if (previewLabel == PreviewOriginLabel.SAVED) DIM else accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).tagged(TAG_STATUS_ORIGIN, probe),
            )
        }
    }
}
