package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.draft.DraftText
import dev.kstep.desktop.draft.OfferKind
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.LineEnding
import dev.kstep.desktop.run.DesktopErrorCodes
import kotlinx.coroutines.delay
import java.nio.file.Path

private const val EMPHASIS_MS = 400L

/** What the banner says; at most one message at a time, the most urgent first (ADR-0052). */
internal enum class BannerKind {
    DRAFT_OFFER,
    DRAFT_ELSEWHERE,
    CONFLICT,
    SAVE_ERROR,
    PREVIEW_ONLY,
    DELETED,
    LINE_ENDINGS,
    DRAFT_NOTICE,
}

internal fun bannerKind(view: EditorView): BannerKind? =
    when {
        view.draftOffer != null -> BannerKind.DRAFT_OFFER
        view.draftElsewhere != null && view.state is EditorState.NoFile -> BannerKind.DRAFT_ELSEWHERE
        else -> stateBannerKind(view.state) ?: if (view.draftNotice != null) BannerKind.DRAFT_NOTICE else null
    }

private fun stateBannerKind(state: EditorState): BannerKind? =
    when {
        state is EditorState.Unavailable -> BannerKind.PREVIEW_ONLY
        state !is EditorState.Editing -> null
        state.conflict != null -> BannerKind.CONFLICT
        state.saveError != null -> BannerKind.SAVE_ERROR
        state.deletedOnDisk -> BannerKind.DELETED
        state.mixedEndings -> BannerKind.LINE_ENDINGS
        else -> null
    }

/**
 * One banner above the text, never on top of it: a conflict (Reload / Keep mine), a save error, the preview-only
 * reason, a deleted file or a line-ending hint. Pressing Save during a conflict makes it flash for 400 ms.
 */
@Composable
internal fun EditorBanner(
    view: EditorView,
    dark: Boolean,
    onReload: () -> Unit,
    onKeepMine: () -> Unit,
    modifier: Modifier = Modifier,
    onRestore: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onOpenDraft: (Path) -> Unit = {},
    onDismissNotice: () -> Unit = {},
) {
    val state = view.state
    val kind = bannerKind(view) ?: return
    var emphasised by remember { mutableStateOf(false) }
    LaunchedEffect(view.pulse) {
        if (view.pulse > 0) {
            emphasised = true
            delay(EMPHASIS_MS)
            emphasised = false
        }
    }
    val base =
        when (kind) {
            BannerKind.SAVE_ERROR -> if (dark) Color(0xFF3B1F1F) else Color(0xFFFDECEA)
            BannerKind.CONFLICT -> if (dark) Color(0xFF3D3318) else Color(0xFFFFF4D6)
            else -> if (dark) Color(0xFF2A2A2A) else Color(0xFFF0F0F0)
        }
    val textColor = if (dark) Color(0xFFE8E8E8) else Color(0xFF1F1F1F)
    val message: String
    when (kind) {
        BannerKind.DRAFT_OFFER -> message = DraftText.offerMessage(view.draftOffer!!, emphasised)
        BannerKind.DRAFT_ELSEWHERE ->
            message =
                DraftText.elsewhereMessage(
                    view.draftElsewhere!!
                        .path.fileName
                        ?.toString()
                        .orEmpty(),
                )
        BannerKind.DRAFT_NOTICE -> message = DraftText.noticeMessage(view.draftNotice!!)
        BannerKind.CONFLICT -> {
            val conflict = (state as EditorState.Editing).conflict!!
            message =
                (if (emphasised) "Choose Reload or Keep mine first. " else "") +
                if (conflict.reloadable) {
                    "The file changed on disk."
                } else {
                    "The file changed on disk; Reload is not possible: ${conflict.reloadBlockedReason}."
                }
        }
        BannerKind.SAVE_ERROR ->
            message =
                "Could not save (${DesktopErrorCodes.SAVE_FAILED}): ${(state as EditorState.Editing).saveError!!.message}."
        BannerKind.PREVIEW_ONLY -> {
            val unavailable = state as EditorState.Unavailable
            message = "Preview only: ${unavailable.reason} (${unavailable.code})."
        }
        BannerKind.DELETED -> message = "The file was deleted on disk. Save recreates it."
        BannerKind.LINE_ENDINGS ->
            message = "Mixed line endings: they will be saved as ${(state as EditorState.Editing).lineEnding.label()}."
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(if (emphasised) base.copy(alpha = 1f) else base.copy(alpha = 0.92f))
            .heightIn(min = 32.dp)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            fontSize = 12.sp,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when (kind) {
            BannerKind.DRAFT_OFFER -> {
                TextButton(onClick = onRestore, enabled = view.draftOffer!!.kind != OfferKind.NOT_EDITABLE) {
                    Text("Restore", fontSize = 12.sp)
                }
                TextButton(onClick = onDiscard) { Text("Discard", fontSize = 12.sp) }
            }
            BannerKind.DRAFT_ELSEWHERE ->
                TextButton(onClick = { onOpenDraft(view.draftElsewhere!!.path) }) { Text("Open", fontSize = 12.sp) }
            BannerKind.DRAFT_NOTICE -> TextButton(onClick = onDismissNotice) { Text("OK", fontSize = 12.sp) }
            else -> Unit
        }
        if (kind == BannerKind.CONFLICT) {
            val conflict = (state as EditorState.Editing).conflict!!
            TextButton(onClick = onReload, enabled = conflict.reloadable) { Text("Reload", fontSize = 12.sp) }
            TextButton(onClick = onKeepMine) { Text("Keep mine", fontSize = 12.sp) }
        }
    }
}

private fun LineEnding.label(): String = if (this == LineEnding.CRLF) "CRLF" else "LF"
