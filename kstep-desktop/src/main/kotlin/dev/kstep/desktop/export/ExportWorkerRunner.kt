package dev.kstep.desktop.export

import dev.kstep.cli.DesktopExportWorker
import dev.kstep.cli.ImportWorkerLaunch
import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.cli.WorkerOutcome
import dev.kstep.cli.diagnosticLine
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.ErrorParser
import dev.kstep.desktop.run.FileArgument
import dev.kstep.desktop.run.SessionDirGuard
import dev.kstep.desktop.run.WorkerEnvironment
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.run.WorkerSandbox
import dev.kstep.desktop.run.defaultWorkerClasspath
import dev.kstep.desktop.run.sandboxRefusal
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Runs one export in a supervised subprocess ([DesktopExportWorker]) and turns its end into an [ExportWorkerResult]
 * (ADR-0057). Same pattern as the live-run runner: the script is only evaluated in the subprocess, the supervisor
 * enforces the timeout and the output bounds and kills the whole process tree, the worker has a heap limit. The worker
 * writes to [sessionDir] (mode 0700), never to the user's target; the user's path is never passed to it.
 *
 * On [ExportWorkerResult.Produced] the caller owns the output file and removes it; on every failure it is removed here.
 */
class ExportWorkerRunner(
    private val sessionDir: Path,
    private val timeoutMillis: Long = DesktopExportWorker.EXPORT_TIMEOUT_MILLIS,
    private val maxHeap: String = ImportWorkerLaunch.WORKER_MAX_HEAP,
    private val workerClasspath: String = defaultWorkerClasspath(),
    private val extraJvmOptions: List<String> = emptyList(),
    /** `null` runs unsandboxed; a real sandbox is the user-flow sandbox of ADR-0062 (never [WorkerSandbox.Unavailable]). */
    private val sandbox: WorkerSandbox? = null,
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

    fun run(
        script: Path,
        format: ExportFormat,
        generation: Long,
        onStarted: (Process) -> Unit,
    ): ExportWorkerResult {
        if (sandbox is WorkerSandbox.Unavailable) {
            return failure(DesktopErrorCodes.SANDBOX_START_FAILED, SANDBOX_MESSAGE + detail(sandbox.reason))
        }
        if (sandbox != null && !guard.intact()) {
            return failure(DesktopErrorCodes.SANDBOX_START_FAILED, WorkerRunner.GUARD_MESSAGE_USER)
        }
        FileArgument.validate(script)?.let { return failure(DesktopErrorCodes.FILE_MISSING, it) }
        val out = sessionDir.resolve("export-$generation${format.extension}")
        deleteQuietly(out)
        var keep = false
        try {
            val outcome =
                supervisorFactory(timeoutMillis, sessionDir, onStarted).runWith { workerTmp ->
                    ImportWorkerLaunch.command(
                        listOf(
                            script.toAbsolutePath().normalize().toString(),
                            "--format",
                            format.workerId,
                            "--out",
                            out.toString(),
                            "--output",
                            "json",
                        ),
                        timeoutMillis,
                        mainClass = DesktopExportWorker.MAIN_CLASS,
                        maxHeap = maxHeap,
                        tmpDir = workerTmp,
                        classPath = workerClasspath,
                        extraJvmOptions =
                            listOf("-Djava.awt.headless=true", "-Dapple.awt.UIElement=true") + extraJvmOptions,
                    )
                }
            var result = judge(outcome, format, out)
            if (sandbox != null && result is ExportWorkerResult.Produced && !guard.intact()) {
                result = failure(DesktopErrorCodes.SANDBOX_START_FAILED, WorkerRunner.GUARD_MESSAGE_USER)
            }
            keep = result is ExportWorkerResult.Produced
            return result
        } finally {
            if (!keep) deleteQuietly(out)
        }
    }

    internal fun judge(
        outcome: WorkerOutcome,
        format: ExportFormat,
        out: Path,
    ): ExportWorkerResult =
        when (outcome) {
            is WorkerOutcome.TimedOut ->
                failure(
                    DesktopErrorCodes.TIMEOUT,
                    "the export ran longer than ${timeoutMillis / MILLIS_PER_SECOND} s and was stopped",
                )
            is WorkerOutcome.OutputOverflow ->
                failure(
                    DesktopErrorCodes.OUTPUT_TOO_LARGE,
                    "the script produced more output than allowed and was stopped",
                )
            is WorkerOutcome.StartFailed ->
                if (sandbox != null) {
                    failure(DesktopErrorCodes.SANDBOX_START_FAILED, SANDBOX_MESSAGE + detail(outcome.reason))
                } else {
                    failure(
                        DesktopErrorCodes.WORKER_START_FAILED,
                        "cannot start the export worker" + detail(outcome.reason),
                    )
                }
            is WorkerOutcome.IncompleteOutput ->
                failure(
                    DesktopErrorCodes.RESULT_UNREADABLE,
                    "the export worker ended abnormally (incomplete output)" +
                        detail(diagnosticLine(text(outcome.stderr))),
                )
            is WorkerOutcome.Exited -> judgeExited(outcome, format, out)
        }

    private fun judgeExited(
        outcome: WorkerOutcome.Exited,
        format: ExportFormat,
        out: Path,
    ): ExportWorkerResult {
        val stdout = text(outcome.stdout, stdout = true)
        val stderr = text(outcome.stderr)
        val code = outcome.exitCode
        if (code == OOM_EXIT_CODE && (stdout + stderr).contains(OOM_MARKER)) {
            return failure(DesktopErrorCodes.HEAP_EXHAUSTED, "the export ran out of memory (heap limit $maxHeap)")
        }
        if (code > SIGNAL_BASE) {
            return failure(DesktopErrorCodes.RESULT_UNREADABLE, "worker terminated (signal ${code - SIGNAL_BASE})")
        }
        if (sandbox != null && code != 0 && code != 1 && sandboxRefusal(stderr)) {
            return failure(DesktopErrorCodes.SANDBOX_START_FAILED, SANDBOX_MESSAGE + detail(diagnosticLine(stderr)))
        }
        if (code != 0 && code != 1) {
            return failure(
                DesktopErrorCodes.RESULT_UNREADABLE,
                "worker exited with code $code" + detail(diagnosticLine(stderr)),
            )
        }
        val last =
            stdout.lineSequence().filter { it.isNotBlank() }.lastOrNull()
                ?: return failure(
                    DesktopErrorCodes.RESULT_UNREADABLE,
                    "the export worker printed no result" + detail(diagnosticLine(stderr)),
                )
        return when (val report = ExportReportParser.parse(last, format)) {
            ExportReport.Produced -> {
                logger.info { "export worker produced format=${format.workerId}" }
                ExportWorkerResult.Produced(out)
            }
            is ExportReport.Unavailable ->
                failure(
                    DesktopErrorCodes.EXPORT_UNAVAILABLE,
                    "${format.label} export needs the native geometry bridge (OCCT), which is not available" +
                        detail(report.reason),
                )
            is ExportReport.Failed -> ExportWorkerResult.Failure(report.errors.first())
            is ExportReport.Unreadable ->
                failure(DesktopErrorCodes.RESULT_UNREADABLE, report.detail + detail(diagnosticLine(stderr)))
        }
    }

    private fun failure(
        code: String,
        message: String,
    ) = ExportWorkerResult.Failure(DesktopError(code, null, null, ErrorParser.sanitize(message)))

    private fun detail(text: String?): String = if (text.isNullOrBlank()) "" else " (${ErrorParser.sanitize(text)})"

    private fun text(
        bytes: ByteArray,
        stdout: Boolean = false,
    ): String = String(bytes, if (stdout) ImportWorkerLaunch.stdoutCharset() else ImportWorkerLaunch.stderrCharset())

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            logger.warn { "could not remove a temporary export file" }
        }
    }

    private companion object {
        const val SANDBOX_MESSAGE = "Sandbox failed to start; the script was not run."
        const val OOM_EXIT_CODE = 3
        const val OOM_MARKER = "Terminating due to java.lang.OutOfMemoryError"
        const val SIGNAL_BASE = 128
        const val MILLIS_PER_SECOND = 1_000L
    }
}
