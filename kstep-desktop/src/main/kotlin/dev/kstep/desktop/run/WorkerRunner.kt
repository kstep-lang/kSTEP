package dev.kstep.desktop.run

import dev.kstep.cli.DesktopRenderWorker
import dev.kstep.cli.ImportWorkerLaunch
import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.cli.UnsavedBufferInput
import dev.kstep.cli.WarmRenderProtocol
import dev.kstep.cli.WorkerOutcome
import dev.kstep.cli.diagnosticLine
import dev.kstep.desktop.ai.AiErrorCodes
import dev.kstep.desktop.export.ScriptExportTarget
import dev.kstep.desktop.model.GlbMeshReader
import dev.kstep.desktop.model.GlbReadException
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.model.TooManyTrianglesException
import dev.kstep.desktop.pick.AssemblyTree
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
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
    private val worker: WorkerLocationResult = WorkerLocator.current,
    private val extraJvmOptions: List<String> = emptyList(),
    private val clock: () -> Instant = Instant::now,
    private val sandbox: WorkerSandbox? = null,
    /** The code of a sandbox that failed to start: D-030 for the AI check, D-031 for the user flows (ADR-0062). */
    private val sandboxFailureCode: String = AiErrorCodes.AI_SANDBOX_START_FAILED,
    /** `null` keeps the AI texts unchanged. */
    private val sandboxFailureMessage: String? = null,
    /** The warm worker of the live preview (ADR-0081); `null` is the cold path, one fresh process per run. */
    private val warm: WarmWorkerClient? = null,
    /** Where the text of "Run Unsaved" is put for the worker (ADR-0086); a folder of its own, created at first use. */
    private val bufferStore: UnsavedBufferStore = UnsavedBufferStore(),
    private val supervisorFactory: (Long, Path, (Process) -> Unit) -> ImportWorkerSupervisor =
        { timeout, base, started ->
            ImportWorkerSupervisor(
                timeout,
                tempBase = base,
                onStarted = started,
                start =
                    if (sandbox == null) {
                        WorkerEnvironment::start
                    } else {
                        { command -> WorkerEnvironment.startSandboxed(command, sandbox) }
                    },
            )
        },
) {
    private val guard = SessionDirGuard(sessionDir)

    /** `true` if this runner serves runs from a warm worker (ADR-0081); for tests. */
    internal val usesWarmWorker: Boolean get() = warm != null

    /** Ends the warm worker, if any (the session is closing). */
    fun closeWarm() {
        warm?.close()
        bufferStore.close()
    }

    fun run(
        file: Path,
        generation: Long,
        onStarted: (Process) -> Unit,
    ): RunResult = execute(file, generation, onStarted, buffer = null)

    /**
     * Runs [text], the unsaved editor buffer of [file] (ADR-0086), as if it were that file: the worker reads the input
     * files next to [file] ([file] itself need not exist, its folder must). The text goes to a private folder outside the
     * session folder and is removed after the run, whatever its end.
     */
    fun runBuffer(
        file: Path,
        text: String,
        generation: Long,
        onStarted: (Process) -> Unit,
    ): RunResult = execute(file, generation, onStarted, buffer = text)

    private fun execute(
        file: Path,
        generation: Long,
        onStarted: (Process) -> Unit,
        buffer: String?,
    ): RunResult {
        if (worker is WorkerLocationResult.Broken) return failure(worker.code, worker.reason)
        if (sandbox is WorkerSandbox.Unavailable) {
            return failure(
                AiErrorCodes.AI_SANDBOX_UNAVAILABLE,
                "no worker sandbox on this system, the script is not run" + detail(sandbox.reason),
            )
        }
        if (sandbox != null && !guard.intact()) {
            return failure(
                sandboxFailureCode,
                if (sandboxFailureMessage == null) SessionDirGuard.MESSAGE else GUARD_MESSAGE_USER,
            )
        }
        var prepared: PreparedBuffer? = null
        if (buffer == null) {
            FileArgument.validate(file)?.let { return failure(DesktopErrorCodes.FILE_MISSING, it) }
        } else {
            when (val p = prepareBuffer(file, buffer)) {
                is BufferPreparation.Refused -> return p.result
                is BufferPreparation.Ready -> prepared = p.buffer
            }
        }
        val out = sessionDir.resolve("run-$generation.glb")
        Files.deleteIfExists(out)
        val startedNanos = System.nanoTime()
        try {
            val target = prepared?.path ?: file.toAbsolutePath().normalize()
            val outcome =
                warm?.run(
                    target,
                    out,
                    timeoutMillis,
                    onStarted,
                    prepared?.unsaved,
                    bindTo = file.toAbsolutePath().normalize(),
                )
                    ?: runCold(target, out, onStarted, prepared?.unsaved)
            if (warm != null && sandbox != null && !guard.intact()) {
                // The folder of the session was changed while the script ran: the warm process is not trusted any more.
                warm.reset()
                return failure(sandboxFailureCode, GUARD_MESSAGE_USER)
            }
            val durationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
            if (prepared != null) refusalOf(outcome)?.let { return it }
            return judge(outcome, out, generation, durationMs, file)
        } finally {
            try {
                Files.deleteIfExists(out)
            } catch (_: Exception) {
                logger.warn { "could not remove a temporary preview file" }
            }
            prepared?.let { bufferStore.delete(it.path) }
        }
    }

    private class PreparedBuffer(
        val path: Path,
        val unsaved: WarmRenderProtocol.Unsaved,
    )

    private sealed interface BufferPreparation {
        class Ready(
            val buffer: PreparedBuffer,
        ) : BufferPreparation

        class Refused(
            val result: RunResult,
        ) : BufferPreparation
    }

    private fun prepareBuffer(
        file: Path,
        text: String,
    ): BufferPreparation {
        val real = file.toAbsolutePath().normalize()
        val dir = real.parent
        val name = real.fileName?.toString().orEmpty()
        if (dir == null || !Files.isDirectory(dir) || !UnsavedBufferInput.validDisplayName(name)) {
            return BufferPreparation.Refused(
                failure(DesktopErrorCodes.SCRIPT_DIR_MISMATCH, "the folder or the name of the script is not usable"),
            )
        }
        return when (val written = bufferStore.write(text)) {
            UnsavedBufferStore.Written.TooLarge ->
                BufferPreparation.Refused(
                    failure(
                        DesktopErrorCodes.UNSAVED_RUN_TOO_LARGE,
                        "the unsaved text is larger than 1 MiB; save it to preview",
                    ),
                )
            is UnsavedBufferStore.Written.Unsafe ->
                BufferPreparation.Refused(failure(DesktopErrorCodes.SESSION_DIR_UNSAFE, UNSAFE_MESSAGE))
            is UnsavedBufferStore.Written.Failed ->
                BufferPreparation.Refused(
                    failure(
                        DesktopErrorCodes.UNSAVED_RUN_WRITE_FAILED,
                        "the unsaved text could not be written for the run",
                    ),
                )
            is UnsavedBufferStore.Written.Ok ->
                BufferPreparation.Ready(
                    PreparedBuffer(written.path, WarmRenderProtocol.Unsaved(dir.toString(), name, written.sha256Hex)),
                )
        }
    }

    // The worker refused the buffer (ADR-0086): only the reason code travels, mapped to the desktop codes. The warm
    // process has already asked to be recycled.
    private fun refusalOf(outcome: WorkerOutcome): RunResult? {
        // Only the worker's own refusal counts: exit code 2 and a stderr that is exactly one marker line. Anything the
        // script printed itself (also a copy of the marker) must never turn a real result into a security alarm.
        if (outcome !is WorkerOutcome.Exited || outcome.exitCode != REFUSED_EXIT_CODE) return null
        val match = REFUSAL_LINE.matchEntire(text(outcome.stderr)) ?: return null
        return when (match.groupValues[1]) {
            UnsavedBufferInput.Refusal.SCRIPT_DIR.name, UnsavedBufferInput.Refusal.DISPLAY_NAME.name ->
                failure(DesktopErrorCodes.SCRIPT_DIR_MISMATCH, "the folder or the name of the script is not usable")
            else -> failure(DesktopErrorCodes.SESSION_DIR_UNSAFE, UNSAFE_MESSAGE)
        }
    }

    private fun runCold(
        file: Path,
        out: Path,
        onStarted: (Process) -> Unit,
        unsaved: WarmRenderProtocol.Unsaved? = null,
    ): WorkerOutcome {
        val location = (worker as WorkerLocationResult.Found).location
        return supervisorFactory(timeoutMillis, sessionDir, onStarted).runWith { workerTmp ->
            ImportWorkerLaunch.command(
                (
                    unsaved?.let {
                        listOf(
                            "--unsaved-script-dir",
                            it.scriptDir,
                            "--unsaved-display-name",
                            it.displayName,
                            "--unsaved-sha256",
                            it.sha256,
                        )
                    } ?: emptyList()
                ) +
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
                classPath = location.classpath,
                extraJvmOptions =
                    listOf("-Djava.awt.headless=true", "-Dapple.awt.UIElement=true") + extraJvmOptions,
                java = location.java,
            )
        }
    }

    private fun judge(
        outcome: WorkerOutcome,
        out: Path,
        generation: Long,
        durationMs: Long,
        file: Path,
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
                    if (sandbox != null) sandboxFailureCode else DesktopErrorCodes.WORKER_START_FAILED,
                    (
                        if (sandbox != null) {
                            sandboxFailureMessage ?: "cannot start the worker sandbox"
                        } else {
                            "cannot start the render worker"
                        }
                    ) + detail(outcome.reason),
                )
            is WorkerOutcome.IncompleteOutput ->
                failure(
                    DesktopErrorCodes.RESULT_UNREADABLE,
                    "the render worker ended abnormally (incomplete output)" +
                        detail(diagnosticLine(text(outcome.stderr))),
                )
            is WorkerOutcome.Exited -> judgeExited(outcome, out, generation, durationMs, file)
        }

    private fun judgeExited(
        outcome: WorkerOutcome.Exited,
        out: Path,
        generation: Long,
        durationMs: Long,
        file: Path,
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
        if (sandbox != null && code != 0 && code != 1 && sandboxRefusal(stderr)) {
            return failure(
                sandboxFailureCode,
                (sandboxFailureMessage ?: "cannot start the worker sandbox") + detail(diagnosticLine(stderr)),
            )
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
        val result = judgeReport(last, notes, out, generation, durationMs, stderr)
        return attachDependencies(result, last, file)
    }

    // ADR-0080: the worker's list of input files rides on the same last line. A failure to read it never changes the
    // verdict of the run itself.
    private fun attachDependencies(
        result: RunResult,
        lastLine: String,
        file: Path,
    ): RunResult {
        val json =
            try {
                Json.parseToJsonElement(lastLine) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return result // an unreadable result gives no report at all
        // Absent means "no report" (null, the desktop keeps its previous set). Invalid is deliberately an empty list: a
        // hostile or broken report clears the watched set (ADR-0080 D5).
        val (paths, invalid) =
            when (val report = DependencyListParser.parse(json["dependencies"], file)) {
                DependencyReport.Absent -> null to false
                is DependencyReport.Valid -> report.paths to false
                DependencyReport.Invalid -> emptyList<Path>() to true
            }
        if (invalid) logger.warn { "the worker's dependency list was ignored" }
        return when (result) {
            is RunResult.Success ->
                result.copy(
                    model =
                        if (invalid) {
                            result.model.copy(notes = result.model.notes + DependencyListParser.IGNORED_NOTICE)
                        } else {
                            result.model
                        },
                    dependencies = paths,
                )
            is RunResult.Failure ->
                result.copy(
                    errors =
                        if (invalid) {
                            result.errors +
                                DesktopError(
                                    DesktopErrorCodes.DEPENDENCIES_IGNORED,
                                    null,
                                    null,
                                    DependencyListParser.IGNORED_NOTICE,
                                    Severity.WARNING,
                                )
                        } else {
                            result.errors
                        },
                    dependencies = paths,
                )
            is RunResult.NativeUnavailable -> result.copy(dependencies = paths)
            is RunResult.Skipped -> result
        }
    }

    private fun judgeReport(
        last: String,
        notes: List<String>,
        out: Path,
        generation: Long,
        durationMs: Long,
        stderr: String,
    ): RunResult =
        when (val report = ErrorParser.parseRenderJson(last)) {
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
            // a tree is only read with a valid part list; with an invalid one D-012 is shown already
            val treeResult =
                if (partsJson != null && table != null) AssemblyTreeParser.parse(partsJson, table) else null
            val warnings =
                buildList {
                    if (parsed is PartListResult.Invalid) {
                        add(
                            DesktopError(
                                DesktopErrorCodes.PARTS_INVALID,
                                null,
                                null,
                                ErrorParser.sanitize("Part list invalid: ${parsed.reason} -- selection is off"),
                                Severity.WARNING,
                            ),
                        )
                    }
                    if (treeResult is AssemblyTreeResult.Invalid) {
                        add(
                            DesktopError(
                                DesktopErrorCodes.TREE_INVALID,
                                null,
                                null,
                                ErrorParser.sanitize(
                                    "Assembly tree unavailable (${treeResult.reason}); click selection still works",
                                ),
                                Severity.WARNING,
                            ),
                        )
                    }
                }
            val tree = (treeResult as? AssemblyTreeResult.Valid)?.tree
            val declared = ExportTargetsParser.parse(partsJson?.get("exportTargets"))
            val scriptTargets = (declared as? ExportTargetsResult.Valid)?.targets.orEmpty()
            val allNotes =
                if (declared is ExportTargetsResult.Invalid) notes + ExportTargetsParser.IGNORED_NOTICE else notes
            RunResult.Success(
                model(
                    mesh,
                    mesh?.mesh?.triangleCount ?: 0,
                    generation,
                    durationMs,
                    allNotes,
                    table,
                    warnings,
                    tree,
                    scriptTargets,
                ),
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
        tree: AssemblyTree? = null,
        scriptExportTargets: List<ScriptExportTarget> = emptyList(),
    ) = DesktopModel(
        mesh,
        triangles,
        generation,
        clock(),
        durationMs,
        notes,
        parts,
        warnings,
        tree,
        scriptExportTargets,
    )

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
        /** The message of a changed session folder in the user flows (ADR-0062). */
        const val GUARD_MESSAGE_USER: String =
            "the session folder was changed while the script ran; the script was not run"
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000
        private const val REFUSED_EXIT_CODE = 2
        private val REFUSAL_LINE =
            Regex("^" + Regex.escape(UnsavedBufferInput.REFUSED_MARKER) + "([A-Z_]+)\\r?\\n?$")
        const val DEFAULT_MAX_GLB_BYTES: Long = 64L shl 20
        private const val UNSAFE_MESSAGE =
            "the private folder for unsaved runs is not safe (link, owner or permissions changed); the run is stopped"
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

/** A line of the sandbox wrapper itself (`sandbox-exec:` / `bwrap:`), not of the script. */
internal fun sandboxRefusal(stderr: String): Boolean =
    stderr.lineSequence().any { it.trimStart().startsWith("sandbox-exec:") || it.trimStart().startsWith("bwrap:") }
