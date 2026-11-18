package dev.kstep.desktop.run

import dev.kstep.desktop.ai.extract.Diagnostic
import dev.kstep.desktop.ai.repair.CandidateCompiler
import dev.kstep.desktop.ai.repair.CompileOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicLong

/**
 * Checks an AI candidate by running it in the same supervised worker as the live preview (ADR-0056). The candidate is
 * written by THIS code (not by the model) into the private session directory and removed again on every path. [run] is
 * a [WorkerRunner] of its own, so a check never disturbs the live runs.
 */
class AiCandidateCompiler(
    private val sessionDir: Path,
    private val run: (file: Path, generation: Long, onStarted: (Process) -> Unit) -> RunResult,
) : CandidateCompiler {
    private val counter = AtomicLong(0)

    override fun compile(
        script: String,
        onStarted: (Process) -> Unit,
    ): CompileOutcome {
        val dir = sessionDir.resolve("ai")
        val posix = "posix" in dir.fileSystem.supportedFileAttributeViews()
        if (!Files.isDirectory(dir)) {
            if (posix) {
                Files.createDirectories(
                    dir,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
            } else {
                Files.createDirectories(dir)
            }
        }
        val n = counter.incrementAndGet()
        val file = dir.resolve("candidate-$n${FileArgument.SUFFIX}")
        if (posix) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
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
                is RunResult.Failure ->
                    CompileOutcome.Errors(
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
}
