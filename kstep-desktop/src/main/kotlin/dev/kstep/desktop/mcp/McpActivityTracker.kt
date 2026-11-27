package dev.kstep.desktop.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Remembers when an agent last used the server, for the quiet light-up of the "MCP" status segment (ADR-0095). Only
 * authenticated, well-formed calls are recorded, so rejected traffic can never make anything light up. Updates are
 * throttled: at most one per [LIT_MS], so a busy agent costs the UI no extra recomposition.
 */
class McpActivityTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val last = MutableStateFlow(NEVER)
    val lastLitAtMs: StateFlow<Long> get() = last

    fun record() {
        val now = clock()
        if (now - last.value >= LIT_MS) last.value = now
    }

    companion object {
        const val LIT_MS = 600L
        private const val NEVER = Long.MIN_VALUE / 2

        fun isLit(
            lastLitAtMs: Long,
            nowMs: Long,
        ): Boolean = nowMs - lastLitAtMs in 0 until LIT_MS
    }
}
