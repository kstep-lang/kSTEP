package dev.kstep.desktop.edit

import dev.kstep.desktop.run.DesktopError

/**
 * Keeps the [ErrorAnchors] of the editor in step with its text (ADR-0085). Fed with the current error list, the text
 * the run saw ([base]: the saved file, or the exact buffer snapshot of an unsaved run, ADR-0086) and the buffer, it needs no hook in any edit path: a new error list (compared by identity)
 * builds fresh anchors against [base] and then replays the difference to the buffer; later calls replay the
 * difference to the previous buffer. Not thread safe; used from the UI thread only.
 */
class ErrorAnchorTracker(
    private val tokenize: (CharSequence) -> List<Token> = KotlinTokenizer::tokenize,
) {
    private var lastErrors: List<DesktopError>? = null
    private var lastText: String? = null
    private var anchors: ErrorAnchors = ErrorAnchors.EMPTY

    fun update(
        errors: List<DesktopError>,
        base: String,
        text: String,
    ): ErrorAnchors {
        if (errors !== lastErrors) {
            lastErrors = errors
            anchors = if (errors.isEmpty()) ErrorAnchors.EMPTY else ErrorAnchors.build(errors, base, tokenize(base))
            lastText = base
        }
        val previous = lastText
        if (previous != null && previous != text) {
            anchors = anchors.applyDiff(previous, text)
        }
        lastText = text
        return anchors
    }
}
