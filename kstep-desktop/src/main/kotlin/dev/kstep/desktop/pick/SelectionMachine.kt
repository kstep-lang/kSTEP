package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable

/**
 * [hovered] is a part index of the current scene; the selection is a part NAME plus the [occurrence] (n-th part of
 * that name, 0-based), so it survives re-runs and still tells apart repeated instances of one product (ADR-0053).
 */
data class SelectionState(
    val hovered: Int? = null,
    val selected: String? = null,
    val occurrence: Int = 0,
)

sealed interface SelectionEvent {
    data class HoverAt(
        val part: Int?,
    ) : SelectionEvent

    /** A click on part [part] (named [name]) or on empty space (`null`, `null`). */
    data class ClickAt(
        val part: Int?,
        val name: String?,
        /** Which part of that name was hit: 0 for the first, 1 for the second, ... */
        val occurrence: Int = 0,
    ) : SelectionEvent

    data object DragStart : SelectionEvent

    data object Escape : SelectionEvent

    data object PointerExit : SelectionEvent

    data class SceneReplaced(
        val table: PartTable?,
    ) : SelectionEvent
}

object SelectionMachine {
    fun reduce(
        s: SelectionState,
        e: SelectionEvent,
    ): SelectionState =
        when (e) {
            is SelectionEvent.HoverAt -> s.copy(hovered = e.part)
            is SelectionEvent.ClickAt ->
                if (e.part == null) {
                    s.copy(selected = null, occurrence = 0)
                } else {
                    s.copy(selected = e.name, occurrence = e.occurrence)
                }
            SelectionEvent.DragStart, SelectionEvent.PointerExit -> s.copy(hovered = null)
            SelectionEvent.Escape -> s.copy(selected = null, occurrence = 0)
            is SelectionEvent.SceneReplaced -> {
                val keep = s.selected != null && e.table != null && e.table.indexOfName(s.selected, s.occurrence) >= 0
                if (keep) {
                    SelectionState(hovered = null, selected = s.selected, occurrence = s.occurrence)
                } else {
                    SelectionState()
                }
            }
        }

    /** Part index of the selection in [table] (the n-th part of that name), or `-1`. */
    fun selectedIndex(
        s: SelectionState,
        table: PartTable?,
    ): Int = if (s.selected == null || table == null) -1 else table.indexOfName(s.selected, s.occurrence)
}
