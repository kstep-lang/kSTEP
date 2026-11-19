package dev.kstep.desktop.okf

import dev.kstep.cli.okf.BlockState
import dev.kstep.cli.okf.Extraction

enum class HlKind { FENCE_LINE, KSTEP_CODE, OTHER_FENCE }

/** [start]..[end] (exclusive) are text offsets; for [HlKind.KSTEP_CODE] the code is tokenised from [offsetBase]. */
data class HlRange(
    val start: Int,
    val end: Int,
    val kind: HlKind,
    val offsetBase: Int,
)

/** The highlight ranges of a note, computed from an [Extraction] (pure, so it is testable without Compose). */
object KnowledgeHighlightPlan {
    fun of(ex: Extraction): List<HlRange> {
        val result = ArrayList<HlRange>()
        for (o in ex.others) result += HlRange(o.start, o.end, HlKind.OTHER_FENCE, o.start)
        for (b in ex.blocks) {
            result += HlRange(b.openLineRange.first, b.openLineRange.last + 1, HlKind.FENCE_LINE, b.openLineRange.first)
            b.closeLineRange?.let { result += HlRange(it.first, it.last + 1, HlKind.FENCE_LINE, it.first) }
            if (b.state != BlockState.TOO_LARGE && b.contentEnd > b.contentStart) {
                result += HlRange(b.contentStart, b.contentEnd, HlKind.KSTEP_CODE, b.contentStart)
            }
        }
        return result
    }
}
