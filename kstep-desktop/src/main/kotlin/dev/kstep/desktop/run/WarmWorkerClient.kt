package dev.kstep.desktop.run

import dev.kstep.cli.DesktopWarmRenderWorker
import dev.kstep.cli.ImportWorkerLaunch
import dev.kstep.cli.WarmRenderProtocol
import dev.kstep.cli.WorkerOutcome
import dev.kstep.cli.WorkerTempDir
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Runs renders on a warm worker process and reports each as the [WorkerOutcome] the cold supervisor would have produced,
 * so [WorkerRunner] judges both paths with the same code (ADR-0081). The desktop trusts nothing the worker says: every
 * irregularity (wrong id, broken line, too long a line, a late reply without a request, a timeout, a crash) kills the
 * process, and the rules of the settings (run count, one script per process, idle time) hold whatever the worker's own
 * `recycle` flag says. At most one worker serves and at most one is waiting.
 *
 * Logging names only the mode, the kind of end and a recycle reason, never script text, paths or worker output.
 */
class WarmWorkerClient(
    private val sessionDir: Path,
    private val settings: WarmWorkerSettings,
    private val sandbox: WorkerSandbox?,
    private val worker: WorkerLocationResult = WorkerLocator.current,
    private val maxHeap: String = ImportWorkerLaunch.WORKER_MAX_HEAP,
    private val extraJvmOptions: List<String> = emptyList(),
    private val launchTimeoutMillis: Long = WorkerRunner.DEFAULT_TIMEOUT_MILLIS,
    private val helloTimeoutMillis: Long = HELLO_TIMEOUT_MILLIS,
    reaperPeriodMillis: Long = REAPER_PERIOD_MILLIS,
    /** Test seam: starts the worker command. Production always goes through [WorkerEnvironment]. */
    private val starter: (List<String>) -> Process = { command ->
        if (sandbox == null) WorkerEnvironment.start(command) else WorkerEnvironment.startSandboxed(command, sandbox)
    },
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val runLock = Any()
    private val starterExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "kstep-warm-start").apply { isDaemon = true } }
    private val reaper: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kstep-warm-reaper").apply { isDaemon = true } }

    // guarded by lock
    private var pending: CompletableFuture<WarmWorkerProcess>? = null
    private var openId = 0L
    private var openWorker: WarmWorkerProcess? = null
    private var busy = false
    private var closed = false
    private var counter = 0L

    init {
        reaper.scheduleWithFixedDelay({ reapIdle() }, reaperPeriodMillis, reaperPeriodMillis, TimeUnit.MILLISECONDS)
    }

    /** One render on the warm worker. [onStarted] receives a handle for this request only (never the process itself). */
    fun run(
        script: Path,
        out: Path,
        timeoutMillis: Long,
        onStarted: (Process) -> Unit,
    ): WorkerOutcome =
        synchronized(runLock) {
            lock.withLock {
                if (closed) return WorkerOutcome.StartFailed("the warm worker is closed")
                busy = true
            }
            try {
                runLocked(script, out, timeoutMillis, onStarted)
            } finally {
                lock.withLock { busy = false }
            }
        }

    /** Starts the next worker in the background if none is there (spare mode, after a run). */
    fun prestart() {
        lock.withLock {
            if (closed || pending != null) return
            pending = startAsync()
        }
    }

    /** Ends the current worker (if any); the client stays usable and the next run starts a fresh one. */
    fun reset() {
        val victim = lock.withLock { pending.also { pending = null } }
        victim?.let { killWhenDone(it) }
    }

    /** Waits until a worker is up and has said hello (spare mode after a run); for tests and measurements. */
    internal fun awaitReady(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            val worker = lock.withLock { pending?.getNow(null) }
            if (worker != null) return worker.awaitHello(maxOf(1L, (deadline - System.nanoTime()) / NANOS_PER_MILLI))
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    /** The pid of the current worker, if one is up; for tests. */
    internal fun currentPid(): Long? = lock.withLock { pending?.getNow(null)?.pid }

    override fun close() {
        val victim =
            lock.withLock {
                if (closed) return
                closed = true
                pending.also { pending = null }
            }
        reaper.shutdownNow()
        starterExecutor.shutdownNow()
        victim?.let { killWhenDone(it) }
    }

    // ---- called from WarmRunHandle (any thread) -------------------------------------------------------------

    internal fun isOpen(id: Long): Boolean = lock.withLock { openId == id }

    /** Ends the worker of request [id], but only if that request is still the open one (§ race with the next run). */
    internal fun killFor(id: Long) {
        lock.withLock {
            if (openId == id) openWorker?.killNow()
        }
    }

    internal fun awaitClosed(
        id: Long,
        timeoutNanos: Long,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutNanos
        lock.withLock {
            while (openId == id) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return false
                changed.awaitNanos(left)
            }
            return true
        }
    }

    // ---- the run --------------------------------------------------------------------------------------------

    private fun runLocked(
        script: Path,
        out: Path,
        timeoutMillis: Long,
        onStarted: (Process) -> Unit,
    ): WorkerOutcome {
        var attempt = 0
        while (true) {
            attempt++
            val worker =
                when (val got = acquire(script)) {
                    is Acquired.Failed -> return WorkerOutcome.StartFailed(got.reason)
                    is Acquired.Ok -> got.worker
                }
            val id =
                lock.withLock {
                    counter++
                    openId = counter
                    openWorker = worker
                    counter
                }
            try {
                onStarted(WarmRunHandle(this, id, worker.process))
            } catch (t: Throwable) {
                closeRequest(id)
                retire(worker)
                throw t
            }
            val request = WarmRenderProtocol.Request(id, script.toString(), out.toString())
            val awaited =
                if (worker.send(WarmRenderProtocol.encodeRequest(request))) {
                    await(worker, id, timeoutMillis)
                } else {
                    worker.killNow()
                    Awaited(WorkerOutcome.IncompleteOutput(worker.pid, -1, ByteArray(0), worker.stderrTail()), null)
                }
            closeRequest(id)
            worker.runsServed++
            worker.lastUsedNanos = System.nanoTime()
            if (awaited.stale && attempt < MAX_ATTEMPTS) {
                // The reply to an EARLIER request arrived late: that run was answered by a forged line. This run is
                // innocent, so it is repeated once on a fresh process.
                retire(worker)
                logger.info { "warm worker retired mode=${settings.mode} after=stale" }
                continue
            }
            return finish(worker, script, awaited)
        }
    }

    private fun finish(
        worker: WarmWorkerProcess,
        script: Path,
        awaited: Awaited,
    ): WorkerOutcome {
        val reply = awaited.reply
        val retireNow =
            reply == null ||
                reply.overflow ||
                reply.recycle ||
                settings.mode != WarmWorkerSettings.Mode.REUSE ||
                worker.runsServed >= settings.maxRuns
        if (retireNow) {
            retire(worker)
            // only the kind of reason (before the colon): the detail may hold a name chosen by the script
            val why = reply?.recycleReason?.substringBefore(':') ?: kindOf(awaited.outcome)
            logger.info { "warm worker retired mode=${settings.mode} after=$why" }
            // After a regular reply the next worker is warmed up while the user reads; after any failure the next run
            // starts a fresh one itself.
            if (reply != null && !reply.overflow) prestart()
        } else {
            worker.boundScript = script
        }
        return awaited.outcome
    }

    private fun kindOf(outcome: WorkerOutcome): String = outcome::class.simpleName.orEmpty()

    private fun closeRequest(id: Long) {
        lock.withLock {
            if (openId == id) {
                openId = 0
                openWorker = null
            }
            changed.signalAll()
        }
    }

    private class Awaited(
        val outcome: WorkerOutcome,
        val reply: WarmRenderProtocol.Reply?,
        val stale: Boolean = false,
    )

    private fun await(
        worker: WarmWorkerProcess,
        id: Long,
        timeoutMillis: Long,
    ): Awaited {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val pid = worker.pid
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) {
                worker.killNow()
                return Awaited(WorkerOutcome.TimedOut(pid), null)
            }
            val event = worker.events.poll(left, TimeUnit.NANOSECONDS) ?: continue
            when (event) {
                is WarmWorkerProcess.Event.Line -> {
                    val verdict = judgeLine(worker, event.bytes, id)
                    if (verdict != null) return verdict
                }
                WarmWorkerProcess.Event.TooLong -> {
                    worker.killNow()
                    return Awaited(WorkerOutcome.OutputOverflow(pid, WarmRenderProtocol.MAX_REPLY_LINE_BYTES), null)
                }
                WarmWorkerProcess.Event.StderrOverflow -> {
                    worker.killNow()
                    return Awaited(
                        WorkerOutcome.OutputOverflow(pid, WarmRenderProtocol.MAX_CAPTURED_STDERR_BYTES),
                        null,
                    )
                }
                WarmWorkerProcess.Event.End -> return Awaited(ended(worker), null)
            }
        }
    }

    // null: a foreign line, keep waiting.
    private fun judgeLine(
        worker: WarmWorkerProcess,
        bytes: ByteArray,
        id: Long,
    ): Awaited? {
        val text = String(bytes, Charsets.UTF_8)
        if (!text.trimStart().startsWith("{")) {
            worker.noteForeign(bytes)
            return null
        }
        val reply = WarmRenderProtocol.decodeReply(text, id)
        if (reply == null) {
            val other = WarmRenderProtocol.idOf(text)
            if (other != null && other < id) {
                worker.killNow()
                return Awaited(
                    WorkerOutcome.IncompleteOutput(worker.pid, -1, ByteArray(0), ByteArray(0)),
                    null,
                    stale = true,
                )
            }
            // Looks like a protocol line but is not the reply to this request: nobody may talk to the desktop like this.
            worker.killNow()
            return Awaited(WorkerOutcome.IncompleteOutput(worker.pid, -1, ByteArray(0), ByteArray(0)), null)
        }
        val outcome =
            if (reply.overflow) {
                WorkerOutcome.OutputOverflow(worker.pid, WarmRenderProtocol.MAX_CAPTURED_STDOUT_BYTES)
            } else {
                WorkerOutcome.Exited(worker.pid, reply.exit, reply.stdout, reply.stderr)
            }
        return Awaited(outcome, reply)
    }

    private fun ended(worker: WarmWorkerProcess): WorkerOutcome {
        val finished =
            try {
                worker.process.waitFor(PROCESS_END_WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!finished) worker.killNow()
        val code = if (finished) worker.process.exitValue() else KILLED_EXIT_CODE
        return WorkerOutcome.Exited(worker.pid, code, worker.foreignTail(), worker.stderrTail())
    }

    // ---- acquiring and retiring workers -------------------------------------------------------------------------

    private sealed interface Acquired {
        class Ok(
            val worker: WarmWorkerProcess,
        ) : Acquired

        class Failed(
            val reason: String,
        ) : Acquired
    }

    private fun acquire(script: Path): Acquired {
        repeat(ACQUIRE_ATTEMPTS) {
            val future =
                lock.withLock {
                    if (closed) return Acquired.Failed("the warm worker is closed")
                    pending ?: startAsync().also { pending = it }
                }
            val worker =
                try {
                    future.get(START_WAIT_SECONDS, TimeUnit.SECONDS)
                } catch (e: ExecutionException) {
                    drop(future)
                    return Acquired.Failed(
                        e.cause
                            ?.message
                            .orEmpty()
                            .ifBlank { "cannot start the render worker" },
                    )
                } catch (_: TimeoutException) {
                    drop(future)
                    killWhenDone(future)
                    return Acquired.Failed("warm worker did not start")
                }
            val reusable =
                worker.isAlive() &&
                    (
                        settings.mode != WarmWorkerSettings.Mode.REUSE ||
                            worker.boundScript == null ||
                            worker.boundScript == script
                    )
            if (!reusable) {
                drop(future)
                worker.kill()
                return@repeat
            }
            if (!worker.awaitHello(helloTimeoutMillis)) {
                drop(future)
                worker.kill()
                return Acquired.Failed("warm worker did not start")
            }
            if (worker.events.isNotEmpty()) {
                // Something spoke while no request was open: not a worker to trust.
                drop(future)
                worker.kill()
                return@repeat
            }
            return Acquired.Ok(worker)
        }
        return Acquired.Failed("warm worker not available")
    }

    // A start that is still running when it is no longer wanted must not leave a process behind.
    private fun killWhenDone(future: CompletableFuture<WarmWorkerProcess>) {
        future.whenComplete { worker, _ -> worker?.kill() }
    }

    private fun drop(future: CompletableFuture<WarmWorkerProcess>) {
        lock.withLock { if (pending === future) pending = null }
    }

    private fun retire(worker: WarmWorkerProcess) {
        lock.withLock {
            val current = pending
            if (current != null &&
                current.isDone &&
                !current.isCompletedExceptionally &&
                current.getNow(null) === worker
            ) {
                pending = null
            }
        }
        worker.kill()
    }

    // guarded by lock (called with it held or from the constructor path of prestart/acquire)
    private fun startAsync(): CompletableFuture<WarmWorkerProcess> =
        CompletableFuture.supplyAsync({ startWorker() }, starterExecutor)

    private fun startWorker(): WarmWorkerProcess {
        val location =
            when (worker) {
                is WorkerLocationResult.Found -> worker.location
                is WorkerLocationResult.Broken -> throw IOException(worker.reason)
            }
        val tmp =
            try {
                WorkerTempDir.create(sessionDir.toAbsolutePath())
            } catch (_: IOException) {
                throw IOException("cannot create worker temp directory")
            }
        val command =
            ImportWorkerLaunch.command(
                emptyList(),
                launchTimeoutMillis,
                mainClass = DesktopWarmRenderWorker.MAIN_CLASS,
                maxHeap = maxHeap,
                tmpDir = tmp,
                classPath = location.classpath,
                java = location.java,
                extraJvmOptions =
                    listOf("-Djava.awt.headless=true", "-Dapple.awt.UIElement=true") +
                        extraJvmOptions +
                        listOf(
                            "-D${DesktopWarmRenderWorker.MAX_RUNS_PROPERTY}=${settings.maxRuns}",
                            "-D${DesktopWarmRenderWorker.IDLE_PROPERTY}=${settings.idleMillis}",
                        ),
            )
        val process =
            try {
                starter(command)
            } catch (t: Throwable) {
                WorkerTempDir.deleteRecursively(tmp)
                throw t
            }
        return WarmWorkerProcess(process, tmp)
    }

    private fun reapIdle() {
        val victim =
            lock.withLock {
                val future = pending
                val worker =
                    if (future != null &&
                        future.isDone &&
                        !future.isCompletedExceptionally
                    ) {
                        future.getNow(null)
                    } else {
                        null
                    }
                val idleNanos = TimeUnit.MILLISECONDS.toNanos(settings.idleMillis)
                if (!closed &&
                    !busy &&
                    openId == 0L &&
                    worker != null &&
                    System.nanoTime() - worker.lastUsedNanos > idleNanos
                ) {
                    pending = null
                    worker
                } else {
                    null
                }
            }
        if (victim != null) {
            logger.info { "warm worker retired mode=${settings.mode} after=idle" }
            victim.kill()
        }
    }

    private companion object {
        const val HELLO_TIMEOUT_MILLIS = 30_000L
        const val REAPER_PERIOD_MILLIS = 1_000L
        const val START_WAIT_SECONDS = 60L
        const val PROCESS_END_WAIT_SECONDS = 5L
        const val ACQUIRE_ATTEMPTS = 2
        const val MAX_ATTEMPTS = 2
        const val KILLED_EXIT_CODE = 137
        const val NANOS_PER_MILLI = 1_000_000L
        const val POLL_MILLIS = 50L
    }
}

/**
 * The handle of ONE request on a shared warm process. [LiveRunController] kills the process it is given when a run is
 * outdated; with a shared process that kill could arrive after the next run started on it. This handle only ever ends
 * the process while its own request is still the open one, so a late cancel is a no-op.
 */
internal class WarmRunHandle(
    private val client: WarmWorkerClient,
    private val id: Long,
    private val real: Process,
) : Process() {
    override fun destroy() = client.killFor(id)

    override fun destroyForcibly(): Process {
        client.killFor(id)
        return this
    }

    override fun isAlive(): Boolean = client.isOpen(id)

    override fun pid(): Long = real.pid()

    // The client kills the whole real tree itself (under a sandbox wrapper the JVM is a child of the wrapper).
    override fun descendants(): java.util.stream.Stream<ProcessHandle> =
        java.util.stream.Stream
            .empty()

    override fun waitFor(): Int {
        while (!client.awaitClosed(id, TimeUnit.DAYS.toNanos(1))) {
            // keep waiting
        }
        return 0
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = client.awaitClosed(id, unit.toNanos(timeout))

    override fun exitValue(): Int = if (client.isOpen(id)) throw IllegalThreadStateException("request is open") else 0

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
}
