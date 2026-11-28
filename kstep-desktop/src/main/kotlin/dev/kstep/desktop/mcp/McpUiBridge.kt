package dev.kstep.desktop.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Runs a block on the UI thread and waits for it with a timeout (ADR-0095, ADR-0097). [onUi] is for reading: a block
 * that did not start within the timeout is cancelled and never runs later. [onUiMutating] is for blocks that change
 * something: a block that has begun is always waited for to its end and its result is always delivered, so the caller
 * never reports "busy" for a change that happened. Never call either from the UI thread itself.
 */
class McpUiBridge(
    private val uiDispatcher: CoroutineDispatcher,
    private val isUiThread: () -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val startedLimitMs: Long = STARTED_LIMIT_MS,
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

    /**
     * Like [onUi] for a block that changes state. Either the block never runs (`UI_BUSY`) or its result is delivered:
     * after the timeout the caller wins the race to cancel only while the block has not begun; once begun it is
     * waited for up to [startedLimitMs] (then `INTERNAL` and a log entry; the block itself keeps running). An exception of the block becomes `INTERNAL` without any text. The block must
     * check everything first and mutate afterwards, so that it never leaves a half change.
     */
    fun <T> onUiMutating(block: () -> T): T {
        check(!isUiThread()) { "McpUiBridge.onUiMutating must not be called on the UI thread" }
        val phase = AtomicInteger(PENDING)
        val job =
            scope.async(uiDispatcher) {
                if (!phase.compareAndSet(PENDING, STARTED)) return@async null
                Box(block())
            }
        val boxed =
            try {
                runBlocking { withTimeout(timeoutMs) { job.await() } }
            } catch (_: TimeoutCancellationException) {
                if (phase.compareAndSet(PENDING, CANCELLED)) {
                    job.cancel()
                    throw McpToolException(McpToolCodes.UI_BUSY)
                }
                awaitStarted(job)
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "UI block failed" }
                throw McpToolException(McpToolCodes.INTERNAL)
            }
        return (boxed ?: throw McpToolException(McpToolCodes.UI_BUSY)).value
    }

    private fun <T> awaitStarted(job: kotlinx.coroutines.Deferred<Box<T>?>): Box<T>? =
        try {
            runBlocking(NonCancellable) { withTimeout(startedLimitMs) { job.await() } }
        } catch (_: TimeoutCancellationException) {
            // The block has begun and cannot be cancelled; do not keep the HTTP worker for ever (ADR-0097 D4).
            logger.warn {
                "UI block did not finish within $startedLimitMs ms; the call gives up, the block keeps running"
            }
            throw McpToolException(McpToolCodes.INTERNAL)
        } catch (e: McpToolException) {
            throw e
        } catch (_: Exception) {
            throw McpToolException(McpToolCodes.INTERNAL)
        }

    private class Box<T>(
        val value: T,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2000L
        const val STARTED_LIMIT_MS = 60_000L
        private const val PENDING = 0
        private const val STARTED = 1
        private const val CANCELLED = 2
    }
}
