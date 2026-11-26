package dev.kstep.desktop.run

/** What the scheduler needs to know about the surroundings (ADR-0087). */
data class AutoPreviewConditions(
    /** A file is open in the preview. */
    val fileOpen: Boolean = false,
    /** The open text is a script, not a knowledge-workspace note. */
    val isScript: Boolean = false,
    /** A crash-draft offer waits for Restore or Discard. */
    val draftOfferOpen: Boolean = false,
    /** A run of the saved file is in progress; an automatic run never replaces it. */
    val fileRunActive: Boolean = false,
)

/** What [AutoPreviewScheduler.tick] asks the caller to do. */
sealed interface AutoPreviewAction {
    /** Preview [text] now (an automatic run). */
    data class RunNow(
        val text: String,
    ) : AutoPreviewAction

    /** The text grew past the limit: report KSTEP-D-046 once, run nothing. */
    data object ShowTooLarge : AutoPreviewAction
}

/** The pure decision whether the driver feeds the scheduler at all (everything is ignored while the setting is off). */
fun shouldSchedulePreview(
    previewUnsaved: Boolean,
    conditions: AutoPreviewConditions,
): Boolean = previewUnsaved && conditions.fileOpen && conditions.isScript

/**
 * Decides when the unsaved editor text is previewed on its own (ADR-0087): [DEBOUNCE_MS] after the last edit, at most one
 * automatic run per [MIN_INTERVAL_MS], never while a run of the saved file is in progress, a draft offer is open, the
 * window is not focused, or the text is not a script. The last edit always runs eventually (trailing).
 *
 * No clock, no thread and no UI: the time is passed in. Not thread-safe; call it from one thread only.
 */
class AutoPreviewScheduler {
    private var enabled = false
    private var focused = false
    private var conditions = AutoPreviewConditions()
    private var text: String? = null
    private var lastEdit = 0L
    private var lastAutoStart: Long? = null

    /** The text the shown picture was made from (the saved file or the last buffer sent); `null` = unknown. */
    private var baseline: String? = null
    private var tooLarge = false
    private var tooLargeReported = false

    fun enabledChanged(
        enabled: Boolean,
        now: Long,
    ) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            text = null
            tooLarge = false
            tooLargeReported = false
            lastAutoStart = null
        } else {
            lastEdit = now
        }
    }

    /** The editor text changed (or is reported again). A change restarts the debounce. */
    fun textChanged(
        text: String,
        now: Long,
    ) {
        if (!enabled) return
        if (text == this.text) return
        this.text = text
        lastEdit = now
        tooLarge = exceedsLimit(text)
        if (!tooLarge) tooLargeReported = false
    }

    /** The window gained or lost the focus. Gaining it with a pending difference debounces again (never runs at once). */
    fun focusChanged(
        focused: Boolean,
        now: Long,
    ) {
        if (this.focused == focused) return
        this.focused = focused
        if (enabled && focused) lastEdit = maxOf(lastEdit, now)
    }

    fun conditionsChanged(
        conditions: AutoPreviewConditions,
        now: Long,
    ) {
        val changed = this.conditions != conditions
        this.conditions = conditions
        // a block that ends (or any change of the surroundings) plans afresh: 800 ms from now, never at once
        if (enabled && changed) lastEdit = maxOf(lastEdit, now)
    }

    /** The text the shown picture comes from changed (a saved-file run started or ended). */
    fun baselineChanged(shownText: String?) {
        baseline = shownText
    }

    /** A manual "Run Unsaved" sent [text]: the picture now shows it. It does not count for the rate limit. */
    fun manualRunStarted(text: String) {
        baseline = text
    }

    /** Another file (or none) is shown: nothing of the old file may leak into the new one. */
    fun fileChanged(now: Long) {
        text = null
        baseline = null
        lastAutoStart = null
        tooLarge = false
        tooLargeReported = false
        lastEdit = now
    }

    /** The time the next action is due, or `null` when nothing is to be done or something blocks. */
    fun nextDueAt(): Long? {
        val current = text
        if (!enabled || current == null || !eligible()) return null
        if (tooLarge) return if (tooLargeReported) null else lastEdit + DEBOUNCE_MS
        if (current == baseline) return null
        return maxOf(lastEdit + DEBOUNCE_MS, lastAutoStart?.let { it + MIN_INTERVAL_MS } ?: Long.MIN_VALUE)
    }

    /** At most one action. A [AutoPreviewAction.RunNow] is booked at once: the text becomes the baseline. */
    fun tick(now: Long): AutoPreviewAction? {
        val due = nextDueAt() ?: return null
        if (now < due) return null
        val current = text ?: return null
        if (tooLarge) {
            tooLargeReported = true
            return AutoPreviewAction.ShowTooLarge
        }
        lastAutoStart = now
        baseline = current
        return AutoPreviewAction.RunNow(current)
    }

    private fun eligible(): Boolean =
        focused &&
            conditions.fileOpen &&
            conditions.isScript &&
            !conditions.draftOfferOpen &&
            !conditions.fileRunActive

    companion object {
        const val DEBOUNCE_MS = 800L
        const val MIN_INTERVAL_MS = 2000L
        const val MAX_BYTES = UnsavedRunPolicy.MAX_BYTES

        /** Whether the UTF-8 size of [text] is over [MAX_BYTES]; cheap prefilters first, no allocation. */
        fun exceedsLimit(text: String): Boolean {
            if (text.length > MAX_BYTES) return true // at least one byte per char
            if (text.length.toLong() * 3 <= MAX_BYTES) return false // at most three bytes per char
            var bytes = 0L
            var i = 0
            while (i < text.length) {
                val c = text[i]
                bytes +=
                    when {
                        c.code < 0x80 -> 1
                        c.code < 0x800 -> 2
                        isSurrogatePairAt(text, i) -> {
                            i++
                            4
                        }
                        else -> 3
                    }
                if (bytes > MAX_BYTES) return true
                i++
            }
            return false
        }
    }
}

private fun isSurrogatePairAt(
    text: String,
    index: Int,
): Boolean = index + 1 < text.length && text[index].isHighSurrogate() && text[index + 1].isLowSurrogate()
