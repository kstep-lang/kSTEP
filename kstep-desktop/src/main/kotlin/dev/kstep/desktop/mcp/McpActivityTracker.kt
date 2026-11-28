package dev.kstep.desktop.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How a finished agent call ended. [Declined] is the user saying no or not answering: not a fault (ADR-0099). */
enum class McpCallOutcome { Ok, Failed, Declined }

/** The state shown by the MCP indicator in the status bar: idle, a call is running, or the last call failed. */
enum class McpPhase { Ready, Busy, Failed }

/** One call in the short history. [label] is already cleaned and cut; [outcome] is `null` while it runs. */
data class McpCall(
    val id: Long,
    val label: String,
    val startedAtMs: Long,
    val outcome: McpCallOutcome?,
)

/** A snapshot for the UI: [recent] is newest first, [finished] counts all calls that ended since the app started. */
data class McpActivity(
    val phase: McpPhase = McpPhase.Ready,
    val running: Int = 0,
    val recent: List<McpCall> = emptyList(),
    val finished: Long = 0,
)

/**
 * Remembers what agents do with the server, for the status bar indicator (ADR-0099). Only authenticated, well-formed
 * calls are recorded, so rejected traffic can never change anything. It keeps labels only (a tool or method name that
 * this server knows), never arguments, scripts or results. Thread safe: the HTTP workers write, the UI reads.
 */
class McpActivityTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val open = HashSet<Long>()
    private val history = ArrayList<McpCall>()
    private var nextId = 1L
    private var finishedCount = 0L
    private var lastFailed = false
    private val flow = MutableStateFlow(McpActivity())

    val state: StateFlow<McpActivity> get() = flow

    /** Starts a call and returns its id. */
    fun begin(label: String): Long =
        synchronized(lock) {
            val id = nextId++
            open += id
            history.add(0, McpCall(id, sanitize(label), clock(), null))
            while (history.size > MAX_RECENT) history.removeAt(history.size - 1)
            publish()
            id
        }

    /** Ends a call. An unknown or already ended id (for instance after [reset]) changes nothing. */
    fun end(
        id: Long,
        outcome: McpCallOutcome,
    ) {
        synchronized(lock) {
            if (!open.remove(id)) return
            val at = history.indexOfFirst { it.id == id }
            if (at >= 0) history[at] = history[at].copy(outcome = outcome)
            finishedCount++
            when (outcome) {
                McpCallOutcome.Ok -> lastFailed = false
                McpCallOutcome.Failed -> lastFailed = true
                McpCallOutcome.Declined -> Unit
            }
            publish()
        }
    }

    /** Forgets a call that turned out not to be one (nothing was answered): it is not counted. */
    fun discard(id: Long) {
        synchronized(lock) {
            if (!open.remove(id)) return
            history.removeAll { it.id == id }
            publish()
        }
    }

    /** At server stop: nothing runs any more. Late [end] calls of calls cut off by the stop are ignored. */
    fun reset() {
        synchronized(lock) {
            open.clear()
            history.clear()
            lastFailed = false
            publish()
        }
    }

    private fun publish() {
        val phase =
            when {
                open.isNotEmpty() -> McpPhase.Busy
                lastFailed -> McpPhase.Failed
                else -> McpPhase.Ready
            }
        flow.value = McpActivity(phase, open.size, history.toList(), finishedCount)
    }

    companion object {
        const val MAX_RECENT = 5
        const val MAX_LABEL = 48

        /** Control characters become spaces, runs of blanks one space; cut at [MAX_LABEL] characters with an ellipsis. */
        fun sanitize(raw: String): String {
            val cleaned = StringBuilder()
            var lastBlank = true
            raw.codePoints().forEach { cp ->
                val blank = Character.isISOControl(cp) || Character.isWhitespace(cp)
                if (blank) {
                    if (!lastBlank) cleaned.append(' ')
                } else {
                    cleaned.appendCodePoint(cp)
                }
                lastBlank = blank
            }
            val text = cleaned.toString().trim()
            if (text.codePointCount(0, text.length) <= MAX_LABEL) return text
            val end = text.offsetByCodePoints(0, MAX_LABEL - 1)
            return text.substring(0, end) + "…"
        }
    }
}
