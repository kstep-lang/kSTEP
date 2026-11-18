package dev.kstep.desktop.ai

/**
 * Koog logs the WHOLE prompt (the user's script) at DEBUG and Ktor logs requests; slf4j-simple reads its configuration
 * once per JVM, so this must run before the first logger is created (ADR-0056).
 */
object AiLogging {
    val QUIET_PROPERTIES: Map<String, String> =
        mapOf(
            "org.slf4j.simpleLogger.log.ai.koog" to "warn",
            "org.slf4j.simpleLogger.log.io.ktor" to "warn",
            "org.slf4j.simpleLogger.log.dev.kstep.desktop.ai" to "info",
        )

    fun quiet() {
        QUIET_PROPERTIES.forEach { (k, v) -> if (System.getProperty(k) == null) System.setProperty(k, v) }
    }
}
