package dev.kstep.desktop.mcp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs a block on the UI thread and waits for it with a timeout (ADR-0095). In this wave no tool uses it: the read
 * tools only look at the published snapshot. It is here, tested, for the tools that must ask the UI. A block that did
 * not start within the timeout is cancelled and never runs later. Never call it from the UI thread itself.
 */
class McpUiBridge(
    private val uiDispatcher: CoroutineDispatcher,
    private val isUiThread: () -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val scope = CoroutineScope(SupervisorJob())

    fun <T> onUi(block: () -> T): T {
        check(!isUiThread()) { "McpUiBridge.onUi must not be called on the UI thread" }
        val job = scope.async(uiDispatcher) { block() }
        return try {
            runBlocking { withTimeout(timeoutMs) { job.await() } }
        } catch (_: TimeoutCancellationException) {
            job.cancel()
            throw McpToolException(McpToolCodes.UI_BUSY)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2000L
    }
}
