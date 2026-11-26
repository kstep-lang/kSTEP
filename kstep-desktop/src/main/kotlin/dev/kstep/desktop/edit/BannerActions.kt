package dev.kstep.desktop.edit

import dev.kstep.desktop.draft.OfferKind

/** What the banner says; at most one message at a time, the most urgent first (ADR-0052). */
enum class BannerKind {
    DRAFT_OFFER,
    DRAFT_ELSEWHERE,
    CONFLICT,
    SAVE_ERROR,
    PREVIEW_ONLY,
    DELETED,
    LINE_ENDINGS,
    DRAFT_NOTICE,
}

/** The banner that [view] shows, or null: the one source of truth for [BannerActions.slots] and the UI. */
fun bannerKind(view: EditorView): BannerKind? =
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

/** The buttons a banner can carry (ADR-0086). */
enum class BannerButton(
    val label: String,
) {
    RESTORE("Restore"),
    DISCARD("Discard"),
    OPEN("Open"),
    OK("OK"),
    RELOAD("Reload"),
    KEEP_MINE("Keep mine"),
}

data class BannerSlot(
    val button: BannerButton,
    val enabled: Boolean,
)

/** The keys the focused banner handles; everything else is [OTHER]. */
enum class BannerKey { TAB, SHIFT_TAB, LEFT, RIGHT, ENTER, SPACE, ESCAPE, OTHER }

sealed interface BannerKeyResult {
    data class Move(
        val index: Int,
    ) : BannerKeyResult

    data class Activate(
        val button: BannerButton,
    ) : BannerKeyResult

    /** Focus goes back to the editor. */
    data object Leave : BannerKeyResult

    data object Ignore : BannerKeyResult
}

/** Keyboard handling of the banner buttons, free of Compose (ADR-0086): the order is the order they are drawn in. */
object BannerActions {
    /** The buttons of the banner of [view] in drawing order; a banner without buttons gives an empty list. */
    fun slots(view: EditorView): List<BannerSlot> =
        when (bannerKind(view)) {
            BannerKind.DRAFT_OFFER ->
                listOf(
                    BannerSlot(BannerButton.RESTORE, view.draftOffer!!.kind != OfferKind.NOT_EDITABLE),
                    BannerSlot(BannerButton.DISCARD, true),
                )
            BannerKind.CONFLICT ->
                listOf(
                    BannerSlot(BannerButton.RELOAD, (view.state as EditorState.Editing).conflict!!.reloadable),
                    BannerSlot(BannerButton.KEEP_MINE, true),
                )
            BannerKind.DRAFT_ELSEWHERE -> listOf(BannerSlot(BannerButton.OPEN, true))
            BannerKind.DRAFT_NOTICE -> listOf(BannerSlot(BannerButton.OK, true))
            BannerKind.PREVIEW_ONLY,
            BannerKind.SAVE_ERROR,
            BannerKind.DELETED,
            BannerKind.LINE_ENDINGS,
            null,
            -> emptyList()
        }

    fun hasActions(view: EditorView): Boolean = slots(view).any { it.enabled }

    /** The button that takes the focus first: the first enabled one. */
    fun firstFocus(slots: List<BannerSlot>): Int? = slots.indexOfFirst { it.enabled }.takeIf { it >= 0 }

    fun onKey(
        key: BannerKey,
        focused: Int,
        slots: List<BannerSlot>,
    ): BannerKeyResult =
        when (key) {
            BannerKey.TAB, BannerKey.RIGHT -> step(focused, slots, 1)
            BannerKey.SHIFT_TAB, BannerKey.LEFT -> step(focused, slots, -1)
            BannerKey.ENTER, BannerKey.SPACE ->
                slots.getOrNull(focused)?.takeIf { it.enabled }?.let { BannerKeyResult.Activate(it.button) }
                    ?: BannerKeyResult.Ignore
            BannerKey.ESCAPE -> BannerKeyResult.Leave
            BannerKey.OTHER -> BannerKeyResult.Ignore
        }

    private fun step(
        focused: Int,
        slots: List<BannerSlot>,
        direction: Int,
    ): BannerKeyResult {
        if (slots.none { it.enabled }) return BannerKeyResult.Ignore
        var i = focused
        repeat(slots.size) {
            i = Math.floorMod(i + direction, slots.size)
            if (slots[i].enabled) return BannerKeyResult.Move(i)
        }
        return BannerKeyResult.Ignore
    }
}
