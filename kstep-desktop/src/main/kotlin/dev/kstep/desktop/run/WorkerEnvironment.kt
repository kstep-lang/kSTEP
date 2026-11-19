package dev.kstep.desktop.run

/**
 * The environment of the render worker (ADR-0056). A script is arbitrary Kotlin, and since the AI panel it can also be
 * AI-generated code that runs before anyone read it; it must not be able to read a key from the environment. Applies to
 * EVERY worker of the desktop (live preview and AI candidate check). The AI candidate check additionally starts inside an
 * OS sandbox ([startSandboxed], ADR-0059); the user flows use [startSandboxed] when the system has a sandbox (ADR-0062), else [start] with a visible notice.
 */
object WorkerEnvironment {
    private val exactNames = setOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY")
    private val patterns = listOf("_API_KEY", "_TOKEN", "_SECRET", "PASSWORD")

    fun isSecretLike(name: String): Boolean {
        val n = name.uppercase()
        return n.startsWith("KSTEP_AI_") ||
            n in exactNames ||
            n.endsWith("_API_KEY") ||
            n.endsWith("_TOKEN") ||
            patterns.any { n.contains(it) }
    }

    /** Removes every secret-like variable from [env] (the map of a [ProcessBuilder]). */
    fun scrub(env: MutableMap<String, String>) {
        env.keys.filter { isSecretLike(it) }.forEach { env.remove(it) }
    }

    /** The worker start of the desktop: a plain [ProcessBuilder] with a scrubbed environment (no sandbox). */
    fun start(command: List<String>): Process = ProcessBuilder(command).also { scrub(it.environment()) }.start()

    /**
     * The worker start of the AI candidate check (ADR-0059): [command] inside [sandbox], scrubbed environment. An
     * unavailable sandbox throws [IllegalStateException]; there is never a fallback to [start].
     */
    fun startSandboxed(
        command: List<String>,
        sandbox: WorkerSandbox,
    ): Process {
        if (sandbox is WorkerSandbox.Unavailable) throw IllegalStateException(sandbox.reason)
        return ProcessBuilder(sandbox.wrap(command)).also { scrub(it.environment()) }.start()
    }

    /**
     * Runs [command] once with a scrubbed environment and discarded output; the exit code, or `null` when it did not
     * end within [timeoutMillis] (then it is killed) or could not be started.
     */
    fun probe(
        command: List<String>,
        timeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS,
    ): Int? =
        try {
            val p =
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { scrub(it.environment()) }
                    .start()
            if (p.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.exitValue()
            } else {
                p.destroyForcibly()
                null
            }
        } catch (_: java.io.IOException) {
            null
        }

    private const val DEFAULT_PROBE_TIMEOUT_MILLIS = 30_000L
}
