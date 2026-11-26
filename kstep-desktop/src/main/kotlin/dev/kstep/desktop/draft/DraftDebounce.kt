package dev.kstep.desktop.draft

/** When the next draft write is due: [debounceMs] after the last change, but at most [maxDelayMs] after the first. */
class DraftDebounce(
    private val debounceMs: Long = DraftPolicy.DEBOUNCE_MS,
    private val maxDelayMs: Long = DraftPolicy.MAX_DELAY_MS,
) {
    private var firstPending: Long? = null
    private var lastChange: Long = 0

    fun onChange(now: Long) {
        if (firstPending == null) firstPending = now
        lastChange = now
    }

    /** The time the write is due, or `null` when nothing is pending. */
    fun dueAt(): Long? = firstPending?.let { minOf(lastChange + debounceMs, it + maxDelayMs) }

    fun isDue(now: Long): Boolean = dueAt()?.let { now >= it } == true

    fun onWritten() = reset()

    fun reset() {
        firstPending = null
    }
}
