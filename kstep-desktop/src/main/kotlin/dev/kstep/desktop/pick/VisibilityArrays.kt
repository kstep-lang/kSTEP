package dev.kstep.desktop.pick

/**
 * Everything the drawing, the picking and the tree rows need, derived once per change of the visibility in
 * O(parts + nodes): never per frame. [partMode] holds [PartMode] codes per part, [nodeState] per tree node `0..2` or
 * [MIXED] (`null` without a tree).
 */
class VisibilityArrays(
    val partMode: ByteArray,
    val nodeState: ByteArray?,
    val alpha: Float,
    val counts: VisibilityCounts,
) {
    companion object {
        const val MIXED: Byte = 3
        private const val UNSET: Byte = -1

        fun build(
            s: VisibilityState,
            scene: VisibilityScene,
            tree: AssemblyTree?,
        ): VisibilityArrays {
            val n = scene.size
            val modes = ByteArray(n)
            var hidden = 0
            var transparent = 0
            for (i in 0 until n) {
                when (s.leafStates[scene.partKey[i]]) {
                    Visibility.Hidden -> {
                        modes[i] = PartMode.HIDDEN
                        hidden++
                    }
                    Visibility.Transparent -> {
                        modes[i] = PartMode.TRANSPARENT
                        transparent++
                    }
                    else -> modes[i] = PartMode.VISIBLE
                }
            }
            return VisibilityArrays(
                modes,
                tree?.let { nodeStates(it, modes) },
                s.alpha,
                VisibilityCounts(hidden, transparent),
            )
        }

        // Pre-order with parent < child: one pass from the back finishes every child before its parent.
        private fun nodeStates(
            tree: AssemblyTree,
            modes: ByteArray,
        ): ByteArray {
            val state = ByteArray(tree.size) { UNSET }
            for (i in tree.size - 1 downTo 0) {
                if (!tree.isGroup(i)) {
                    val part = tree.partOf(i)
                    state[i] = if (part in modes.indices) modes[part] else PartMode.VISIBLE
                } else if (state[i] == UNSET) {
                    state[i] = PartMode.VISIBLE
                }
                val p = tree.parentOf(i)
                if (p >= 0) {
                    if (state[p] == UNSET) {
                        state[p] = state[i]
                    } else if (state[p] != state[i]) {
                        state[p] = MIXED
                    }
                }
            }
            return state
        }
    }
}

/** `"2 hidden · 5 transparent"`; a zero count is left out; empty when everything is visible. */
fun visibilityStatusText(c: VisibilityCounts): String =
    listOfNotNull(
        if (c.hidden > 0) "${c.hidden} hidden" else null,
        if (c.transparent > 0) "${c.transparent} transparent" else null,
    ).joinToString(" · ")

/** The tree header: `"12 parts · 2 hidden · 5 transparent"`. */
fun visibilityHeaderText(
    parts: Int,
    c: VisibilityCounts,
): String = listOf("$parts parts", visibilityStatusText(c)).filter { it.isNotEmpty() }.joinToString(" · ")
