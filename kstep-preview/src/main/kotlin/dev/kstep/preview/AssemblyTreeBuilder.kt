package dev.kstep.preview

// Assembly hierarchy for the desktop worker (ADR-0054). Pure (no OCCT), so the algorithm is unit-testable.

/** One step of an instance's usage path: the usage object's identity and the display name of the part it leads to. */
internal class ChainStep(
    val identity: Any,
    val name: String,
)

/**
 * The path of one drawn instance from its root, expressed as identity keys: [rootKey]/[rootName] for the root
 * definition, then one [ChainStep] per usage. [leafName] is the instance's own display name and [leafSpan] its index
 * in the part-span list (instances and spans are in the same order).
 */
internal class InstanceChain(
    val rootKey: Any,
    val rootName: String,
    val steps: List<ChainStep>,
    val leafName: String,
    val leafSpan: Int,
)

/** Pre-order tree over part-span indices, before the GLB writer dropped any triangles. [span] is -1 for a group. */
internal class RawAssemblyTree(
    val names: List<String>,
    val parents: IntArray,
    val span: IntArray,
) {
    val size: Int get() = names.size

    /**
     * Re-indexes the leaves with [partOfSpan] (span index to part index, `-1` for a vanished part), drops leaves
     * without a part and groups left without a leaf. Returns `null` if nothing is left.
     */
    fun reindexed(partOfSpan: IntArray): RenderedAssemblyTree? {
        val keep = BooleanArray(size)
        for (i in size - 1 downTo 0) {
            if (span[i] >= 0 && partOfSpan[span[i]] >= 0) keep[i] = true
            if (keep[i] && parents[i] >= 0) keep[parents[i]] = true
        }
        val newIndex = IntArray(size) { -1 }
        val nodes = ArrayList<RenderedTreeNode>()
        for (i in 0 until size) {
            if (!keep[i]) continue
            newIndex[i] = nodes.size
            val parent = if (parents[i] < 0) -1 else newIndex[parents[i]]
            nodes += RenderedTreeNode(names[i], parent, if (span[i] >= 0) partOfSpan[span[i]] else -1)
        }
        return if (nodes.isEmpty()) null else RenderedAssemblyTree(nodes)
    }
}

/**
 * Builds the hierarchy from the instance chains, given in depth-first order. Common prefixes are found by IDENTITY
 * against the previous chain, never by a trie: the same usage object hangs on every instance of a repeated parent, so
 * merging by identity alone would fuse repeated sub-assemblies (the keys of different parent occurrences differ at an
 * earlier step, which is what splits them here).
 *
 * An instance whose path is a prefix of later instances (a shaped sub-assembly) becomes a group whose first child is
 * a leaf of the same name carrying its own geometry. More than one root gets a synthetic "Assembly" root.
 */
internal fun buildAssemblyTree(chains: List<InstanceChain>): RawAssemblyTree? {
    if (chains.isEmpty()) return null
    val names = ArrayList<String>()
    val parents = ArrayList<Int>()
    val span = ArrayList<Int>()
    // open nodes: key identity and node index, one per depth
    val stackKeys = ArrayList<Any>()
    val stackNodes = ArrayList<Int>()

    fun add(
        name: String,
        parent: Int,
        leafSpan: Int,
    ): Int {
        names += name
        parents += parent
        span += leafSpan
        return names.size - 1
    }
    for (chain in chains) {
        val length = 1 + chain.steps.size

        fun keyAt(k: Int): Any = if (k == 0) chain.rootKey else chain.steps[k - 1].identity
        var common = 0
        while (common < stackKeys.size && common < length && stackKeys[common] === keyAt(common)) common++
        if (common == length) common = length - 1 // identical full path cannot happen; keep it a sibling
        if (common == stackKeys.size && common > 0 && span[stackNodes.last()] >= 0) {
            // the previous leaf is an ancestor of this instance: it becomes a group with its geometry as first child
            val top = stackNodes.last()
            add(names[top], top, span[top])
            span[top] = -1
        }
        while (stackKeys.size > common) {
            stackKeys.removeLast()
            stackNodes.removeLast()
        }
        for (k in common until length) {
            val isLeaf = k == length - 1
            val name =
                if (isLeaf) {
                    chain.leafName
                } else if (k == 0) {
                    chain.rootName
                } else {
                    chain.steps[k - 1].name
                }
            val node = add(name, stackNodes.lastOrNull() ?: -2, if (isLeaf) chain.leafSpan else -1)
            stackKeys += keyAt(k)
            stackNodes += node
        }
    }
    val topLevel = parents.count { it == -2 }
    if (topLevel == 1) {
        return RawAssemblyTree(
            names,
            IntArray(parents.size) {
                if (parents[it] ==
                    -2
                ) {
                    -1
                } else {
                    parents[it]
                }
            },
            span.toIntArray(),
        )
    }
    // several roots: synthetic group in front, everything shifts by one
    val shiftedNames = listOf("Assembly") + names
    val shiftedParents = IntArray(parents.size + 1)
    shiftedParents[0] = -1
    for (i in parents.indices) shiftedParents[i + 1] = if (parents[i] == -2) 0 else parents[i] + 1
    return RawAssemblyTree(shiftedNames, shiftedParents, (listOf(-1) + span).toIntArray())
}
