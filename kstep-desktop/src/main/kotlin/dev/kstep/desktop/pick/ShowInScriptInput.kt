package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartInfo

/** The decision whether a part's script line may be trusted against the editor buffer (ADR-0091). */
object ShowInScriptInput {
    /**
     * `true` only when the part has a line and the text that was run ([shownSourceText]) is exactly the current buffer
     * [text]. The revision is not used: it also counts caret moves.
     */
    fun positionUsable(
        info: PartInfo?,
        shownSourceText: String?,
        text: String,
    ): Boolean = info?.sourceLine != null && shownSourceText != null && shownSourceText == text
}
