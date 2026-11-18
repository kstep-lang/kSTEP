package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable

/** How one part is drawn (ADR-0055): in full, see-through, or not at all. */
enum class Visibility { Visible, Transparent, Hidden }

/** The state of a tree row: a group whose parts differ is [Mixed]. */
enum class TreeVisibility { Visible, Transparent, Hidden, Mixed }

/** Per-part byte codes; the numbers are the same as in the viewer's `CanvasVisibility`. */
object PartMode {
    const val VISIBLE: Byte = 0
    const val TRANSPARENT: Byte = 1
    const val HIDDEN: Byte = 2
}

/** Stable identity of a part across re-runs: the tree key of its leaf, or a name-plus-occurrence fallback. */
typealias PartKey = String

/** The keys of every part of the current scene (index = part index). */
class VisibilityScene(
    val partKey: Array<PartKey>,
) {
    private val known: Set<PartKey> by lazy { partKey.toHashSet() }

    val size: Int get() = partKey.size

    fun contains(key: PartKey): Boolean = key in known

    companion object {
        /**
         * With a [tree] the key of a part is the key of its leaf (path of names plus occurrence). A part the tree does
         * not cover, and every part without a tree, uses `"n:" + name + NUL + occurrence`. The occurrence is counted
         * here in one pass, never with the O(n) lookup of [PartTable.occurrenceOf] per part.
         */
        fun of(
            table: PartTable,
            tree: AssemblyTree?,
        ): VisibilityScene {
            val seen = HashMap<String, Int>()
            val keys =
                Array(table.parts.size) { i ->
                    val name = table.parts[i].name
                    val occurrence = seen.merge(name, 1, Int::plus)!! - 1
                    val leaf = tree?.leafNodeOfPart(i) ?: -1
                    if (leaf >= 0) tree!!.key(leaf) else "n:$name\u0000$occurrence"
                }
            return VisibilityScene(keys)
        }
    }
}

/**
 * The visibility of the leaves, immutable. [leafStates] only holds parts that are not [Visibility.Visible]. [alphaStep]
 * (1..12) is the opacity of transparent parts in steps of 5 %: an integer, so the label "Opacity 25 %" never drifts.
 */
class VisibilityState(
    val leafStates: Map<PartKey, Visibility> = emptyMap(),
    val alphaStep: Int = DEFAULT_STEP,
) {
    val alpha: Float get() = alphaStep * ALPHA_UNIT

    fun copy(
        leafStates: Map<PartKey, Visibility> = this.leafStates,
        alphaStep: Int = this.alphaStep,
    ): VisibilityState = VisibilityState(leafStates, alphaStep)

    override fun equals(other: Any?): Boolean =
        other is VisibilityState && other.alphaStep == alphaStep && other.leafStates == leafStates

    override fun hashCode(): Int = 31 * leafStates.hashCode() + alphaStep

    companion object {
        const val DEFAULT_STEP = 5
        const val MIN_STEP = 1
        const val MAX_STEP = 12
        const val ALPHA_UNIT = 0.05f
    }
}

data class VisibilityCounts(
    val hidden: Int,
    val transparent: Int,
)
