package dev.kstep.desktop.status

import java.util.Locale

/** How much a status segment matters when the line is too narrow (ADR-0094). */
enum class SegmentPriority { ESSENTIAL, HIGH, MEDIUM, LOW }

/** One piece of the run status. [dropRank] orders segments of the same priority: the lower rank goes first. */
data class StatusSegment(
    val text: String,
    val priority: SegmentPriority,
    val dropRank: Int = 0,
    /** Names a segment the UI treats specially (the "MCP" light, ADR-0095); `null` for all others. */
    val id: String? = null,
)

/** [shown] is what fits, [full] is everything (tooltip and accessibility text), [droppedCount] the segments left out. */
data class FittedStatus(
    val shown: String,
    val full: String,
    val droppedCount: Int,
    /** The character range in [shown] of each kept segment that has an id. */
    val ranges: Map<String, IntRange> = emptyMap(),
)

/**
 * The pure rules of the run status (ADR-0094). The line is made of whole segments; when it is too narrow the least
 * important segments are left out, never cut in the middle. The state, "hidden" and "unsaved changes" are essential and
 * never left out. No Compose here.
 */
object StatusSegments {
    /** A conservative width of one character at 12 sp: better one segment too early than a cut one. */
    const val CHAR_DP = 7.0f

    @Suppress("LongParameterList")
    fun build(
        knowledgeLabel: String?,
        runState: String?,
        durationMs: Long?,
        triangles: Int?,
        triangleWarning: String?,
        note: String?,
        transientNotice: String?,
        aiStatus: String?,
        hidden: Int,
        transparent: Int,
        dirty: Boolean,
        mcp: String? = null,
    ): List<StatusSegment> {
        val all =
            listOf(
                StatusSegment(knowledgeLabel.orEmpty(), SegmentPriority.HIGH),
                StatusSegment(runState.orEmpty(), SegmentPriority.ESSENTIAL),
                StatusSegment(durationMs?.let { "$it ms" }.orEmpty(), SegmentPriority.LOW, 0),
                StatusSegment(
                    triangles?.let { String.format(Locale.ROOT, "%,d", it) + " triangles" }.orEmpty(),
                    SegmentPriority.LOW,
                    1,
                ),
                StatusSegment(triangleWarning.orEmpty(), SegmentPriority.MEDIUM),
                StatusSegment(note.orEmpty(), SegmentPriority.MEDIUM),
                StatusSegment(transientNotice.orEmpty(), SegmentPriority.HIGH),
                StatusSegment(aiStatus.orEmpty(), SegmentPriority.HIGH),
                StatusSegment(mcp.orEmpty(), SegmentPriority.MEDIUM, dropRank = 1, id = MCP_SEGMENT_ID),
                StatusSegment(if (hidden > 0) "$hidden hidden" else "", SegmentPriority.ESSENTIAL),
                StatusSegment(if (transparent > 0) "$transparent transparent" else "", SegmentPriority.MEDIUM),
                StatusSegment(if (dirty) "unsaved changes" else "", SegmentPriority.ESSENTIAL),
            )
        return all.filter { it.text.isNotBlank() }
    }

    /**
     * Leaves segments out until the joined text has at most [maxChars] characters: first the low ones (lowest rank first),
     * then the medium, then the high ones; within a class the last one goes first. Essential segments stay. The order of
     * the remaining segments is kept.
     */
    fun fit(
        segments: List<StatusSegment>,
        maxChars: Int,
    ): FittedStatus {
        val full = segments.joinToString(StatusLineText.SEPARATOR) { it.text }
        val order =
            segments.indices
                .filter { segments[it].priority != SegmentPriority.ESSENTIAL }
                .sortedWith(
                    compareByDescending<Int> { segments[it].priority.ordinal }
                        .thenBy { segments[it].dropRank }
                        .thenByDescending { it },
                )
        val kept = BooleanArray(segments.size) { true }
        var shown = full
        var dropped = 0
        for (index in order) {
            if (shown.length <= maxChars) break
            kept[index] = false
            dropped++
            shown = segments.filterIndexed { i, _ -> kept[i] }.joinToString(StatusLineText.SEPARATOR) { it.text }
        }
        return FittedStatus(shown, full, dropped, rangesOf(segments.filterIndexed { i, _ -> kept[i] }))
    }

    private fun rangesOf(shown: List<StatusSegment>): Map<String, IntRange> {
        val ranges = LinkedHashMap<String, IntRange>()
        var at = 0
        shown.forEach { segment ->
            segment.id?.let { ranges[it] = at until at + segment.text.length }
            at += segment.text.length + StatusLineText.SEPARATOR.length
        }
        return ranges
    }

    /** The id of the segment that shows the MCP server (ADR-0095). */
    const val MCP_SEGMENT_ID = "mcp"

    fun maxChars(widthDp: Float): Int = (widthDp / CHAR_DP).toInt()
}
