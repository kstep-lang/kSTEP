package dev.kstep.desktop.ai.diff

/** A diff split into change groups the user can apply one by one (ADR-0069). */
sealed interface HunkPlan {
    data class TooLarge(
        val oldLines: Int,
        val newLines: Int,
    ) : HunkPlan

    /**
     * [old] and [new] are the exact texts the plan was computed from; [HunkSelection.build] checks them. Not a data
     * class on purpose: a generated `equals` would compare two texts of up to 1 MiB on every recomposition.
     */
    class Ready(
        val old: String,
        val new: String,
        val hunks: List<PlannedHunk>,
        val added: Int,
        val removed: Int,
    ) : HunkPlan
}

/**
 * One change group. Replacing the old lines `[oldStart, oldEnd)` by [newLines] applies it; [oldStart] is the first
 * changed old line (the insertion position for a pure insertion), [oldEnd] is exclusive, both 0-based. [display] holds
 * the changes with up to three lines of context and no `Collapsed` marker.
 */
data class PlannedHunk(
    val id: Int,
    val oldStart: Int,
    val oldEnd: Int,
    val newLines: List<String>,
    val added: Int,
    val removed: Int,
    val display: Hunk,
)
