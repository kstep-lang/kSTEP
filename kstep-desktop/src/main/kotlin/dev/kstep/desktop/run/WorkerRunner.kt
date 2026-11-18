package dev.kstep.desktop.run

import dev.kstep.cli.DesktopRenderWorker
import dev.kstep.cli.ImportWorkerLaunch
import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.cli.WorkerOutcome
import dev.kstep.cli.diagnosticLine
import dev.kstep.desktop.model.GlbMeshReader
import dev.kstep.desktop.model.GlbReadException
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.model.TooManyTrianglesException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** Checks the file argument of a run (ADR-0051): a readable, regular `.kstep.kts` file. */
object FileArgument {
    const val SUFFIX: String = ".kstep.kts"

    /** `null` if [path] is acceptable, else a short message for the error bar. */
    fun validate(path: Path): String? =
        when {
            !path.fileName.toString().endsWith(SUFFIX) -> "not a $SUFFIX script"
            !Files.exists(path) -> "file not found"
            !Files.isRegularFile(path) -> "not a regular file"
            !Files.isReadable(path) -> "file is not readable"
            else -> null
        }
}

/** The system property carrying the class path of the worker JVM (set by the Gradle build, see build.gradle.kts). */
const val WORKER_CLASSPATH_PROPERTY: String = "kstep.desktop.workerClasspath"

/**
 * The class path of the worker: the one the build hands over (only kstep-cli's runtime class path, so the script
 * compiler does not see Compose jars), else the desktop's own with a warning (F5).
 */
fun defaultWorkerClasspath(): String =
    System.getProperty(WORKER_CLASSPATH_PROPERTY)?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.class.path").also {
            logger.warn { "worker classpath property not set; using the desktop classpath" }
        }

/**
 * Runs one `kstep render --format glb --output json` in a supervised subprocess ([DesktopRenderWorker]) and turns its end
 * into a [RunResult] (ADR-0051). The script is only ever evaluated in that subprocess, never in the desktop's JVM. The
 * supervisor enforces the wall-clock timeout, the output bounds and kills the whole process tree; the worker has a heap
 * limit and an orphan watchdog. The GLB goes to [sessionDir] (the supervisor's own temp directory is deleted before it
 * returns, F2).
 */
