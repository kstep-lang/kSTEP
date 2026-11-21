package dev.kstep.desktop.ai.diff

import dev.kstep.desktop.ai.repair.AiLimits

sealed interface SelectionBuild {
    data class Built(
        val text: String,
    ) : SelectionBuild

    data object TooLarge : SelectionBuild
}

/** Builds the buffer for a chosen subset of change groups (ADR-0069). Pure; the caller re-checks the result. */
object HunkSelection {
    /**
     * @throws IllegalArgumentException for an unknown id, or when [editorText] / [proposal] are not the plan's texts.
     */
    fun build(
        editorText: String,
        proposal: String,
        plan: HunkPlan.Ready,
        selected: Set<Int>,
        maxBytes: Int = AiLimits().maxScriptBytes,
    ): SelectionBuild {
        require(editorText == plan.old && proposal == plan.new) { "the plan was computed from other texts" }
        val byId = plan.hunks.associateBy { it.id }
        require(selected.all { it in byId }) { "unknown change group" }
        if (isAll(plan, selected)) return SelectionBuild.Built(proposal)
        if (selected.isEmpty()) return SelectionBuild.Built(editorText)
        val out = LineDiff.lines(editorText).toMutableList()
        val size = out.size
        var touchesEnd = false
        for (h in selected.map { byId.getValue(it) }.sortedByDescending { it.oldStart }) {
            if (h.oldEnd == size) touchesEnd = true
            for (k in h.oldEnd - 1 downTo h.oldStart) out.removeAt(k)
            out.addAll(h.oldStart, h.newLines)
        }
        val newline = if (touchesEnd) proposal.endsWith('\n') else editorText.endsWith('\n')
        val text = if (out.isEmpty()) "" else out.joinToString("\n") + if (newline) "\n" else ""
        if (text.toByteArray(Charsets.UTF_8).size > maxBytes) return SelectionBuild.TooLarge
        return SelectionBuild.Built(text)
    }

    fun isAll(
        plan: HunkPlan.Ready,
        selected: Set<Int>,
    ): Boolean = plan.hunks.isNotEmpty() && plan.hunks.all { it.id in selected }
}
