package dev.kstep.desktop.export

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.cli.WorkerTempDir
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.ErrorParser
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.UserScriptSandbox
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/** Lets at most ten progress reports per second through; the final report (written == total) always passes. */
internal class ProgressThrottle(
    private val clock: () -> Long,
) {
    private var last: Long? = null

    fun admit(
        written: Long,
        total: Long,
    ): Boolean {
        val now = clock()
        val previous = last
        val pass = previous == null || now - previous >= MIN_INTERVAL_MS || (total > 0 && written >= total)
        if (pass) last = now
        return pass
    }
}

private const val MIN_INTERVAL_MS = 100L

/**
 * Wires the pure [ExportStateMachine] to the export worker and the atomic writer (ADR-0057). Compose-free. Every event
 * goes through one lock; the pipeline (check target, run worker, validate output, write atomically) runs on a single
 * thread, so at most one export and one worker process exist. A kill never happens under the lock. The script path is
 * captured when the dialog opens ([begin]): a later change of the open script does not affect a running export.
 *
 * Logging is limited to generation, format and outcome kind: never paths, script text or worker output.
 */
class ExportController(
    private val worker: (
        script: Path,
        format: ExportFormat,
        generation: Long,
        onStarted: (Process) -> Unit,
    ) -> ExportWorkerResult,
    private val checker: (Path) -> TargetCheck = ExportTargets::check,
    private val validator: (
        Path,
        ExportFormat,
    ) -> ValidationResult = { f, fmt -> ExportOutputValidator.validate(f, fmt) },
    private val writer: ExportAtomicWriter = ExportAtomicWriter(),
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val sessionDir: Path? = null,
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "kstep-desktop-export").apply { isDaemon = true } },
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow<ExportState>(ExportStateMachine.initial(ExportFormat.DEFAULT, null))
    private val closed = AtomicBoolean(false)
    private var script: Path? = null
    private var current: Pair<Long, Process>? = null

    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    /**
     * The generation of the last export an agent started (ADR-0098), or -1. The window uses it to leave the user's
     * remembered format and folder alone and to show a different notice.
     */
    @Volatile
    var exclusiveGeneration: Long = -1
        private set

    /** Elapsed milliseconds since [startedAtMs] on this controller's clock (the dialog's progress line). */
    fun elapsedSince(startedAtMs: Long): Long = (clock() - startedAtMs).coerceAtLeast(0)

    /** Opens the dialog's session for [scriptPath]; a running export is never interrupted by this. */
    fun begin(
        scriptPath: Path,
        format: ExportFormat,
        directory: Path,
        scriptTargets: List<ScriptExportTarget> = emptyList(),
        preferredScriptTarget: Int? = null,
    ) {
        // a declared name wins over the last used format (ADR-0063); the folder is always the caller's, never the script's
        val suggestion =
            ExportStateMachine.suggestion(scriptPath.fileName.toString(), scriptTargets, format, preferredScriptTarget)
        synchronized(lock) {
            if (mutableState.value is ExportState.Running) return
            script = scriptPath.toAbsolutePath().normalize()
            mutableState.value =
                ExportStateMachine
                    .initial(
                        suggestion.format,
                        null,
                        scriptTargets = scriptTargets,
                        selectedScriptTarget = suggestion.selectedScriptTarget,
                    ).copy(generation = mutableState.value.generation)
        }
        chooseExact(directory.resolve(suggestion.fileName))
    }

    /** The user picked one of the names the script suggested. */
    fun chooseScriptTarget(index: Int) {
        val before = mutableState.value as? ExportState.Configuring ?: return
        val declared = before.scriptTargets.getOrNull(index) ?: return
        dispatch(ExportEvent.ChooseScriptTarget(index))
        val target = (mutableState.value as? ExportState.Configuring)?.target
        val folder = target?.parent ?: return
        chooseExact(folder.resolve(declared.fileName))
    }

    fun chooseFormat(format: ExportFormat) {
        dispatch(ExportEvent.ChooseFormat(format))
        // the target's extension changed: look at the new name (existing file, writable)
        (mutableState.value as? ExportState.Configuring)?.target?.let { chooseTarget(it) }
    }

    fun chooseTarget(path: Path) {
        val current = mutableState.value as? ExportState.Configuring ?: return
        // exactly the declared name stays as it is (a `.stp` declaration is not turned into `.step`)
        val declared = current.selectedScriptTarget?.let { current.scriptTargets.getOrNull(it) }
        if (declared != null && path.fileName?.toString() == declared.fileName && declared.format == current.format) {
            chooseExact(path)
        } else {
            chooseExact(ExportStateMachine.retarget(path, current.format))
        }
    }

    private fun chooseExact(adjusted: Path) {
        when (val check = checker(adjusted)) {
            is TargetCheck.Ok -> dispatch(ExportEvent.ChooseTarget(check.file, check.existing?.bytes))
            is TargetCheck.Invalid ->
                dispatch(
                    ExportEvent.TargetRejected(
                        adjusted,
                        DesktopError(check.code, null, null, ErrorParser.sanitize(check.message)),
                    ),
                )
        }
    }

    /**
     * Starts an export to exactly [target] without the dialog, for an agent (ADR-0098): the file is created, never
     * replaced. Returns the generation of the started run, or `null` when nothing started (a run is in progress, or the
     * target was rejected or exists). The caller makes sure that the dialog is not open.
     */
    fun startExclusive(
        scriptPath: Path,
        format: ExportFormat,
        target: Path,
    ): Long? {
        val folder = target.parent ?: return null
        synchronized(lock) { if (mutableState.value is ExportState.Running) return null }
        begin(scriptPath, format, folder)
        chooseExact(target)
        val configuring = mutableState.value as? ExportState.Configuring ?: return null
        if (configuring.target != target || configuring.overwrite !is Overwrite.None) return null
        val generation = configuring.generation + 1
        // set before the run can end, so that the window never sees the end of this run as a user's export
        exclusiveGeneration = generation
        dispatch(ExportEvent.Start(clock(), exclusive = true))
        return (mutableState.value as? ExportState.Running)?.generation?.takeIf { it == generation }
    }

    fun confirmReplace() = dispatch(ExportEvent.ConfirmReplace)

    fun start() = dispatch(ExportEvent.Start(clock()))

    fun stop() = dispatch(ExportEvent.Stop)

    /** Closing the dialog: only effective while configuring or done (a run is stopped first). */
    fun closeDialog() = dispatch(ExportEvent.Close)

    private fun dispatch(event: ExportEvent) {
        val toKill: Process?
        val starts = ArrayList<ExportEffect.StartPipeline>()
        synchronized(lock) {
            if (closed.get()) return
            val transition = ExportStateMachine.reduce(mutableState.value, event)
            mutableState.value = transition.state
            var cancel = false
            for (effect in transition.effects) {
                when (effect) {
                    is ExportEffect.CancelRun -> cancel = true
                    is ExportEffect.StartPipeline -> starts += effect
                    ExportEffect.CloseDialog -> Unit // the UI observes the state; nothing to do here
                }
            }
            toKill = if (cancel) current?.second else null
            if (cancel) current = null
            val path = script
            if (path != null) starts.forEach { submit(it, path) }
        }
        toKill?.let { killAsync(it) }
    }

    private fun submit(
        effect: ExportEffect.StartPipeline,
        scriptPath: Path,
    ) {
        try {
            executor.submit { runPipeline(effect, scriptPath) }
        } catch (_: RejectedExecutionException) {
            // closed
        }
    }

    private fun isActive(generation: Long): Boolean =
        synchronized(lock) {
            val s = mutableState.value
            !closed.get() && s is ExportState.Running && s.generation == generation
        }

    private fun runPipeline(
        effect: ExportEffect.StartPipeline,
        scriptPath: Path,
    ) {
        val generation = effect.generation
        val outcome =
            try {
                pipeline(effect, scriptPath)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                logger.warn { "export gen=$generation failed unexpectedly (${e::class.simpleName})" }
                failed(DesktopErrorCodes.EXPORT_FAILED, "the export failed (${e::class.simpleName})")
            }
        synchronized(lock) { if (current?.first == generation) current = null }
        logger.info { "export gen=$generation format=${effect.format.workerId} outcome=${outcome::class.simpleName}" }
        dispatch(ExportEvent.Finished(generation, outcome))
    }

    private fun pipeline(
        effect: ExportEffect.StartPipeline,
        scriptPath: Path,
    ): PipelineOutcome {
        val generation = effect.generation
        val ok =
            when (val check = checker(effect.target)) {
                is TargetCheck.Invalid -> return failed(check.code, check.message)
                is TargetCheck.Ok -> check
            }
        ok.existing?.let { if (!effect.replaceConfirmed) return PipelineOutcome.NeedsReplace(it.bytes) }
        if (!isActive(generation)) return PipelineOutcome.Cancelled
        dispatch(ExportEvent.PhaseChanged(generation, ExportPhase.RUNNING_SCRIPT))
        val file =
            when (val result = worker(scriptPath, effect.format, generation) { onStarted(generation, it) }) {
                is ExportWorkerResult.Failure ->
                    return if (isActive(generation)) PipelineOutcome.Failed(result.error) else PipelineOutcome.Cancelled
                is ExportWorkerResult.Produced -> result.file
            }
        try {
            if (!isActive(generation)) return PipelineOutcome.Cancelled
            dispatch(ExportEvent.PhaseChanged(generation, ExportPhase.VALIDATING))
            (validator(file, effect.format) as? ValidationResult.Invalid)?.let {
                return failed(DesktopErrorCodes.EXPORT_OUTPUT_INVALID, it.reason)
            }
            dispatch(ExportEvent.PhaseChanged(generation, ExportPhase.WRITING))
            val throttle = ProgressThrottle(clock)
            val onProgress: (Long, Long) -> Unit = { written, total ->
                if (throttle.admit(written, total)) dispatch(ExportEvent.WriteProgress(generation, written, total))
            }
            val stopped = { !isActive(generation) }
            return when (
                val written =
                    if (effect.exclusive) {
                        writer.writeExclusive(file, ok.file, onProgress, stopped)
                    } else {
                        writer.write(file, ok.file, effect.replaceConfirmed, onProgress, stopped)
                    }
            ) {
                is WriteResult.Written ->
                    PipelineOutcome.Success(ok.file.fileName.toString(), written.bytes, ok.dir)
                is WriteResult.NeedsReplace -> PipelineOutcome.NeedsReplace(written.existingBytes)
                WriteResult.Cancelled -> PipelineOutcome.Cancelled
                is WriteResult.Failed -> failed(written.code, written.message)
            }
        } finally {
            try {
                Files.deleteIfExists(file)
            } catch (_: IOException) {
                logger.warn { "could not remove a temporary export file" }
            }
        }
    }

    private fun failed(
        code: String,
        message: String,
    ) = PipelineOutcome.Failed(DesktopError(code, null, null, ErrorParser.sanitize(message)))

    private fun onStarted(
        generation: Long,
        process: Process,
    ) {
        val outdated =
            synchronized(lock) {
                if (!isActive(generation)) {
                    true
                } else {
                    current = generation to process
                    false
                }
            }
        if (outdated) killAsync(process)
    }

    private fun killAsync(process: Process) {
        Thread({ ImportWorkerSupervisor.kill(process) }, "kstep-desktop-export-kill").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val process = synchronized(lock) { current.also { current = null } }
        process?.second?.let { ImportWorkerSupervisor.kill(it) }
        executor.shutdownNow()
        if (!executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
            logger.warn { "the export executor did not end in time" }
        }
        sessionDir?.let { WorkerTempDir.deleteRecursively(it) }
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val CLOSE_WAIT_SECONDS = 15L

        /** The production wiring: a real [ExportWorkerRunner] in its own session directory (the live run stays untouched). */
        fun production(sandbox: UserScriptSandbox? = null): ExportController {
            val dir = LiveRunController.createSessionDir()
            // A normal close() removes the session directory; this covers SIGTERM/Ctrl-C of the whole application.
            Runtime.getRuntime().addShutdownHook(
                Thread({ WorkerTempDir.deleteRecursively(dir) }, "kstep-desktop-export-cleanup"),
            )
            // With a sandbox (ADR-0062) the runner resolves it at the first export, in the worker thread.
            val runner =
                lazy {
                    ExportWorkerRunner(dir, sandbox = sandbox?.forSession(dir))
                }
            return ExportController(
                worker = {
                    script,
                    format,
                    generation,
                    started,
                    ->
                    runner.value.run(script, format, generation, started)
                },
                sessionDir = dir,
            )
        }
    }
}
