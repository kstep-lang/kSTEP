package dev.kstep.desktop.run

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.cli.WorkerTempDir
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
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "kstep-desktop-run").apply { isDaemon = true } },
) : AutoCloseable {
    private val machine = RunStateMachine()
    private val lock = Any()
    private val mutableState = MutableStateFlow<DesktopState>(DesktopState.Idle)
    private val mutableOpenCount = MutableStateFlow(0)
    private val closed = AtomicBoolean(false)

    private var latestGeneration = 0L
    private var current: Pair<Long, Process>? = null
    private var watcher: AutoCloseable? = null

    /**
     * Receives every watcher signal before the run starts (the editor, ADR-0052). Called on the watcher thread, outside
     * the lock. There is still exactly one watcher and one run path; this is only a second listener.
     */
    @Volatile
    var onDiskSignal: ((WatchSignal) -> Unit)? = null

    val state: StateFlow<DesktopState> = mutableState.asStateFlow()

    /** Counts `Open` events; the UI resets the camera when it changes. */
    val openCount: StateFlow<Int> = mutableOpenCount.asStateFlow()

    fun open(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        val old = synchronized(lock) { watcher.also { watcher = null } }
        old?.close()
        dispatch(DesktopEvent.Open(normalized))
        val created = watcherFactory(normalized, ::onSignal)
        synchronized(lock) {
            if (closed.get()) {
                created.close()
            } else {
                watcher = created
            }
        }
    }

    fun forceRun() = dispatch(DesktopEvent.ForceRun)

    private fun onSignal(signal: WatchSignal) {
        try {
            onDiskSignal?.invoke(signal)
        } catch (e: Exception) {
            logger.warn { "the disk-signal listener failed (${e::class.simpleName})" }
        }
        dispatch(if (signal == WatchSignal.CHANGED) DesktopEvent.Changed else DesktopEvent.Missing)
    }

    private fun dispatch(event: DesktopEvent) {
        val toKill: Process?
        val starts = ArrayList<Effect.StartRun>()
        synchronized(lock) {
            if (closed.get()) return
            val transition = machine.reduce(mutableState.value, event, clock(), wallClock())
            mutableState.value = transition.state
            latestGeneration = transition.state.generation
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
            executor.submit { runOne(generation, start.file) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // closed
        }
    }

    private fun runOne(
        generation: Long,
        file: Path,
    ) {
        if (synchronized(lock) { generation != latestGeneration || closed.get() }) return
        val startedMs = clock()
        val result =
            try {
                runner(file, generation) { process -> onStarted(generation, process) }
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
        synchronized(lock) { if (current?.first == generation) current = null }
        logger.info { "run gen=$generation outcome=${result::class.simpleName} durationMs=${clock() - startedMs}" }
        dispatch(DesktopEvent.Finished(generation, result))
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
        p?.second?.let { ImportWorkerSupervisor.kill(it) }
        executor.shutdownNow()
        if (!executor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
            logger.warn { "the run executor did not end in time" }
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

        /** The production wiring: a real [WorkerRunner] and a polling [FileChangeWatcher]. */
        fun production(): LiveRunController {
            val dir = createSessionDir()
            // A normal close() removes the session directory; this covers SIGTERM/Ctrl-C of the whole application.
            Runtime.getRuntime().addShutdownHook(
                Thread({ WorkerTempDir.deleteRecursively(dir) }, "kstep-desktop-cleanup"),
            )
            val runner = WorkerRunner(dir)
            return LiveRunController(
                runner = { file, gen, started -> runner.run(file, gen, started) },
                watcherFactory = { file, onEvent -> FileChangeWatcher(file, onEvent = onEvent) },
                sessionDir = dir,
            )
        }
    }
}
