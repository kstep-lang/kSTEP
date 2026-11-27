package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable

/** One part of a selection: its name and which part of that name (0-based), so repeated instances stay apart. */
data class PartRef(
    val name: String,
    val occurrence: Int,
)

/** A selection never holds more parts than this (ADR-0092). */
const val MAX_SELECTION = 1000

/**
 * [hovered] is a part index of the current scene; the selection is a part NAME plus the [occurrence] (n-th part of
 * that name, 0-based), so it survives re-runs and still tells apart repeated instances of one product (ADR-0053).
 *
 * [selectedGroup] is the path key of a selected assembly group (ADR-0054); it excludes [selected]. [treeHover] is the
 * part range of the group or part under the pointer in the assembly tree.
 *
 * A multi-selection (ADR-0092) is the primary part ([selected], [occurrence]) followed by [more]; [parts] lists them in
 * that order. The primary stays the single source for code that only knows one part (inspection, Show in Script), so
 * the two never drift. [anchor] is the part a range selection starts from; `null` means the primary part.
 */
data class SelectionState(
    val hovered: Int? = null,
    val selected: String? = null,
    val occurrence: Int = 0,
    val selectedGroup: String? = null,
    val treeHover: IntRange? = null,
    val more: List<PartRef> = emptyList(),
    val anchor: PartRef? = null,
) {
    /** Every selected part, primary first; empty while a group or nothing is selected. */
    val parts: List<PartRef>
        get() = if (selected == null) emptyList() else listOf(PartRef(selected, occurrence)) + more

    val anchorRef: PartRef? get() = anchor ?: selected?.let { PartRef(it, occurrence) }
}

/** What the user should be told about a selection that was cut short. */
sealed interface SelectionNotice {
    data object LimitReached : SelectionNotice