class WorkerRunner(
    private val sessionDir: Path,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxHeap: String = ImportWorkerLaunch.WORKER_MAX_HEAP,
    private val maxGlbBytes: Long = DEFAULT_MAX_GLB_BYTES,
    private val workerClasspath: String = defaultWorkerClasspath(),
    private val extraJvmOptions: List<String> = emptyList(),
    private val clock: () -> Instant = Instant::now,
    private val supervisorFactory: (Long, Path, (Process) -> Unit) -> ImportWorkerSupervisor =
        { timeout, base, started -> ImportWorkerSupervisor(timeout, tempBase = base, onStarted = started) },
) {
    fun run(
        file: Path,
        generation: Long,
        onStarted: (Process) -> Unit,
    ): RunResult {
        FileArgument.validate(file)?.let { return failure(DesktopErrorCodes.FILE_MISSING, it) }
        val out = sessionDir.resolve("run-$generation.glb")
        Files.deleteIfExists(out)
        val startedNanos = System.nanoTime()
        try {
            val outcome =
                supervisorFactory(timeoutMillis, sessionDir, onStarted).runWith { workerTmp ->
                    ImportWorkerLaunch.command(
                        listOf(
                            file.toAbsolutePath().normalize().toString(),
                            "--format",
                            "glb",
                            "--out",
                            out.toString(),
                            "--output",
                            "json",
                        ),
                        timeoutMillis,
                        mainClass = DesktopRenderWorker.MAIN_CLASS,
                        maxHeap = maxHeap,
                        tmpDir = workerTmp,
                        classPath = workerClasspath,
                        extraJvmOptions =
                            listOf("-Djava.awt.headless=true", "-Dapple.awt.UIElement=true") + extraJvmOptions,
                    )
                }
            val durationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
            return judge(outcome, out, generation, durationMs)
        } finally {
            try {
                Files.deleteIfExists(out)
            } catch (_: Exception) {
                logger.warn { "could not remove a temporary preview file" }
            }
        }
    }

    private fun judge(
        outcome: WorkerOutcome,
        out: Path,
        generation: Long,
        durationMs: Long,
    ): RunResult =
        when (outcome) {
            is WorkerOutcome.TimedOut ->
                failure(
                    DesktopErrorCodes.TIMEOUT,
                    "the script ran longer than ${timeoutMillis / MILLIS_PER_SECOND} s and was stopped",
                )
            is WorkerOutcome.OutputOverflow ->
                failure(
                    DesktopErrorCodes.OUTPUT_TOO_LARGE,
                    "the script produced more output than allowed and was stopped",
                )
            is WorkerOutcome.StartFailed ->
                failure(
                    DesktopErrorCodes.WORKER_START_FAILED,
                    "cannot start the render worker" + detail(outcome.reason),
                )
            is WorkerOutcome.IncompleteOutput ->
                failure(
                    DesktopErrorCodes.RESULT_UNREADABLE,
                    "the render worker ended abnormally (incomplete output)" +
                        detail(diagnosticLine(text(outcome.stderr))),
                )
            is WorkerOutcome.Exited -> judgeExited(outcome, out, generation, durationMs)
        }

    private fun judgeExited(
        outcome: WorkerOutcome.Exited,
        out: Path,
        generation: Long,
        durationMs: Long,
    ): RunResult {
        val stdout = text(outcome.stdout, stdout = true)
        val stderr = text(outcome.stderr)
        val code = outcome.exitCode
        if (code == OOM_EXIT_CODE && (stdout + stderr).contains(OOM_MARKER)) {
            return failure(DesktopErrorCodes.HEAP_EXHAUSTED, "the script ran out of memory (heap limit $maxHeap)")
        }
        if (code > SIGNAL_BASE) {
            return failure(DesktopErrorCodes.RESULT_UNREADABLE, "worker terminated (signal ${code - SIGNAL_BASE})")
        }
        if (code != 0 && code != 1) {
            return failure(
                DesktopErrorCodes.RESULT_UNREADABLE,
                "worker exited with code $code" + detail(diagnosticLine(stderr)),
            )
        }
        val lines = stdout.lineSequence().filter { it.isNotBlank() }.toList()
        val last =
            lines.lastOrNull()
                ?: return failure(
                    DesktopErrorCodes.RESULT_UNREADABLE,
                    "the render worker printed no result" + detail(diagnosticLine(stderr)),
                )
        val notes = notes(stderr, lines.size - 1)
        return when (val report = ErrorParser.parseRenderJson(last)) {
            is RenderReport.Failed -> RunResult.Failure(report.errors)
            is RenderReport.Unreadable ->
                failure(
                    DesktopErrorCodes.RESULT_UNREADABLE,
                    report.detail + detail(diagnosticLine(stderr)),
                )
            RenderReport.NoGeometry -> RunResult.Success(model(null, 0, generation, durationMs, notes))
            is RenderReport.Notice ->
                if (!report.occtAvailable || report.reason == OCCT_UNAVAILABLE) {
                    RunResult.NativeUnavailable(ErrorParser.sanitize(report.occtReason ?: report.reason))
                } else {
                    failure(
                        DesktopErrorCodes.PREVIEW_FAILED,
                        "geometry preview failed: ${ErrorParser.sanitize(report.reason)}",
                    )
                }
            is RenderReport.Geometry -> readGlb(out, generation, durationMs, notes, report.partsJson)
        }
    }

    internal fun readGlb(
        out: Path,
        generation: Long,
        durationMs: Long,
        notes: List<String>,
        partsJson: JsonObject?,
    ): RunResult {
        val attrs =
            try {
                Files.readAttributes(out, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) {
                return failure(DesktopErrorCodes.RESULT_UNREADABLE, "the render worker wrote no geometry file")
            }
        if (!attrs.isRegularFile) {
            return failure(
                DesktopErrorCodes.RESULT_UNREADABLE,
                "the geometry file is not a regular file",
            )
        }
        if (attrs.size() > maxGlbBytes) {
            return failure(
                DesktopErrorCodes.OUTPUT_TOO_LARGE,
                "the geometry file is larger than ${maxGlbBytes shr MIB_SHIFT} MiB",
            )
        }
        return try {
            val mesh = GlbMeshReader.read(Files.readAllBytes(out))
            val parsed = if (partsJson == null) PartListResult.Absent else PartListParser.parse(partsJson, mesh)
            val table = (parsed as? PartListResult.Valid)?.table
            val warnings =
                if (parsed is PartListResult.Invalid) {
                    listOf(
                        DesktopError(
                            DesktopErrorCodes.PARTS_INVALID,
                            null,
                            null,
                            ErrorParser.sanitize("Part list invalid: ${parsed.reason} -- selection is off"),
                            Severity.WARNING,
                        ),
                    )
                } else {
                    emptyList()
                }
            RunResult.Success(
                model(mesh, mesh?.mesh?.triangleCount ?: 0, generation, durationMs, notes, table, warnings),
            )
        } catch (e: TooManyTrianglesException) {
            failure(
                DesktopErrorCodes.TOO_MANY_TRIANGLES,
                "the model has too many triangles to preview (limit ${e.limit})",
            )
        } catch (e: GlbReadException) {
            failure(
                DesktopErrorCodes.RESULT_UNREADABLE,
                "the geometry file is invalid: ${ErrorParser.sanitize(e.message.orEmpty())}",
            )
        }
    }

    private fun model(
        mesh: dev.kstep.geometry.ColoredMesh?,
        triangles: Int,
        generation: Long,
        durationMs: Long,
        notes: List<String>,
        parts: PartTable? = null,
        warnings: List<DesktopError> = emptyList(),
    ) = DesktopModel(mesh, triangles, generation, clock(), durationMs, notes, parts, warnings)

    /** Warning lines of the render (`kstep render:` prefix, sanitised, at most [MAX_NOTES]) and the discarded stdout lines. */
    private fun notes(
        stderr: String,
        discardedStdoutLines: Int,
    ): List<String> {
        val warnings =
            stderr
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("kstep render:") }
                .map { ErrorParser.sanitize(it) }
                .take(MAX_NOTES)
                .toList()
        return if (discardedStdoutLines > 0) warnings + "script printed $discardedStdoutLines lines" else warnings
    }

    private fun failure(
        code: String,
        message: String,
    ): RunResult.Failure = RunResult.Failure(listOf(DesktopError(code, null, null, ErrorParser.sanitize(message))))

    private fun detail(text: String?): String = if (text.isNullOrBlank()) "" else " (${ErrorParser.sanitize(text)})"

    private fun text(
        bytes: ByteArray,
        stdout: Boolean = false,
    ): String = String(bytes, if (stdout) ImportWorkerLaunch.stdoutCharset() else ImportWorkerLaunch.stderrCharset())

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000
        const val DEFAULT_MAX_GLB_BYTES: Long = 64L shl 20
        private const val OOM_EXIT_CODE = 3
        private const val OOM_MARKER = "Terminating due to java.lang.OutOfMemoryError"
        private const val SIGNAL_BASE = 128
        private const val OCCT_UNAVAILABLE = "occt_unavailable"
        private const val MAX_NOTES = 5
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MIB_SHIFT = 20
    }
}
