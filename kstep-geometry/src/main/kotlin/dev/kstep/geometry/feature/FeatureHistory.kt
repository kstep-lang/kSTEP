package dev.kstep.geometry.feature

/** One undoable change to a [FeatureSequence]. */
sealed interface FeatureEdit {
    /** Short description for undo/redo menus. */
    val label: String
}

/** Inserts [feature] at position [at] (null = append); later features move up and their indices follow. */
data class AddFeature(
    val feature: Feature,
    val at: Int? = null,
) : FeatureEdit {
    override val label: String get() = "add ${feature::class.simpleName}"
}

/** Removes the feature at [index]; refused while a later feature still refers to it. */
data class RemoveFeature(
    val index: Int,
) : FeatureEdit {
    override val label: String get() = "remove feature[$index]"
}

/** Replaces the feature at [index] (a parameter change); no index remapping. */
data class ReplaceFeature(
    val index: Int,
    val feature: Feature,
) : FeatureEdit {
    override val label: String get() = "change feature[$index]"
}

/** Moves the feature at [from] to position [to]; all indices follow, every reference must still point backwards. */
data class MoveFeature(
    val from: Int,
    val to: Int,
) : FeatureEdit {
    override val label: String get() = "move feature[$from] to $to"
}

/** Why a [FeatureHistory] operation was refused. */
enum class FeatureHistoryErrorKind {
    NOTHING_TO_UNDO,
    NOTHING_TO_REDO,
    INDEX_OUT_OF_RANGE,

    /** A later feature still refers to the feature to remove. */
    REFERENCED_BY_LATER_FEATURE,

    /** After moving, a feature would refer to a feature at or after its own position. */
    ORDER_VIOLATION,

    /** The edit would produce a structurally invalid sequence; the reason is the [Throwable.cause]. */
    INVALID_SEQUENCE,
    LIMIT,
}

/**
 * A refused [FeatureHistory] operation. [dependents] lists the positions of the features that block a removal.
 * The history is unchanged whenever this is thrown.
 */