    data class Truncated(
        val total: Int,
    ) : SelectionNotice
}

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

    /**
     * Cmd/Ctrl+click: adds the part to the selection or removes it. While a group is selected the caller resolves it to
     * [groupRefs] (at most [MAX_SELECTION] + 1 entries, [groupTotal] is the real count), so the reducer stays pure.
     */
    data class TogglePart(
        val part: Int?,
        val name: String?,
        val occurrence: Int = 0,
        val groupRefs: List<PartRef> = emptyList(),
        val groupTotal: Int = groupRefs.size,
    ) : SelectionEvent

    /** Shift+click: the parts between the anchor and the click; the anchor stays. */
    data class SelectRange(
        val refs: List<PartRef>,
        val total: Int = refs.size,
    ) : SelectionEvent

    /** Replaces the selection by [refs] (e.g. "Select Children"). */
    data class SelectParts(
        val refs: List<PartRef>,
        val total: Int = refs.size,
    ) : SelectionEvent

    /** A context menu was asked for on a part (`null`: empty space, nothing changes). */
    data class ContextAt(
        val part: Int?,
        val name: String?,
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
    ): SelectionState = reduceWithNotice(s, e).first

    fun reduceWithNotice(
        s: SelectionState,
        e: SelectionEvent,
    ): Pair<SelectionState, SelectionNotice?> =
        when (e) {
            is SelectionEvent.HoverAt -> s.copy(hovered = e.part) to null
            is SelectionEvent.ClickAt -> click(s, e.part, e.name, e.occurrence) to null
            is SelectionEvent.ContextAt ->
                if (e.part == null || e.name == null) {
                    s to null
                } else if (PartRef(e.name, e.occurrence) in s.parts) {
                    s to null
                } else {
                    click(s, e.part, e.name, e.occurrence) to null
                }
            is SelectionEvent.TogglePart -> toggle(s, e)
            is SelectionEvent.SelectRange -> {
                val keep = s.anchorRef
                val (next, notice) = withParts(s, e.refs, e.total)
                (if (keep != null && keep != next.parts.firstOrNull()) next.copy(anchor = keep) else next) to notice
            }
            is SelectionEvent.SelectParts -> withParts(s, e.refs, e.total)
            is SelectionEvent.SelectGroup -> cleared(s).copy(selectedGroup = e.key) to null
            is SelectionEvent.TreeHover -> s.copy(treeHover = e.range) to null
            SelectionEvent.DragStart, SelectionEvent.PointerExit -> s.copy(hovered = null) to null
            SelectionEvent.Escape -> cleared(s) to null
            is SelectionEvent.SceneReplaced -> sceneReplaced(s, e) to null
        }

    private fun cleared(s: SelectionState): SelectionState =
        s.copy(selected = null, occurrence = 0, selectedGroup = null, more = emptyList(), anchor = null)

    private fun click(
        s: SelectionState,
        part: Int?,
        name: String?,
        occurrence: Int,
    ): SelectionState =
        if (part == null || name == null) {
            cleared(s)
        } else {
            cleared(s).copy(selected = name, occurrence = occurrence)
        }

    /** The selection becomes [refs] (without duplicates, cut to [MAX_SELECTION]); the anchor is the first part. */
    private fun withParts(
        s: SelectionState,
        refs: List<PartRef>,
        total: Int,
    ): Pair<SelectionState, SelectionNotice?> {
        val unique = LinkedHashSet<PartRef>()
        for (r in refs) {
            if (unique.size >= MAX_SELECTION) break
            unique += r
        }
        val list = unique.toList()
        val notice = if (total > list.size && total > MAX_SELECTION) SelectionNotice.Truncated(total) else null
        val first = list.firstOrNull()
        val next =
            if (first == null) {
                cleared(s)
            } else {
                cleared(s).copy(selected = first.name, occurrence = first.occurrence, more = list.drop(1))
            }
        return next to notice
    }

    private fun toggle(
        s: SelectionState,
        e: SelectionEvent.TogglePart,
    ): Pair<SelectionState, SelectionNotice?> {
        if (e.part == null || e.name == null) return s to null
        val ref = PartRef(e.name, e.occurrence)
        val base: List<PartRef>
        if (s.selectedGroup != null) {
            if (e.groupTotal > MAX_SELECTION) {
                return click(s, e.part, e.name, e.occurrence) to SelectionNotice.Truncated(e.groupTotal)
            }
            base = e.groupRefs
        } else {
            base = s.parts
        }
        if (ref in base) {
            val rest = base.filter { it != ref }
            val first = rest.firstOrNull() ?: return cleared(s) to null
            return cleared(s).copy(
                selected = first.name,
                occurrence = first.occurrence,
                more = rest.drop(1),
            ) to null
        }
        if (base.size >= MAX_SELECTION) return s to SelectionNotice.LimitReached
        val all = base + ref
        val first = all.first()
        return cleared(s).copy(
            selected = first.name,
            occurrence = first.occurrence,
            more = all.drop(1),
            anchor = ref.takeIf { it != first },
        ) to null
    }

    private fun sceneReplaced(
        s: SelectionState,
        e: SelectionEvent.SceneReplaced,
    ): SelectionState {
        val table = e.table
        val kept = if (table == null) emptyList() else s.parts.filter { table.indexOfName(it.name, it.occurrence) >= 0 }
        val keepGroup =
            s.selectedGroup != null &&
                e.tree != null &&
                e.tree.nodeOfKey(s.selectedGroup).let { it >= 0 && e.tree.isGroup(it) }
        val first = kept.firstOrNull()
        val anchor = s.anchor?.takeIf { it in kept && it != first }
        return SelectionState(
            hovered = null,
            selected = first?.name,
            occurrence = first?.occurrence ?: 0,
            selectedGroup = if (keepGroup) s.selectedGroup else null,
            treeHover = null,
            more = kept.drop(1),
            anchor = anchor,
        )
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

    /**
     * Part indices of the parts the selection holds, in selection order (a group counts as its range). One O(n + k)
     * pass, never one lookup per part.
     */
    fun partIndices(
        s: SelectionState,
        tree: AssemblyTree?,
        table: PartTable?,
    ): IntArray {
        if (table == null) return IntArray(0)
        if (s.selectedGroup != null) {
            val r = highlightRange(s, tree, table)
            return if (r == NONE) IntArray(0) else IntArray(r.last - r.first + 1) { r.first + it }
        }
        val refs = s.parts
        if (refs.isEmpty()) return IntArray(0)
        if (refs.size == 1) return selectedIndex(s, table).let { if (it >= 0) intArrayOf(it) else IntArray(0) }
        val wanted = HashMap<PartRef, Int>(refs.size * 2)
        refs.forEachIndexed { i, r -> wanted[r] = i }
        val byOrder = IntArray(refs.size) { -1 }
        val occurrences = table.occurrenceArray()
        for (p in table.parts.indices) {
            val at = wanted[PartRef(table.parts[p].name, occurrences[p])] ?: continue
            byOrder[at] = p
        }
        return byOrder.filter { it >= 0 }.toIntArray()
    }

    /** One flag per part of [table] for a multi-selection of more than one part, else `null` (single range or none). */
    fun highlightMask(
        s: SelectionState,
        table: PartTable?,
    ): BooleanArray? {
        if (table == null || s.selectedGroup != null || s.more.isEmpty()) return null
        val mask = BooleanArray(table.parts.size)
        for (p in partIndices(s, null, table)) mask[p] = true
        return mask
    }

    /** The parts of a group as references (at most [MAX_SELECTION] + 1, so a caller can tell it was too big). */
    fun groupRefs(
        tree: AssemblyTree,
        node: Int,
        table: PartTable,
    ): List<PartRef> {
        if (node !in 0 until tree.size) return emptyList()
        val first = tree.firstPart(node)
        val last = minOf(tree.lastPart(node), table.parts.size - 1)
        val occurrences = table.occurrenceArray()
        val out = ArrayList<PartRef>()
        for (p in first..last) {
            if (out.size > MAX_SELECTION) break
            out += PartRef(table.parts[p].name, occurrences[p])
        }
        return out
    }

    val NONE: IntRange = -1..-1
}

/**
 * Remembers the last multi-selection mask: a hover or a camera move changes the [SelectionState] but not the selected
 * parts, and rebuilding the mask (O(parts)) 30 times a second would be waste. UI thread only.
 */
class MaskMemo {
    private var table: PartTable? = null
    private var selected: String? = null
    private var occurrence = 0
    private var more: List<PartRef>? = null
    private var mask: BooleanArray? = null

    fun of(
        s: SelectionState,
        table: PartTable,
    ): BooleanArray? {
        if (s.more.isEmpty() || s.selectedGroup != null) return null
        if (this.table === table && selected == s.selected && occurrence == s.occurrence && more == s.more) return mask
        this.table = table
        selected = s.selected
        occurrence = s.occurrence
        more = s.more
        mask = SelectionMachine.highlightMask(s, table)
        return mask
    }
}
