package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.draft.DraftText
import dev.kstep.desktop.edit.BannerActions
import dev.kstep.desktop.edit.BannerButton
import dev.kstep.desktop.edit.BannerKey
import dev.kstep.desktop.edit.BannerKeyResult
import dev.kstep.desktop.edit.BannerKind
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.LineEnding
import dev.kstep.desktop.edit.bannerKind
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.menu.bannerShortcutHint
import dev.kstep.desktop.run.DesktopErrorCodes
import kotlinx.coroutines.delay
import java.nio.file.Path

private const val EMPHASIS_MS = 400L

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
    /** Counts "Banner Actions" presses (ADR-0086): a change moves the focus to the first usable button. */
    focusSignal: Int = 0,
    /** Focus goes back to the editor (after a button was pressed with the keyboard, or on Escape). */
    onLeave: () -> Unit = {},
) {
    val state = view.state
    val kind = bannerKind(view) ?: return
    var emphasised by remember { mutableStateOf(false) }
    val os = remember { MenuOs.current() }
    val slots = BannerActions.slots(view)
    val requesters = remember(slots.size) { List(slots.size) { FocusRequester() } }
    var focusedIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(focusSignal) {
        if (focusSignal > 0) {
            BannerActions.firstFocus(slots)?.let { runCatching { requesters[it].requestFocus() } }
        }
    }
    val activate: (BannerButton) -> Unit = { button ->
        when (button) {
            BannerButton.RESTORE -> onRestore()
            BannerButton.DISCARD -> onDiscard()
            BannerButton.OPEN -> view.draftElsewhere?.let { onOpenDraft(it.path) }
            BannerButton.OK -> onDismissNotice()
            BannerButton.RELOAD -> onReload()
            BannerButton.KEEP_MINE -> onKeepMine()
        }
    }
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
            .onPreviewKeyEvent { event ->
                val bannerKey = event.toBannerKey()
                if (focusedIndex < 0 || bannerKey == BannerKey.OTHER) return@onPreviewKeyEvent false
                // KeyUp is swallowed too, so the built-in click of the button does not fire a second time
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (val result = BannerActions.onKey(bannerKey, focusedIndex, slots)) {
                    is BannerKeyResult.Move -> runCatching { requesters[result.index].requestFocus() }
                    is BannerKeyResult.Activate -> {
                        activate(result.button)
                        onLeave()
                    }
                    BannerKeyResult.Leave -> onLeave()
                    BannerKeyResult.Ignore -> Unit
                }
                true
            }.fillMaxWidth()
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
        if (slots.any { it.enabled }) {
            Text(bannerShortcutHint(os), fontSize = 11.sp, color = textColor.copy(alpha = 0.6f), maxLines = 1)
        }
        slots.forEachIndexed { index, slot ->
            val ring = focusedIndex == index
            TextButton(
                onClick = { activate(slot.button) },
                enabled = slot.enabled,
                modifier =
                    Modifier
                        .focusRequester(requesters[index])
                        .onFocusChanged {
                            if (it.isFocused) {
                                focusedIndex = index
                            } else if (focusedIndex == index) {
                                focusedIndex = -1
                            }
                        }.then(
                            if (ring) {
                                Modifier.border(2.dp, if (dark) ACCENT_DARK else ACCENT_LIGHT, RoundedCornerShape(4.dp))
                            } else {
                                Modifier
                            },
                        ),
            ) {
                Text(slot.button.label, fontSize = 12.sp)
            }
        }
    }
}

private fun KeyEvent.toBannerKey(): BannerKey =
    when (key) {
        Key.Tab -> if (isShiftPressed) BannerKey.SHIFT_TAB else BannerKey.TAB
        Key.DirectionLeft -> BannerKey.LEFT
        Key.DirectionRight -> BannerKey.RIGHT
        Key.Enter, Key.NumPadEnter -> BannerKey.ENTER
        Key.Spacebar -> BannerKey.SPACE
        Key.Escape -> BannerKey.ESCAPE
        else -> BannerKey.OTHER
    }

private fun LineEnding.label(): String = if (this == LineEnding.CRLF) "CRLF" else "LF"