class FeatureHistoryException(
    val kind: FeatureHistoryErrorKind,
    message: String,
    val dependents: List<Int> = emptyList(),
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Undo/redo over a [FeatureSequence] (Welle N, ADR-0033). Snapshot model: the undo and redo stacks hold
 * immutable copies of the sequence (defensively copied on entry); [Feature]s are value types and shared between
 * snapshots. No `OcctShape` or native handle is ever held -- shapes appear only when [rebuild] is called, so
 * undoing costs nothing until the result is needed. (An [ExternalRootFeature] must therefore not hold a native
 * handle either.)
 *
 * Rules: a new edit clears the redo stack; past [limit] the oldest undo entry is dropped; an edit that does not
 * change the sequence is not recorded; every state is checked with [FeatureRebuilder.validateStructure] before
 * it enters the history (an empty sequence is allowed). Adding, removing and moving keep the indices of later
 * features consistent. NOT thread-safe: confine an instance to one thread or synchronise externally. The
 * history lives in memory only; there is no serialised feature format.
 */
class FeatureHistory(
    initial: FeatureSequence = FeatureSequence(emptyList()),
    val limit: Int = DEFAULT_LIMIT,
) {
    private class Entry(
        val features: List<Feature>,
        val label: String,
    )

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var state: List<Feature>

    init {
        require(limit in 1..MAX_LIMIT) { "limit must be within 1..$MAX_LIMIT, got $limit" }
        state = snapshot(initial.features)
        checkStructure(state)
    }

    /** The current sequence (an immutable snapshot). */
    val current: FeatureSequence get() = FeatureSequence(state)
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    /** Labels of the edits [undo] would revert, most recent first. */
    fun undoLabels(): List<String> = undoStack.reversed().map { it.label }

    /** Labels of the edits [redo] would re-apply, next first. */
    fun redoLabels(): List<String> = redoStack.reversed().map { it.label }

    /**
     * Applies [edit] and returns the new sequence.
     *
     * @throws FeatureHistoryException if the edit is refused; the history is unchanged.
     */
    fun apply(edit: FeatureEdit): FeatureSequence {
        val next =
            when (edit) {
                is AddFeature -> add(edit)
                is RemoveFeature -> remove(edit)
                is ReplaceFeature -> replaceAt(edit)
                is MoveFeature -> move(edit)
            }
        checkStructure(next)
        if (next == state) return current
        undoStack.addLast(Entry(state, edit.label))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        state = next
        return current
    }

    fun add(
        feature: Feature,
        at: Int? = null,
    ): FeatureSequence = apply(AddFeature(feature, at))

    fun remove(index: Int): FeatureSequence = apply(RemoveFeature(index))

    fun replace(
        index: Int,
        feature: Feature,
    ): FeatureSequence = apply(ReplaceFeature(index, feature))

    fun move(
        from: Int,
        to: Int,
    ): FeatureSequence = apply(MoveFeature(from, to))

    /** Replaces the feature at [index] by `change(feature)`, e.g. `update(2) { (it as HoleFeature).copy(diameter = 10.0) }`. */
    fun update(
        index: Int,
        change: (Feature) -> Feature,
    ): FeatureSequence {
        requireIndex(index, state.size)
        return replace(index, change(state[index]))
    }

    /** Reverts the last edit. @throws FeatureHistoryException with `NOTHING_TO_UNDO`. */
    fun undo(): FeatureSequence {
        val entry =
            undoStack.removeLastOrNull()
                ?: throw FeatureHistoryException(FeatureHistoryErrorKind.NOTHING_TO_UNDO, "nothing to undo")
        redoStack.addLast(Entry(state, entry.label))
        state = entry.features
        return current
    }

    /** Re-applies the last undone edit. @throws FeatureHistoryException with `NOTHING_TO_REDO`. */
    fun redo(): FeatureSequence {
        val entry =
            redoStack.removeLastOrNull()
                ?: throw FeatureHistoryException(FeatureHistoryErrorKind.NOTHING_TO_REDO, "nothing to redo")
        undoStack.addLast(Entry(state, entry.label))
        state = entry.features
        return current
    }

    /** Builds the current sequence; shapes exist only for the lifetime of the returned result. */
    fun rebuild(): FeatureRebuildResult = FeatureRebuilder.rebuild(current)

    private fun add(edit: AddFeature): List<Feature> {
        val at = edit.at ?: state.size
        if (at !in 0..state.size) {
            throw FeatureHistoryException(
                FeatureHistoryErrorKind.INDEX_OUT_OF_RANGE,
                "cannot add at $at: valid positions are 0..${state.size}",
            )
        }
        val shift: (Int) -> Int = { if (it >= at) it + 1 else it }
        val out = ArrayList<Feature>(state.size + 1)
        for (j in state.indices) {
            if (j == at) out.add(edit.feature)
            out.add(if (j < at) state[j] else state[j].remapIndices(shift))
        }
        if (at == state.size) out.add(edit.feature)
        return snapshot(out)
    }

    private fun remove(edit: RemoveFeature): List<Feature> {
        requireIndex(edit.index, state.size)
        val i = edit.index
        val dependents = (i + 1 until state.size).filter { i in state[it].referencedIndices() }
        if (dependents.isNotEmpty()) {
            throw FeatureHistoryException(
                FeatureHistoryErrorKind.REFERENCED_BY_LATER_FEATURE,
                "feature[$i] cannot be removed: feature(s) $dependents still refer to it",
                dependents,
            )
        }
        val out = ArrayList<Feature>(state.size - 1)
        state.forEachIndexed { j, f ->
            if (j < i) {
                out.add(f)
            } else if (j > i) {
                out.add(f.remapIndices { if (it > i) it - 1 else it })
            }
        }
        return snapshot(out)
    }

    private fun replaceAt(edit: ReplaceFeature): List<Feature> {
        requireIndex(edit.index, state.size)
        val out = ArrayList(state)
        out[edit.index] = edit.feature
        return snapshot(out)
    }

    private fun move(edit: MoveFeature): List<Feature> {
        requireIndex(edit.from, state.size)
        requireIndex(edit.to, state.size)
        if (edit.from == edit.to) return state
        // order[newPosition] = oldPosition
        val order = state.indices.toMutableList()
        order.removeAt(edit.from)
        order.add(edit.to, edit.from)
        val newPosition = IntArray(state.size)
        order.forEachIndexed { newPos, oldPos -> newPosition[oldPos] = newPos }
        val out = order.map { old -> state[old].remapIndices { newPosition[it] } }
        out.forEachIndexed { position, feature ->
            val offending = feature.referencedIndices().filter { it >= position }
            // GeneratedEdges may name the base itself, which is still an earlier position.
            if (offending.isNotEmpty()) {
                throw FeatureHistoryException(
                    FeatureHistoryErrorKind.ORDER_VIOLATION,
                    "moving feature[${edit.from}] to ${edit.to} would make feature[$position] refer to " +
                        "feature(s) $offending at or after its own position",
                    listOf(position),
                )
            }
        }
        return snapshot(out)
    }

    private fun requireIndex(
        index: Int,
        size: Int,
    ) {
        if (index !in 0 until size) {
            throw FeatureHistoryException(
                FeatureHistoryErrorKind.INDEX_OUT_OF_RANGE,
                "index $index is out of range 0..${size - 1}",
            )
        }
    }

    private fun checkStructure(features: List<Feature>) {
        if (features.size > FeatureRebuilder.MAX_SEQUENCE_LENGTH) {
            throw FeatureHistoryException(
                FeatureHistoryErrorKind.LIMIT,
                "a sequence may have at most ${FeatureRebuilder.MAX_SEQUENCE_LENGTH} features",
            )
        }
        try {
            FeatureRebuilder.validateStructure(FeatureSequence(features))
        } catch (e: IllegalArgumentException) {
            throw FeatureHistoryException(
                FeatureHistoryErrorKind.INVALID_SEQUENCE,
                "the edit would produce an invalid sequence: ${e.message}",
                cause = e,
            )
        }
    }

    private fun snapshot(features: List<Feature>): List<Feature> =
        java.util.Collections.unmodifiableList(ArrayList(features))

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 10_000
    }
}
