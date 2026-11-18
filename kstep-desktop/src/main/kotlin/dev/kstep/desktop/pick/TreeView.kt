package dev.kstep.desktop.pick

/** Which groups are open, by node key, and which keys the tree had when this state was made (ADR-0054). */
data class TreeViewState(
    val expanded: Set<String>,
    val known: Set<String>,
)

/** Keys the tree panel reacts to. */
enum class TreeKey { Up, Down, Left, Right, Home, End, Enter, Escape }

/** A node the key press selects. */
data class TreeTarget(
    val node: Int,
)

/** The result of one key press: the new view state, a node to select (`null` keeps the selection), and whether to clear it. */
data class TreeStep(
    val view: TreeViewState,
    val select: TreeTarget? = null,
    val clearSelection: Boolean = false,
)

/** Open/close state and the list of visible rows of the assembly tree. Pure logic, no UI types (ADR-0054). */
object TreeViewLogic {
    /** Groups of this depth or less are open at first sight (root and its children). */
    const val INITIAL_OPEN_DEPTH: Int = 1

    fun initial(tree: AssemblyTree): TreeViewState = replaced(tree, emptySet(), emptySet())

    private fun replaced(
        tree: AssemblyTree,
        oldExpanded: Set<String>,
        oldKnown: Set<String>,
    ): TreeViewState {
        val expanded = HashSet<String>()
        val known = HashSet<String>(tree.size * 2)
        for (n in 0 until tree.size) {
            val key = tree.key(n)
            known += key
            if (!tree.isGroup(n)) continue
            val open = if (key in oldKnown) key in oldExpanded else tree.depth(n) <= INITIAL_OPEN_DEPTH
            if (open) expanded += key
        }
        return TreeViewState(expanded, known)
    }

    /** The nodes to draw, in order: a closed group hides its whole subtree. O(n). */
    fun visibleRows(
        tree: AssemblyTree,
        s: TreeViewState,
    ): IntArray {
        val rows = IntArray(tree.size)
        var count = 0
        var i = 0
        while (i < tree.size) {
            rows[count++] = i
            i = if (tree.isGroup(i) && tree.key(i) !in s.expanded) tree.subtreeEnd(i) else i + 1
        }
        return rows.copyOf(count)
    }

    fun isExpanded(
        tree: AssemblyTree,
        s: TreeViewState,
        node: Int,
    ): Boolean = tree.isGroup(node) && tree.key(node) in s.expanded

    fun toggle(
        tree: AssemblyTree,
        s: TreeViewState,
        node: Int,
    ): TreeViewState {
        if (!tree.isGroup(node)) return s
        val key = tree.key(node)
        return s.copy(expanded = if (key in s.expanded) s.expanded - key else s.expanded + key)
    }

    fun setExpanded(
        tree: AssemblyTree,
        s: TreeViewState,
        node: Int,
        open: Boolean,
    ): TreeViewState {
        if (!tree.isGroup(node) || isExpanded(tree, s, node) == open) return s
        return toggle(tree, s, node)
    }

    /** Opens every ancestor of [node] so that its row becomes visible. */
    fun expandAncestors(
        tree: AssemblyTree,
        s: TreeViewState,
        node: Int,
    ): TreeViewState {
        if (node !in 0 until tree.size) return s
        var expanded = s.expanded
        var cur = tree.parentOf(node)
        while (cur >= 0) {
            val key = tree.key(cur)
            if (key !in expanded) expanded = expanded + key
            cur = tree.parentOf(cur)
        }
        return if (expanded === s.expanded) s else s.copy(expanded = expanded)
    }

    /**
     * Carries the open state over to a new run: keys that still exist keep their state, vanished keys are dropped,
     * and nodes the old tree did not have follow the first-sight rule.
     */
    fun onTreeReplaced(
        old: AssemblyTree?,
        new: AssemblyTree,
        s: TreeViewState,
    ): TreeViewState = if (old == null) initial(new) else replaced(new, s.expanded, s.known)

    /** Keyboard navigation over the visible [rows] (indices of nodes); [selectedNode] is `-1` without a selection. */
    fun onKey(
        tree: AssemblyTree,
        view: TreeViewState,
        rows: IntArray,
        selectedNode: Int,
        key: TreeKey,
    ): TreeStep {
        if (rows.isEmpty()) return TreeStep(view)
        val at =
            java.util.Arrays
                .binarySearch(rows, selectedNode)
                .takeIf { selectedNode >= 0 && it >= 0 } ?: -1

        fun select(row: Int) = TreeStep(view, TreeTarget(rows[row]))
        return when (key) {
            TreeKey.Down -> select(if (at < 0) 0 else minOf(at + 1, rows.size - 1))
            TreeKey.Up -> select(if (at < 0) rows.size - 1 else maxOf(at - 1, 0))
            TreeKey.Home -> select(0)
            TreeKey.End -> select(rows.size - 1)
            TreeKey.Right -> {
                when {
                    at < 0 || !tree.isGroup(selectedNode) -> TreeStep(view)
                    !isExpanded(tree, view, selectedNode) -> TreeStep(setExpanded(tree, view, selectedNode, true))
                    else -> TreeStep(view, TreeTarget(selectedNode + 1))
                }
            }
            TreeKey.Left -> {
                when {
                    at < 0 -> TreeStep(view)
                    isExpanded(tree, view, selectedNode) -> TreeStep(setExpanded(tree, view, selectedNode, false))
                    tree.parentOf(selectedNode) >= 0 -> TreeStep(view, TreeTarget(tree.parentOf(selectedNode)))
                    else -> TreeStep(view)
                }
            }
            TreeKey.Enter ->
                if (at >= 0 &&
                    tree.isGroup(selectedNode)
                ) {
                    TreeStep(toggle(tree, view, selectedNode))
                } else {
                    TreeStep(view)
                }
            TreeKey.Escape -> TreeStep(view, clearSelection = selectedNode >= 0)
        }
    }
}
