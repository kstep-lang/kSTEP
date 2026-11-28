package dev.kstep.desktop.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** What an agent asks the user to allow (ADR-0098). */
enum class McpFileAction { SAVE, OPEN_RECENT, EXPORT }

/**
 * What the confirmation dialog shows. All texts are already cleaned and clipped by [McpDisplayText]: they come from an
 * agent or from a file name and are shown as data, never as instructions.
 */
class McpConfirmRequest(
    val id: Long,
    val action: McpFileAction,
    val fileName: String,
    val folder: String,
    val scope: String,
    val createdAtMs: Long,
) {
    override fun toString(): String = "McpConfirmRequest(id=$id, action=$action)"
}

enum class McpConfirmDecision { ALLOW, DENY }

/** How a confirmation ended. */
sealed interface McpConfirmOutcome {
    data object Allowed : McpConfirmOutcome

    data object Denied : McpConfirmOutcome

    data object TimedOut : McpConfirmOutcome

    /** Ended by the application (the permission was switched off, the server stopped): [code] is the tool code. */
    class Cancelled(
        val code: String,
    ) : McpConfirmOutcome

    /** Another confirmation is open. */
    data object Busy : McpConfirmOutcome

    /** The user just denied one: wait [retryAfterMs] before asking again. */
    class CoolingDown(
        val retryAfterMs: Long,
    ) : McpConfirmOutcome
}

/** Runs [task] after [delayMs]; closing the result cancels it. Injectable so that tests need no real time. */
fun interface McpConfirmScheduler {
    fun schedule(
        delayMs: Long,
        task: Runnable,
    ): AutoCloseable
}

/** The production scheduler: one daemon thread; [close] ends it. */
class ExecutorConfirmScheduler :
    McpConfirmScheduler,
    AutoCloseable {
    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "kstep-desktop-mcp-confirm").apply { isDaemon = true }
        }
    }

    override fun schedule(
        delayMs: Long,
        task: Runnable,
    ): AutoCloseable {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/**
 * At most one open confirmation (ADR-0098 D3). [request] blocks the caller (an HTTP worker, never the UI thread) until
 * the user decides, the time is up or the application cancels; it never waits longer than [timeoutMs] plus
 * [BACKSTOP_MS]. The window shows [pending] and calls [resolve]. A denial starts a cool-down so that an agent cannot
 * bombard the user with dialogs. Pure logic: no Compose, no real time except the backstop of the wait.
 */
class McpConfirmationGate(
    private val scheduler: McpConfirmScheduler,
    private val clock: () -> Long,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val denyCooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private class Slot(
        val request: McpConfirmRequest,
        val future: CompletableFuture<McpConfirmOutcome>,
    )

    private val slot = AtomicReference<Slot?>(null)
    private val ids = AtomicLong(0)
    private val pendingFlow = MutableStateFlow<McpConfirmRequest?>(null)

    @Volatile private var coolUntil = Long.MIN_VALUE

    /** The open request the window shows; `null` when there is none. */
    val pending: StateFlow<McpConfirmRequest?> = pendingFlow.asStateFlow()

    fun request(build: (id: Long) -> McpConfirmRequest): McpConfirmOutcome {
        val now = clock()
        if (now < coolUntil) return McpConfirmOutcome.CoolingDown(coolUntil - now)
        val request = build(ids.incrementAndGet())
        val mine = Slot(request, CompletableFuture())
        if (!slot.compareAndSet(null, mine)) return McpConfirmOutcome.Busy
        pendingFlow.value = request
        val timer = scheduler.schedule(timeoutMs) { complete(request.id, McpConfirmOutcome.TimedOut) }
        try {
            return mine.future.get(timeoutMs + BACKSTOP_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            complete(request.id, McpConfirmOutcome.TimedOut)
            return McpConfirmOutcome.TimedOut
        } catch (_: ExecutionException) {
            complete(request.id, McpConfirmOutcome.TimedOut)
            return McpConfirmOutcome.TimedOut
        } catch (e: InterruptedException) {
            complete(request.id, McpConfirmOutcome.Cancelled(McpToolCodes.INTERNAL))
            Thread.currentThread().interrupt()
            return McpConfirmOutcome.Cancelled(McpToolCodes.INTERNAL)
        } finally {
            try {
                timer.close()
            } catch (_: Exception) {
                // nothing to do
            }
        }
    }

    /** The user's decision; a wrong or old [id] is ignored. */
    fun resolve(
        id: Long,
        decision: McpConfirmDecision,
    ) {
        complete(
            id,
            if (decision == McpConfirmDecision.ALLOW) McpConfirmOutcome.Allowed else McpConfirmOutcome.Denied,
        )
    }

    /** Ends the open request (if any) with [code]; the caller answers with that code. */
    fun cancelAll(code: String) {
        val current = slot.get() ?: return
        complete(current.request.id, McpConfirmOutcome.Cancelled(code))
    }

    private fun complete(
        id: Long,
        outcome: McpConfirmOutcome,
    ) {
        val current = slot.get() ?: return
        if (current.request.id != id) return
        if (!slot.compareAndSet(current, null)) return
        if (outcome is McpConfirmOutcome.Denied) coolUntil = clock() + denyCooldownMs
        pendingFlow.value = null
        current.future.complete(outcome)
    }

    /** Ends an open request and stops the timer thread; the application is quitting. */
    fun shutdown() {
        cancelAll(McpToolCodes.FILES_DISABLED)
        (scheduler as? AutoCloseable)?.close()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_COOLDOWN_MS = 10_000L
        const val BACKSTOP_MS = 5_000L
    }
}

/** Makes text from an agent or a file name safe to show in the confirmation dialog. Pure. */
object McpDisplayText {
    /**
     * NFC, control, format, line and paragraph separator characters and loose surrogates replaced by a visible
     * `\u{XXXX}`, cut at [max] code points (a `…` is appended when cut).
     */
    fun forDialog(
        s: String,
        max: Int,
    ): String {
        val cleaned = visible(Normalizer.normalize(s, Normalizer.Form.NFC))
        val points = cleaned.codePointCount(0, cleaned.length)
        if (points <= max) return cleaned
        val end = cleaned.offsetByCodePoints(0, max)
        return cleaned.substring(0, end) + "…"
    }

    /** A folder as `…/rest`, at most [max] code points, cut on the left. */
    fun leftClip(
        s: String,
        max: Int,
    ): String {
        val cleaned = visible(Normalizer.normalize(s, Normalizer.Form.NFC))
        val points = cleaned.codePointCount(0, cleaned.length)
        if (points <= max) return cleaned
        val start = cleaned.offsetByCodePoints(0, points - (max - 1))
        return "…" + cleaned.substring(start)
    }

    private fun visible(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val type = Character.getType(cp)
            val loneSurrogate = n == 1 && Character.isSurrogate(s[i])
            val bad =
                loneSurrogate ||
                    type == Character.CONTROL.toInt() ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    type == Character.UNASSIGNED.toInt() ||
                    type == Character.PRIVATE_USE.toInt()
            if (bad) {
                out.append("\\u{").append(cp.toString(HEX).uppercase().padStart(MIN_HEX, '0')).append('}')
            } else {
                out.appendCodePoint(cp)
            }
            i += n
        }
        return out.toString()
    }

    private const val HEX = 16
    private const val MIN_HEX = 4
}
