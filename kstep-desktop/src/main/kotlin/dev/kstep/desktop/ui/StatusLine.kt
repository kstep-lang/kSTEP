package dev.kstep.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.run.SandboxNotice
import dev.kstep.desktop.status.FittedStatus
import dev.kstep.desktop.status.PreviewOriginLabel
import dev.kstep.desktop.status.StatusLineText
import dev.kstep.desktop.status.StatusSegment
import dev.kstep.desktop.status.StatusSegments

internal const val TAG_STATUS_FILE = "status.file"
internal const val TAG_STATUS_EXPORT = "status.export"
internal const val TAG_STATUS_SANDBOX = "status.sandbox"
internal const val TAG_STATUS_RUN = "status.run"
internal const val TAG_STATUS_ORIGIN = "status.origin"
private const val FILE_MIN_DP = 64
private const val FILE_MAX_DP = 220
private const val GROUP_WEIGHT = 2f

internal fun Modifier.tagged(
    tag: String,
    probe: ((String, Rect) -> Unit)?,
): Modifier =
    testTag(tag).let { tagged ->
        if (probe == null) tagged else tagged.onGloballyPositioned { probe(tag, it.boundsInRoot()) }
    }

/**
 * The left part of the bottom status bar (ADR-0099, formerly the right part of the toolbar, ADR-0064): file name, then the export line, the sandbox notice and the run status.
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
    statusSegments: List<StatusSegment>,
    /** Reports what the run status shows after fitting (tests only). */
    fitProbe: ((FittedStatus) -> Unit)? = null,
    probe: ((String, Rect) -> Unit)? = null,
    /** What the picture came from (ADR-0086); drawn in the accent colour when it is an unsaved buffer. */
    previewLabel: PreviewOriginLabel? = null,
    accent: Color = ACCENT_LIGHT,
) {
    val hasRight = exportText != null || sandboxNotice != null || statusSegments.isNotEmpty() || previewLabel != null
    Text(
        StatusLineText.fileLabel(fileName),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface,
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
            StatusBarLink("Show in Folder", onShowInFolder)
            if (sandboxNotice != null || statusSegments.isNotEmpty()) {
                Text(StatusLineText.SEPARATOR, fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false)
            }
        }
        if (sandboxNotice != null) {
            SandboxMark(
                sandboxNotice,
                warningTextColor(isSystemInDarkTheme()),
                Modifier.tagged(TAG_STATUS_SANDBOX, probe),
            )
            if (statusSegments.isNotEmpty() || previewLabel != null) {
                Text(StatusLineText.SEPARATOR, fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false)
            }
        }
        if (statusSegments.isNotEmpty()) {
            RunStatusText(
                statusSegments,
                fitProbe,
                Modifier.weight(1f, fill = false).tagged(TAG_STATUS_RUN, probe),
            )
        }
        if (previewLabel != null) {
            if (statusSegments.isNotEmpty()) {
                Text(StatusLineText.SEPARATOR, fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false)
            }
            Text(
                previewLabel.text,
                fontSize = 12.sp,
                color = if (previewLabel == PreviewOriginLabel.SAVED) DIM else accent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                // Unweighted (ADR-0094 D3d-2): it takes its natural width, the run status gets the rest.
                modifier = Modifier.tagged(TAG_STATUS_ORIGIN, probe),
            )
        }
    }
}

/**
 * The run status (ADR-0094): whole segments, the least important ones left out when the room is short. The full text is
 * the accessibility description and the tooltip once something is left out (or the last segment is still cut).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunStatusText(
    segments: List<StatusSegment>,
    fitProbe: ((FittedStatus) -> Unit)?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val fitted = StatusSegments.fit(segments, StatusSegments.maxChars(maxWidth.value))
        fitProbe?.invoke(fitted)
        var cut by remember { mutableStateOf(false) }
        val text: @Composable () -> Unit = {
            Text(
                fitted.shown,
                fontSize = 12.sp,
                color = DIM,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { cut = it.hasVisualOverflow },
                modifier = Modifier.semantics { contentDescription = fitted.full },
            )
        }
        if (fitted.droppedCount > 0 || cut) {
            TooltipArea(
                tooltip = {
                    Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                        Text(
                            fitted.full,
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                },
            ) { text() }
        } else {
            text()
        }
    }
}
