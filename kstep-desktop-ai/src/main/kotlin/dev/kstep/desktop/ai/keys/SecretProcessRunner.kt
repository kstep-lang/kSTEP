package dev.kstep.desktop.ai.keys

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val timedOut: Boolean,
)

/** Runs one keychain command (the only place of this module that starts a process, ADR-0056). */
fun interface SecretCommandRunner {
    /** The secret goes through [stdin] only, never through the command line. stderr is discarded. */
    fun run(
        command: List<String>,
        stdin: CharArray?,
    ): ProcessResult
}

class SecretProcessRunner(
    private val timeoutMillis: Long = 60_000, // the first macOS keychain access shows a system dialog
) : SecretCommandRunner {
    override fun run(
        command: List<String>,
        stdin: CharArray?,
    ): ProcessResult {
        val process =
            try {
                ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (_: Exception) {
                return ProcessResult(-1, "", false)
            }
        val out = ByteArrayOutputStream()
        val reader =
            Thread({
                try {
                    val buf = ByteArray(1024)
                    process.inputStream.use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (out.size() < MAX_STDOUT) out.write(buf, 0, minOf(n, MAX_STDOUT - out.size()))
                        }
                    }
                } catch (_: Exception) {
                    // reading ends with the process
                }
            }, "kstep-keychain-reader").apply { isDaemon = true }
        reader.start()
        try {
            process.outputStream.use { os ->
                if (stdin != null) {
                    val bytes = String(stdin).toByteArray(Charsets.UTF_8)
                    try {
                        os.write(bytes)
                        os.flush()
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        } catch (_: Exception) {
            // the tool may exit before reading stdin
        }
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            return ProcessResult(-1, "", true)
        }
        reader.join(1_000)
        return ProcessResult(process.exitValue(), out.toString(Charsets.UTF_8), false)
    }

    private companion object {
        const val MAX_STDOUT = 8 * 1024
    }
}
