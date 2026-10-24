package dev.kstep.cli

import dev.kstep.shape.importer.StepImportErrorKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** How the worker subprocess ended, before it is judged (ADR-0038). */
internal sealed interface WorkerOutcome {
    /** The worker ended on its own within the timeout; [exitCode] is the raw process exit value. */
    class Exited(
        val pid: Long,
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    ) : WorkerOutcome

    /**
     * The worker ended on its own but a descendant kept an output pipe open, so the output is not known to be complete.
     * [exitCode] is the worker's real exit value, informational only.
     */
    class IncompleteOutput(
        val pid: Long,
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    ) : WorkerOutcome

    /** The worker outlived the timeout and was killed (with all its descendants). */
    class TimedOut(
        val pid: Long,
    ) : WorkerOutcome

    /** The worker wrote more than the supervisor collects on one stream and was killed. */
    class OutputOverflow(
        val pid: Long,
        val limitBytes: Int,
    ) : WorkerOutcome

    /** The worker could not be started at all. */
    class StartFailed(
        val reason: String,
    ) : WorkerOutcome
}

/** What `kstep import` prints for a [WorkerOutcome]. */
internal sealed interface WorkerVerdict {
    /** A regular end: the worker's stdout, stderr and exit code are passed through unchanged. */
    class PassThrough(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    ) : WorkerVerdict

    /** An abnormal end, reported as a structured import error; [detail] is one short diagnostic line for stderr. */
    data class Failure(
        val kind: StepImportErrorKind,
        val message: String,
        val detail: String?,
    ) : WorkerVerdict
}

/**
 * Starts the worker, collects its stdout and stderr up to a bound, and kills it (and its descendants) after the
 * wall-clock timeout (ADR-0038). [start] is injectable so the supervision logic can be tested with a stand-in process;
 * production always uses [ProcessBuilder].
 */
