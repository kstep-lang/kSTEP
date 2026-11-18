package dev.kstep.desktop.run

/**
 * The environment of the render worker (ADR-0056). A script is arbitrary Kotlin, and since the AI panel it can also be
 * AI-generated code that runs before anyone read it; it must not be able to read a key from the environment. Applies to
 * EVERY worker of the desktop (live preview and AI candidate check).
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

    /** The worker start of the desktop: a plain [ProcessBuilder] with a scrubbed environment. */
    fun start(command: List<String>): Process = ProcessBuilder(command).also { scrub(it.environment()) }.start()
}
