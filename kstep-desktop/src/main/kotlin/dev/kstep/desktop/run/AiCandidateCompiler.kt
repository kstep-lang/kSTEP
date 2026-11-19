package dev.kstep.desktop.run

import dev.kstep.desktop.ai.AiErrorCodes
import dev.kstep.desktop.ai.extract.Diagnostic
import dev.kstep.desktop.ai.repair.CandidateCompiler
import dev.kstep.desktop.ai.repair.CompileOutcome
import dev.kstep.desktop.ai.repair.NotVerifiedReason
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Checks an AI candidate by running it in the same supervised worker as the live preview (ADR-0056). The candidate is
 * written by THIS code (not by the model) into the private session directory and removed again on every path. [run] is
 * a [WorkerRunner] of its own, so a check never disturbs the live runs. The check only ever runs inside [sandbox] (ADR-0059).
 */
class AiCandidateCompiler(
    private val sessionDir: Path,
    private val sandbox: () -> WorkerSandbox,
    private val run: (file: Path, generation: Long, onStarted: (Process) -> Unit) -> RunResult,
) : CandidateCompiler {
    private val counter = AtomicLong(0)
    private val guard = SessionDirGuard(sessionDir)

    override fun compile(
        script: String,
        onStarted: (Process) -> Unit,
    ): CompileOutcome {
        // Fail closed (ADR-0059): without a sandbox the candidate is neither written nor run.
        val sandboxNow = sandbox()
        if (sandboxNow is WorkerSandbox.Unavailable) {
            return CompileOutcome.Unverifiable(sandboxNow.reason, NotVerifiedReason.SANDBOX_UNAVAILABLE)
        }
        val dir = sessionDir.resolve("ai")
        // The sandboxed candidate may have swapped the session or ai folder for a link (confused deputy, ADR-0059).
        if (!guard.intact(dir)) {
            return CompileOutcome.Refused(AiErrorCodes.AI_SANDBOX_START_FAILED, SessionDirGuard.MESSAGE)
        }
        val posix = "posix" in dir.fileSystem.supportedFileAttributeViews()
        val n = counter.incrementAndGet()
        // The name is not predictable: a candidate cannot plant a file of that name beforehand (ADR-0059).
        val file = dir.resolve("candidate-$n-${UUID.randomUUID()}${FileArgument.SUFFIX}")
        try {
            if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (posix) {
                    Files.createDirectories(
                        dir,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                    )
                } else {
                    Files.createDirectories(dir)
                }
            }
            if (posix) {
                Files.createFile(
                    file,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            }
        } catch (_: IOException) {
            return CompileOutcome.Refused(AiErrorCodes.AI_SANDBOX_START_FAILED, SessionDirGuard.MESSAGE)
        }
        try {
            Files.writeString(
                file,
                script,
                Charsets.UTF_8,
                if (posix) StandardOpenOption.WRITE else StandardOpenOption.CREATE_NEW,
                *(if (posix) arrayOf(StandardOpenOption.TRUNCATE_EXISTING) else emptyArray()),
            )
            return when (val r = run(file, n, onStarted)) {
                is RunResult.Success -> CompileOutcome.Ok
                is RunResult.NativeUnavailable -> CompileOutcome.Unverifiable(r.reason)
                is RunResult.Skipped -> CompileOutcome.Unverifiable(r.hint)
                is RunResult.Failure ->
                    r.errors.firstOrNull()?.takeIf { it.code in SANDBOX_CODES }?.let {
                        CompileOutcome.Refused(it.code, it.message)
                    } ?: CompileOutcome.Errors(
                        r.errors.map { e ->
                            Diagnostic(
                                e.code,
                                e.line,
                                e.column,
                                e.message.replace(file.toString(), "script.kstep.kts"),
                            )
                        },
                    )
            }
        } finally {
            try {
                Files.deleteIfExists(file)
            } catch (_: Exception) {
                // the session directory is removed at the end anyway
            }
        }
    }

    private companion object {
        val SANDBOX_CODES = setOf(AiErrorCodes.AI_SANDBOX_UNAVAILABLE, AiErrorCodes.AI_SANDBOX_START_FAILED)
    }
}