internal class ImportWorkerSupervisor(
    private val timeoutMillis: Long,
    private val maxStdoutBytes: Int = MAX_STDOUT_BYTES,
    private val maxStderrBytes: Int = MAX_STDERR_BYTES,
    private val streamJoinMillis: Long = STREAM_JOIN_MILLIS,
    private val tempBase: Path = Path.of(System.getProperty("java.io.tmpdir")),
    private val start: (List<String>) -> Process = { ProcessBuilder(it).start() },
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    /** Runs a fixed [command]; the worker still gets its own temp directory, which only the command can use. */
    fun run(command: List<String>): WorkerOutcome = runWith { command }

    /**
     * Creates the private temp directory of this run, starts the worker with the command [buildCommand] returns for it,
     * and removes the directory again after the worker (and its descendants) are gone, on every path.
     */
    fun runWith(buildCommand: (workerTmpDir: Path) -> List<String>): WorkerOutcome {
        val dir =
            try {
                WorkerTempDir.create(tempBase.toAbsolutePath())
            } catch (_: IOException) {
                return WorkerOutcome.StartFailed("cannot create worker temp directory")
            } catch (_: SecurityException) {
                return WorkerOutcome.StartFailed("cannot create worker temp directory")
            }
        val process =
            try {
                start(buildCommand(dir))
            } catch (e: IOException) {
                WorkerTempDir.deleteRecursively(dir)
                return WorkerOutcome.StartFailed(e.message.orEmpty())
            } catch (e: SecurityException) {
                WorkerTempDir.deleteRecursively(dir)
                return WorkerOutcome.StartFailed(e.message.orEmpty())
            } catch (e: RuntimeException) {
                WorkerTempDir.deleteRecursively(dir)
                throw e
            }
        val pid = process.pid()
        // The supervisor itself being ended (Ctrl-C, SIGTERM) must not leave the worker or its temp directory behind.
        val hook =
            Thread({
                kill(process)
                WorkerTempDir.deleteRecursively(dir)
            }, "kstep-import-worker-reaper")
        val hookInstalled =
            try {
                Runtime.getRuntime().addShutdownHook(hook)
                true
            } catch (_: IllegalStateException) {
                false
            }
        try {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                // the worker reads nothing; a closed pipe is fine
            }
            val overflow = AtomicBoolean(false)
            val out = Collector(process.inputStream, maxStdoutBytes) { overflowed(process, overflow) }
            val err = Collector(process.errorStream, maxStderrBytes) { overflowed(process, overflow) }
            out.start()
            err.start()
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                kill(process)
                out.join(streamJoinMillis)
                err.join(streamJoinMillis)
                return WorkerOutcome.TimedOut(pid)
            }
            out.join(streamJoinMillis)
            err.join(streamJoinMillis)
            if (overflow.get() || out.overflowed || err.overflowed) {
                kill(process)
                return WorkerOutcome.OutputOverflow(pid, if (out.overflowed) maxStdoutBytes else maxStderrBytes)
            }
            if (out.isAlive || err.isAlive) {
                // A descendant still holds a pipe open: the output is not known to be complete.
                kill(process)
                return WorkerOutcome.IncompleteOutput(pid, process.exitValue(), out.bytes(), err.bytes())
            }
            return WorkerOutcome.Exited(pid, process.exitValue(), out.bytes(), err.bytes())
        } catch (e: InterruptedException) {
            kill(process)
            Thread.currentThread().interrupt()
            return WorkerOutcome.TimedOut(pid)
        } finally {
            // Delete only once the worker is gone, so nothing can still write into (or re-create) the directory.
            if (process.isAlive) kill(process)
            if (!WorkerTempDir.deleteRecursively(dir)) {
                logger.warn { "could not remove the import worker temp directory $dir" }
            }
            if (hookInstalled) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook)
                } catch (_: IllegalStateException) {
                    // already shutting down: the hook runs and kills the (already ended) worker again, harmless
                }
            }
        }
    }

    private fun overflowed(
        process: Process,
        flag: AtomicBoolean,
    ) {
        flag.set(true)
        kill(process)
    }

    /** Reads one stream into memory up to [limit] bytes; beyond it, [onOverflow] runs once and the rest is discarded. */
    private class Collector(
        private val input: InputStream,
        private val limit: Int,
        private val onOverflow: () -> Unit,
    ) : Thread("kstep-import-worker-stream") {
        private val buffer = ByteArrayOutputStream()

        @Volatile
        var overflowed: Boolean = false
            private set

        init {
            isDaemon = true
        }

        override fun run() {
            val chunk = ByteArray(CHUNK_BYTES)
            try {
                input.use { stream ->
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        if (overflowed) continue
                        if (buffer.size() + n > limit) {
                            overflowed = true
                            onOverflow()
                            continue
                        }
                        synchronized(buffer) { buffer.write(chunk, 0, n) }
                    }
                }
            } catch (_: IOException) {
                // the pipe was closed by a kill; whatever was read so far is kept
            }
        }

        fun bytes(): ByteArray = synchronized(buffer) { buffer.toByteArray() }
    }

    companion object {
        /** Bound of the collected worker stdout (the report at the expansion limit is about 7.5 MB, see ADR-0038). */
        const val MAX_STDOUT_BYTES: Int = 16 * 1024 * 1024

        /** Bound of the collected worker stderr (warnings in text mode). */
        const val MAX_STDERR_BYTES: Int = 4 * 1024 * 1024

        /** How long the stream readers are given to finish after the worker ended or was killed. */
        const val STREAM_JOIN_MILLIS: Long = 10_000L

        private const val CHUNK_BYTES = 64 * 1024
        private const val KILL_WAIT_SECONDS = 10L

        /** Kills the worker and its descendants and waits (bounded) until the worker is gone. */
        fun kill(process: Process) {
            try {
                process.descendants().forEach { it.destroyForcibly() }
            } catch (_: UnsupportedOperationException) {
                // no process tree support: only the worker itself is killed
            }
            process.destroyForcibly()
            try {
                process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

private val SIGNAL_NAMES =
    mapOf(4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 11 to "SIGSEGV")

private const val FATAL_ERROR_BANNER = "# A fatal error has been detected"

private const val OOM_EXIT_CODE = 3

private const val OOM_MARKER = "Terminating due to java.lang.OutOfMemoryError"

/**
 * Judges the worker's end (ADR-0038). Only a regular end is passed through: exit 0 or 1 with one complete report on
 * stdout (in JSON mode a single `import` JSON object whose status matches the exit code; in text mode an `Imported` or
 * `Error:` report ending in a newline) and no JVM fatal-error banner. Everything else becomes a structured import
 * error: a timeout, an OutOfMemoryError or an output overflow is `limit_exceeded` (KSTEP-I-010), a crash or any other
 * abnormal end `read_failed` (KSTEP-I-009, the reader could not read the file), a worker that cannot start `io_error`.
 */
internal fun judgeWorkerOutcome(
    jsonOutput: Boolean,
    timeoutMillis: Long,
    outcome: WorkerOutcome,
): WorkerVerdict =
    when (outcome) {
        is WorkerOutcome.TimedOut ->
            WorkerVerdict.Failure(StepImportErrorKind.LIMIT_EXCEEDED, importTimeoutMessage(timeoutMillis), null)
        is WorkerOutcome.OutputOverflow ->
            WorkerVerdict.Failure(
                StepImportErrorKind.LIMIT_EXCEEDED,
                "import report exceeds ${outcome.limitBytes} bytes; the input is too large",
                null,
            )
        is WorkerOutcome.StartFailed ->
            WorkerVerdict.Failure(
                StepImportErrorKind.IO_ERROR,
                "cannot start the import worker process",
                sanitizeDetail(outcome.reason),
            )
        is WorkerOutcome.Exited -> judgeExited(jsonOutput, outcome)
        is WorkerOutcome.IncompleteOutput ->
            WorkerVerdict.Failure(
                StepImportErrorKind.READ_FAILED,
                "the import worker terminated abnormally (incomplete output); the file cannot be read safely",
                diagnosticLine(String(outcome.stderr, ImportWorkerLaunch.stderrCharset())),
            )
    }

private fun judgeExited(
    jsonOutput: Boolean,
    outcome: WorkerOutcome.Exited,
): WorkerVerdict {
    if (isRegularEnd(jsonOutput, outcome.exitCode, outcome.stdout)) {
        return WorkerVerdict.PassThrough(outcome.exitCode, outcome.stdout, outcome.stderr)
    }
    val stderrText = String(outcome.stderr, ImportWorkerLaunch.stderrCharset())
    // -XX:+ExitOnOutOfMemoryError prints "Terminating due to java.lang.OutOfMemoryError: ..." (on stdout) and exits 3.
    val stdoutText = String(outcome.stdout, ImportWorkerLaunch.stdoutCharset())
    if (outcome.exitCode == OOM_EXIT_CODE && (stdoutText + stderrText).contains(OOM_MARKER)) {
        return WorkerVerdict.Failure(
            StepImportErrorKind.LIMIT_EXCEEDED,
            "import ran out of memory (worker heap limit ${ImportWorkerLaunch.WORKER_MAX_HEAP}); the input is too large",
            null,
        )
    }
    val code = outcome.exitCode
    val how =
        when {
            code > 128 -> "signal ${code - 128}" + (SIGNAL_NAMES[code - 128]?.let { " ($it)" } ?: "")
            code == 0 || code == 1 -> "exit code $code without a complete report"
            else -> "exit code $code"
        }
    return WorkerVerdict.Failure(
        StepImportErrorKind.READ_FAILED,
        "the import worker terminated abnormally ($how); the file cannot be read safely",
        diagnosticLine(stderrText),
    )
}

/** `true` for exit 0/1 with one complete, well-formed report on stdout (see [judgeWorkerOutcome]). */
internal fun isRegularEnd(
    jsonOutput: Boolean,
    exitCode: Int,
    stdout: ByteArray,
): Boolean {
    if (exitCode != 0 && exitCode != 1) return false
    val text = String(stdout, ImportWorkerLaunch.stdoutCharset())
    if (!text.endsWith("\n")) return false
    if (!jsonOutput) {
        // A JSON document is checked by parsing it whole; a text report only by its first line and the absence of the
        // JVM's fatal-error banner at the start of a line (the worker suppresses that banner anyway).
        if (text.lineSequence().any { it.startsWith(FATAL_ERROR_BANNER) }) return false
        return text.startsWith("Imported ") || (exitCode == 1 && text.startsWith("Error: "))
    }
    val json =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
    if ((json["command"] as? JsonPrimitive)?.content != "import") return false
    val status = (json["status"] as? JsonPrimitive)?.content
    return if (exitCode == 0) status == "success" else status == "error" || status == "validation_failed"
}

/** Log lines the worker's logging backend may print before the real failure (not the cause of an abnormal end). */
private val LOG_NOISE = Regex("""^\[[^\]]*]\s+(INFO|DEBUG|TRACE)\b""")

/**
 * The one line of the worker's stderr that best explains an abnormal end: the first exception or error line, else the
 * first line that is not INFO/DEBUG/TRACE log output; `null` if there is only such noise (nothing misleading is printed).
 */
internal fun diagnosticLine(stderr: String): String? {
    val candidates =
        stderr
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { LOG_NOISE.containsMatchIn(it) }
            .toList()
    val best =
        candidates.firstOrNull { it.contains("Exception") || it.startsWith("Caused by") || it.contains("Error") }
            ?: candidates.firstOrNull()
    return best?.let { sanitizeDetail(it) }
}

private fun sanitizeDetail(text: String): String? =
    text
        .filter { it.code >= 0x20 && it.code != 0x7f }
        .take(MAX_DETAIL_CHARS)
        .ifBlank { null }

private const val MAX_DETAIL_CHARS = 300

private val logger = KotlinLogging.logger {}

/** The private, per-run `java.io.tmpdir` of one import worker (ADR-0038). */
internal object WorkerTempDir {
    private const val PREFIX = "kstep-import-"

    /** Creates a fresh, randomly named directory below [base], readable only by the current user where possible. */
    fun create(base: Path): Path =
        if ("posix" in base.fileSystem.supportedFileAttributeViews()) {
            Files.createTempDirectory(
                base,
                PREFIX,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        } else {
            Files.createTempDirectory(base, PREFIX)
        }

    /** Deletes [dir] with everything in it, never following a symbolic link; `false` if something could not be removed. */
    fun deleteRecursively(dir: Path): Boolean {
        var clean = true
        try {
            Files.walkFileTree(
                dir,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!tryDelete(file)) clean = false
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        clean = false
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (!tryDelete(dir)) clean = false
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: IOException) {
            clean = false
        } catch (_: SecurityException) {
            clean = false
        }
        return clean
    }

    private fun tryDelete(path: Path): Boolean =
        try {
            Files.deleteIfExists(path)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
}
