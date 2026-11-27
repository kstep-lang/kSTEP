package dev.kstep.desktop.run

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.cli.WorkerTempDir
import dev.kstep.desktop.watch.DependencyWatch
import dev.kstep.desktop.watch.DependencyWatcher
import dev.kstep.desktop.watch.FileChangeWatcher
import dev.kstep.desktop.watch.WatchSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Wires the pure [RunStateMachine] to a watcher and a runner (ADR-0051). Every event goes through one lock, so the state
 * is always consistent; effects run after the transition. At most one worker process lives at a time: the runs execute on
 * a single-thread executor, a queued run of an outdated generation never starts a process, and a started one is killed
 * as soon as its generation is outdated ([cancelCurrent], and [onStarted] closes the race between start and cancel).
 *
 * Logging is limited to generation, outcome kind and duration -- never paths' contents, script text or worker output.
 */
class LiveRunController(
    private val runner: (file: Path, generation: Long, onStarted: (Process) -> Unit) -> RunResult,
    private val watcherFactory: (Path, (WatchSignal) -> Unit) -> AutoCloseable,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val wallClock: () -> Instant = Instant::now,
    private val sessionDir: Path? = null,
    /** Creates the watcher of the script's input files (ADR-0080); `null` switches the observation off. */
    private val dependencyWatchFactory: ((onChange: () -> Unit) -> DependencyWatch)? = null,
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "kstep-desktop-run").apply { isDaemon = true } },
    /** Runs in [close] before the session folder is removed (the warm worker lives in it, ADR-0081). */
    private val onClose: () -> Unit = {},
    /** Runs the unsaved buffer of a file (ADR-0086); `null` switches "Run Unsaved" off (it ends in KSTEP-D-008). */
    private val bufferRunner: (
        (
            file: Path,
            text: String,
            generation: Long,
            onStarted: (Process) -> Unit,
        ) -> RunResult
    )? =
        null,
    /** `true` when [file] is shown by another runner (a note, ADR-0058): there is only the saved file to preview. */
    private val isRouted: (Path) -> Boolean = { false },
    /** Reads the saved script as the editor would, `null` when that fails (ADR-0091); never throws. */
    private val sourceTextReader: (Path) -> String? = ::readScriptSourceText,
) : AutoCloseable {
    private val machine = RunStateMachine()
    private val lock = Any()
    private val mutableState = MutableStateFlow<DesktopState>(DesktopState.Idle)
    private val mutableOpenCount = MutableStateFlow(0)
    private val closed = AtomicBoolean(false)

    private var latestGeneration = 0L
    private var maxGeneration = 0L // never decreases, so generations stay unique across a clear()
    private var current: Pair<Long, Process>? = null
    private var watcher: AutoCloseable? = null
    private var depWatch: DependencyWatch? = null
    private var depToken = 0L // the token of the installed dependency watcher; 0 = none
    private var depTokenCounter = 0L

    /**
     * Receives every watcher signal before the run starts (the editor, ADR-0052). Called on the watcher thread, outside
     * the lock. There is still exactly one watcher and one run path; this is only a second listener.
     */
    @Volatile
    var onDiskSignal: ((WatchSignal) -> Unit)? = null

    /**
     * Decides whether a `CHANGED` signal starts a run (ADR-0058: a note changed only outside the shown block does not).
     * Runs AFTER [onDiskSignal], so the editor still sees every change. `MISSING` always runs.
     */
    @Volatile
    var runFilter: ((WatchSignal) -> Boolean)? = null

    val state: StateFlow<DesktopState> = mutableState.asStateFlow()

    /** Counts `Open` events; the UI resets the camera when it changes. */
    val openCount: StateFlow<Int> = mutableOpenCount.asStateFlow()

    fun open(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        val old = synchronized(lock) { watcher.also { watcher = null } }
        old?.close()
        closeDependencyWatch()
        dispatch(DesktopEvent.Open(normalized, synchronized(lock) { maxGeneration }))
        val created = watcherFactory(normalized, ::onSignal)
        val createdDeps = createDependencyWatch()
        synchronized(lock) {
            if (closed.get()) {
                created.close()
                createdDeps?.first?.close()
            } else {
                watcher = created
                depWatch = createdDeps?.first
                depToken = createdDeps?.second ?: depToken
            }
        }
    }

    // Each opened file gets its own dependency watcher and a fresh token; a late callback of an older one is ignored.
    private fun createDependencyWatch(): Pair<DependencyWatch, Long>? {
        val factory = dependencyWatchFactory ?: return null
        val token = synchronized(lock) { ++depTokenCounter }
        return factory { onDependencyChange(token) } to token
    }

    private fun closeDependencyWatch() {
        val old =
            synchronized(lock) {
                depWatch.also {
                    depWatch = null
                    depToken = 0L
                }
            }
        old?.close()
    }

    /**
     * A watched input file changed or vanished (ADR-0080). It starts a run and nothing else: the editor must not take
     * it for an external change of the script, the knowledge-workspace filter does not apply, and a vanished input is
     * not "file not found" for the script itself (the run reports the missing input).
     */
    private fun onDependencyChange(token: Long) {
        if (closed.get() || synchronized(lock) { token != depToken }) return
        dispatch(DesktopEvent.Changed)
    }

    // Installs the input set of the newest run's report. A stale run or a run without a report changes nothing.
    private fun replaceDependencies(
        generation: Long,
        file: Path,
        reported: List<Path>,
    ) {
        val (watch, filtered) =
            synchronized(lock) {
                val state = mutableState.value
                if (generation != latestGeneration || closed.get() || state.file != file) return
                val base = file.parent
                val paths =
                    reported
                        .filter { it != file && (base == null || it.startsWith(base) || it.startsWith(realBase(file))) }
                        .take(DependencyWatcher.MAX_PATHS)
                        .toSet()
                depWatch to paths
            }
        watch?.replace(filtered)
    }

    private fun realBase(file: Path): Path =
        try {
            file.parent.toRealPath()
        } catch (_: Exception) {
            file.parent
        }

    fun forceRun() = dispatch(DesktopEvent.ForceRun)

    /** Previews [text], the unsaved buffer of [file] (ADR-0086). The saved file and the input watch stay untouched. */
    fun runBuffer(
        file: Path,
        text: String,
        auto: Boolean = false,
    ) = dispatch(DesktopEvent.RunBuffer(file.toAbsolutePath().normalize(), text, auto))

    /** The automatic preview found the buffer of [file] over 1 MiB (ADR-0087): shows KSTEP-D-046, runs nothing. */
    fun bufferTooLarge(file: Path) = dispatch(DesktopEvent.BufferTooLarge(file.toAbsolutePath().normalize()))

    /** Stops watching and running and returns to [DesktopState.Idle] (the workspace was closed, ADR-0058). */
    fun clear() {
        val old = synchronized(lock) { watcher.also { watcher = null } }
        old?.close()
        closeDependencyWatch()
        dispatch(DesktopEvent.Close)
    }

    private fun onSignal(signal: WatchSignal) {
        try {
            onDiskSignal?.invoke(signal)
        } catch (e: Exception) {
            logger.warn { "the disk-signal listener failed (${e::class.simpleName})" }
        }
        if (signal == WatchSignal.CHANGED) {
            val wanted =
                try {
                    runFilter?.invoke(signal) != false
                } catch (e: Exception) {
                    logger.warn { "the run filter failed (${e::class.simpleName})" }
                    true
                }
            if (wanted) dispatch(DesktopEvent.Changed)
        } else {
            dispatch(DesktopEvent.Missing)
        }
    }

    private fun dispatch(event: DesktopEvent) {
        val toKill: Process?
        val starts = ArrayList<Effect.StartRun>()
        synchronized(lock) {
            if (closed.get()) return
            val transition = machine.reduce(mutableState.value, event, clock(), wallClock())
            mutableState.value = transition.state
            latestGeneration = transition.state.generation
            maxGeneration = maxOf(maxGeneration, latestGeneration)
            var cancel = false
            for (effect in transition.effects) {
                when (effect) {
                    Effect.CancelRun -> cancel = true
                    Effect.ResetCamera -> mutableOpenCount.value = mutableOpenCount.value + 1
                    is Effect.StartRun -> starts += effect
                }
            }
            toKill = if (cancel) current?.second else null
            if (cancel) current = null
            for (start in starts) submit(start)
        }
        // The kill waits for the process to be gone (up to ten seconds): never under the lock.
        toKill?.let { killAsync(it) }
    }

    private fun submit(start: Effect.StartRun) {
        val generation = start.generation
        try {
            executor.submit { runOne(generation, start.file, start.buffer) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // closed
        }
    }

    private fun runOne(
        generation: Long,
        file: Path,
        buffer: String? = null,
    ) {
        if (synchronized(lock) { generation != latestGeneration || closed.get() }) return
        val startedMs = clock()
        var sourceText: String? = buffer
        val rawResult =
            try {
                if (buffer == null) {
                    // The text the line numbers belong to (ADR-0091): the saved file read before and after the run;
                    // only an unchanged file counts. Notes are not scripts.
                    val before = if (isRouted(file)) null else readSource(file)
                    val ran = runner(file, generation) { process -> onStarted(generation, process) }
                    val after = if (before == null) null else readSource(file)
                    sourceText = before?.takeIf { it == after }
                    ran
                } else {
                    runBufferOne(file, buffer, generation)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                RunResult.Failure(
                    listOf(
                        DesktopError(
                            DesktopErrorCodes.WORKER_START_FAILED,
                            null,
                            null,
                            "the run failed (${e::class.simpleName})",
                        ),
                    ),
                )
            }
        val result =
            if (rawResult is RunResult.Success) {
                rawResult.copy(model = rawResult.model.copy(sourceText = sourceText))
            } else {
                rawResult
            }
        synchronized(lock) { if (current?.first == generation) current = null }
        logger.info {
            "run gen=$generation kind=${if (buffer == null) "file" else "buffer"} " +
                "outcome=${result::class.simpleName} durationMs=${clock() - startedMs}" +
                (result.dependencies?.let { " deps=${it.size}" } ?: "")
        }
        // the input watch follows the saved file; a buffer run does not change what is watched (ADR-0086)
        if (buffer == null) result.dependencies?.let { replaceDependencies(generation, file, it) }
        dispatch(DesktopEvent.Finished(generation, result))
    }

    // A reader that fails only costs the part lines of this run, never the run itself (ADR-0091).
    private fun readSource(file: Path): String? =
        try {
            sourceTextReader(file)
        } catch (_: RuntimeException) {
            null
        }

    private fun runBufferOne(
        file: Path,
        text: String,
        generation: Long,
    ): RunResult {
        val run = bufferRunner
        return when {
            isRouted(file) -> RunResult.Skipped("Notes preview only the saved file")
            run == null ->
                RunResult.Failure(
                    listOf(DesktopError(DesktopErrorCodes.PREVIEW_FAILED, null, null, "Run Unsaved is not available")),
                )
            else -> run(file, text, generation) { process -> onStarted(generation, process) }
        }
    }

    private fun onStarted(
        generation: Long,
        process: Process,
    ) {
        val outdated =
            synchronized(lock) {
                if (generation != latestGeneration || closed.get()) {
                    true
                } else {
                    current = generation to process
                    false
                }
            }
        if (outdated) killAsync(process)
    }

    private fun killAsync(process: Process) {
        Thread({ ImportWorkerSupervisor.kill(process) }, "kstep-desktop-kill").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val (w, p) = synchronized(lock) { Pair(watcher.also { watcher = null }, current.also { current = null }) }
        w?.close()
        closeDependencyWatch()
        p?.second?.let { ImportWorkerSupervisor.kill(it) }
        executor.shutdownNow()
        if (!executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
            logger.warn { "the run executor did not end in time" }
        }
        try {
            onClose()
        } catch (e: Exception) {
            logger.warn { "closing the run helpers failed (${e::class.simpleName})" }
        }
        sessionDir?.let { WorkerTempDir.deleteRecursively(it) }
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val CLOSE_WAIT_SECONDS = 15L

        /** A fresh, randomly named session directory (mode 0700 where supported), not a predictable `kstep-desktop-<pid>` (F2). */
        fun createSessionDir(base: Path = Path.of(System.getProperty("java.io.tmpdir"))): Path =
            if ("posix" in base.fileSystem.supportedFileAttributeViews()) {
                Files.createTempDirectory(
                    base,
                    "kstep-desktop-",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------"),
                    ),
                )
            } else {
                Files.createTempDirectory(base, "kstep-desktop-")
            }

        /**
         * The production wiring: a real [WorkerRunner] and a polling [FileChangeWatcher]. [route] may hand a file to
         * another runner (the knowledge workspace runs a block of a note, ADR-0058); `null` means the default runner.
         */
        fun production(
            sandbox: UserScriptSandbox? = null,
            route: ((Path) -> ((Path, Long, (Process) -> Unit) -> RunResult)?)? = null,
        ): LiveRunController {
            val dir = createSessionDir()
            // The warm worker (ADR-0081) of the live preview; the kill switch is a property or an environment variable.
            val warm = WarmWorkerSettings.resolve()
            // A normal close() removes the session directory; this covers SIGTERM/Ctrl-C of the whole application.
            // A warm worker is ended through its own close() and, on SIGTERM, by its stdin end-of-file.
            Runtime.getRuntime().addShutdownHook(
                Thread({ WorkerTempDir.deleteRecursively(dir) }, "kstep-desktop-cleanup"),
            )
            // With a sandbox (ADR-0062) the runner resolves it at the first run, in the runner thread.
            // The text of "Run Unsaved" goes to a folder of its own, never to the session folder (ADR-0086).
            val buffers = UnsavedBufferStore()
            Runtime.getRuntime().addShutdownHook(Thread({ buffers.close() }, "kstep-desktop-unsaved-cleanup"))
            val runner =
                sandbox?.workerRunner(dir, warm, buffers)
                    ?: lazy {
                        WorkerRunner(
                            dir,
                            warm = warm.takeIf { it.enabled }?.let { WarmWorkerClient(dir, it, null) },
                            bufferStore = buffers,
                        )
                    }
            return LiveRunController(
                runner = { file, gen, started ->
                    val routed = route?.invoke(file)
                    if (routed != null) routed(file, gen, started) else runner.value.run(file, gen, started)
                },
                watcherFactory = { file, onEvent -> FileChangeWatcher(file, onEvent = onEvent) },
                sessionDir = dir,
                dependencyWatchFactory = { callback -> DependencyWatcher(onChange = callback) },
                onClose = {
                    if (runner.isInitialized()) runner.value.closeWarm()
                    buffers.close()
                },
                bufferRunner = { file, text, gen, started -> runner.value.runBuffer(file, text, gen, started) },
                isRouted = { file -> route?.invoke(file) != null },
            )
        }
    }
}
