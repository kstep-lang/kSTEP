package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertIssue
import dev.kstep.cli.ConvertOutcome
import dev.kstep.cli.ConvertTarget
import dev.kstep.cli.ConvertedFile
import dev.kstep.cli.DesktopConvert
import dev.kstep.cli.DesktopConvertRequest
import dev.kstep.desktop.run.DesktopErrorCodes
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * The state machine of File > Convert (ADR-0077), Compose-free. One lock guards the state; one daemon thread runs one
 * conversion at a time. Nothing is ever overwritten silently: a run starts without force, and only [replace] from a
 * [ConvertState.Conflict] may force, and only if a fresh check finds exactly the conflicts the user saw.
 *
 * Logging is limited to generation, target kind, outcome kind and counts: never paths, names or file content.
 */
class ConvertController(
    private val runner: (DesktopConvertRequest) -> ConvertOutcome = DesktopConvert::run,
    private val onDone: (ConvertRequest) -> Unit = {},
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "kstep-desktop-convert").apply { isDaemon = true } },
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow<ConvertState>(ConvertState.Idle())
    private var generation = 0L
    private var closed = false

    /** Every conflict the user was shown (the state only carries the first [ConvertState.MAX_SHOWN]). */
    private var seenConflicts: List<String> = emptyList()

    val state: StateFlow<ConvertState> = mutableState.asStateFlow()

    private fun nextGeneration(): Long = ++generation

    /** Opens the dialog with [prefill]; ignored while a conversion runs. */
    fun begin(prefill: ConvertRequest) {
        synchronized(lock) {
            if (closed || mutableState.value is ConvertState.Running) return
            mutableState.value = ConvertState.Editing(prefill, null, nextGeneration())
        }
    }

    /** Changes the request; leaving a conflict this way discards it. */
    fun edit(request: ConvertRequest) {
        synchronized(lock) {
            if (closed) return
            when (mutableState.value) {
                is ConvertState.Editing,
                is ConvertState.Conflict,
                is ConvertState.Done,
                is ConvertState.Failed,
                -> mutableState.value = ConvertState.Editing(request, null, nextGeneration())
                is ConvertState.Idle, is ConvertState.Running -> Unit
            }
        }
    }

    /** From a result or a conflict back to the editable request. */
    fun back() {
        synchronized(lock) {
            if (closed) return
            val request =
                when (val s = mutableState.value) {
                    is ConvertState.Conflict -> s.request
                    is ConvertState.Done -> s.request
                    is ConvertState.Failed -> s.request
                    is ConvertState.Idle, is ConvertState.Editing, is ConvertState.Running -> return
                }
            mutableState.value = ConvertState.Editing(request, null, nextGeneration())
        }
    }

    /** Closes the dialog; ignored while a conversion runs (there is no cancelling). */
    fun closeDialog() {
        synchronized(lock) {
            if (closed || mutableState.value is ConvertState.Running) return
            seenConflicts = emptyList()
            mutableState.value = ConvertState.Idle(nextGeneration())
        }
    }

    /** Starts a conversion without force; only from [ConvertState.Editing] and only with a source. */
    fun start() {
        val gen: Long
        val request: ConvertRequest
        synchronized(lock) {
            val s = mutableState.value as? ConvertState.Editing ?: return
            if (closed || s.request.source == null) return
            request = s.request
            gen = nextGeneration()
            mutableState.value = ConvertState.Running(request, replacing = false, generation = gen)
        }
        submit(gen, request, expected = null)
    }

    /** Replaces the listed targets; only from [ConvertState.Conflict], and a second call finds [Running]. */
    fun replace() {
        val gen: Long
        val request: ConvertRequest
        val expected: List<String>
        synchronized(lock) {
            val s = mutableState.value as? ConvertState.Conflict ?: return
            if (closed) return
            request = s.request
            expected = seenConflicts
            gen = nextGeneration()
            mutableState.value = ConvertState.Running(request, replacing = true, generation = gen)
        }
        submit(gen, request, expected)
    }

    private fun submit(
        gen: Long,
        request: ConvertRequest,
        expected: List<String>?,
    ) {
        try {
            executor.execute { execute(gen, request, expected) }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                if (!closed && applies(gen)) mutableState.value = failedState(gen, request, listOf(unexpected(null)))
            }
        }
    }

    private class Result(
        val outcome: ConvertOutcome,
        val forced: Boolean,
    )

    private fun execute(
        gen: Long,
        request: ConvertRequest,
        expected: List<String>?,
    ) {
        val result =
            try {
                pipeline(request, expected)
            } catch (e: Exception) {
                logger.warn { "convert gen=$gen failed with ${e::class.simpleName}" }
                applyFailure(gen, request, unexpected(e))
                return
            }
        var finished: ConvertRequest? = null
        synchronized(lock) {
            if (closed || !applies(gen)) return
            val outcome = result.outcome
            mutableState.value =
                when {
                    outcome is ConvertOutcome.Success -> {
                        finished = request
                        ConvertState.Done(request, outcome.written, outcome.skipped, outcome.warnings, gen)
                    }
                    outcome is ConvertOutcome.Failure && outcome.isTargetConflict && !result.forced -> {
                        seenConflicts = outcome.conflicts
                        ConvertState.Conflict(
                            request,
                            outcome.conflicts.take(ConvertState.MAX_SHOWN),
                            (outcome.conflicts.size - ConvertState.MAX_SHOWN).coerceAtLeast(0),
                            outcome.conflicts.size,
                            gen,
                        )
                    }
                    outcome is ConvertOutcome.Failure ->
                        failedState(gen, request, outcome.errors, outcome.written, outcome.warnings)
                    else -> failedState(gen, request, listOf(unexpected(null)))
                }
            logger.info { logLine(gen, request, mutableState.value, outcome) }
        }
        finished?.let {
            try {
                onDone(it)
            } catch (_: Exception) {
                // remembering preferences must never break a conversion
            }
        }
    }

    /** A plain run; a replace re-checks first and forces only when the conflict set is exactly the one shown. */
    private fun pipeline(
        request: ConvertRequest,
        expected: List<String>?,
    ): Result {
        val plain = checkNotNull(request.toFacade(force = false))
        val first = runner(plain)
        if (expected == null) return Result(first, forced = false)
        if (first is ConvertOutcome.Failure && first.isTargetConflict && first.conflicts == expected) {
            return Result(runner(checkNotNull(request.toFacade(force = true))), forced = true)
        }
        return Result(first, forced = false)
    }

    private fun applyFailure(
        gen: Long,
        request: ConvertRequest,
        issue: ConvertIssue,
    ) {
        synchronized(lock) {
            if (closed || !applies(gen)) return
            mutableState.value = failedState(gen, request, listOf(issue))
            logger.info {
                "convert gen=$gen target=${kind(
                    request.target,
                )} outcome=Failed written=0 skipped=0 conflicts=0"
            }
        }
    }

    private fun applies(gen: Long): Boolean {
        val s = mutableState.value
        return s is ConvertState.Running && s.generation == gen
    }

    private fun failedState(
        gen: Long,
        request: ConvertRequest,
        errors: List<ConvertIssue>,
        written: List<ConvertedFile> = emptyList(),
        warnings: List<ConvertIssue> = emptyList(),
    ) = ConvertState.Failed(
        request,
        errors.take(ConvertState.MAX_SHOWN),
        (errors.size - ConvertState.MAX_SHOWN).coerceAtLeast(0),
        written,
        warnings,
        gen,
    )

    private fun unexpected(e: Exception?) =
        ConvertIssue(
            code = DesktopErrorCodes.CONVERT_FAILED,
            kind = "convert_failed",
            message = "the conversion failed (${e?.let { it::class.simpleName } ?: "unavailable"})",
            source = null,
        )

    private fun kind(target: ConvertTarget) =
        when (target) {
            ConvertTarget.OKF -> "okf"
            ConvertTarget.KTS -> "kts"
        }

    private fun logLine(
        gen: Long,
        request: ConvertRequest,
        state: ConvertState,
        outcome: ConvertOutcome,
    ): String {
        val name = state::class.simpleName
        val conflicts = (outcome as? ConvertOutcome.Failure)?.conflicts?.size ?: 0
        return "convert gen=$gen target=${kind(request.target)} outcome=$name written=${outcome.written.size} " +
            "skipped=${outcome.skipped.size} conflicts=$conflicts"
    }

    /** Does not wait for a write: each file is written atomically and the thread is a daemon. */
    override fun close() {
        synchronized(lock) { closed = true }
        executor.shutdownNow()
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
