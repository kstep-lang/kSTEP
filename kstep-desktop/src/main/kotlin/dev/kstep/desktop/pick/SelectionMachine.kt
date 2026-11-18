package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable

/**
 * [hovered] is a part index of the current scene; the selection is a part NAME plus the [occurrence] (n-th part of
 * that name, 0-based), so it survives re-runs and still tells apart repeated instances of one product (ADR-0053).
 *
 * [selectedGroup] is the path key of a selected assembly group (ADR-0054); it excludes [selected]. [treeHover] is the
 * part range of the group or part under the pointer in the assembly tree.
 */
data class SelectionState(
    val hovered: Int? = null,
    val selected: String? = null,
    val occurrence: Int = 0,
    val selectedGroup: String? = null,
    val treeHover: IntRange? = null,
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

    /** A group of the assembly tree was selected. */
    data class SelectGroup(
        val key: String,
    ) : SelectionEvent

    /** The pointer is over a tree row covering [range] (`null`: it left the tree). */
    data class TreeHover(
        val range: IntRange?,
    ) : SelectionEvent

    data object DragStart : SelectionEvent

    data object Escape : SelectionEvent

    data object PointerExit : SelectionEvent

    data class SceneReplaced(
        val table: PartTable?,
        val tree: AssemblyTree? = null,
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
                    s.copy(selected = null, occurrence = 0, selectedGroup = null)
                } else {
                    s.copy(selected = e.name, occurrence = e.occurrence, selectedGroup = null)
                }
            is SelectionEvent.SelectGroup -> s.copy(selected = null, occurrence = 0, selectedGroup = e.key)
            is SelectionEvent.TreeHover -> s.copy(treeHover = e.range)
            SelectionEvent.DragStart, SelectionEvent.PointerExit -> s.copy(hovered = null)
            SelectionEvent.Escape -> s.copy(selected = null, occurrence = 0, selectedGroup = null)
            is SelectionEvent.SceneReplaced -> {
                val keepPart =
                    s.selected != null && e.table != null && e.table.indexOfName(s.selected, s.occurrence) >= 0
                val keepGroup =
                    s.selectedGroup != null &&
                        e.tree != null &&
                        e.tree.nodeOfKey(s.selectedGroup).let { it >= 0 && e.tree.isGroup(it) }
                SelectionState(
                    hovered = null,
                    selected = if (keepPart) s.selected else null,
                    occurrence = if (keepPart) s.occurrence else 0,
                    selectedGroup = if (keepGroup) s.selectedGroup else null,
                    treeHover = null,
                )
            }
        }

    /** Part index of the selection in [table] (the n-th part of that name), or `-1`. */
    fun selectedIndex(
        s: SelectionState,
        table: PartTable?,
    ): Int = if (s.selected == null || table == null) -1 else table.indexOfName(s.selected, s.occurrence)

    /** The part range the selection covers: a group's range, a single part, or [NONE] (`-1..-1`). */
    fun highlightRange(
        s: SelectionState,
        tree: AssemblyTree?,
        table: PartTable?,
    ): IntRange {
        if (s.selectedGroup != null && tree != null) {
            val node = tree.nodeOfKey(s.selectedGroup)
            if (node >= 0 && tree.isGroup(node)) return tree.firstPart(node)..tree.lastPart(node)
        }
        val index = selectedIndex(s, table)
        return if (index >= 0) index..index else NONE
    }

    val NONE: IntRange = -1..-1
}
