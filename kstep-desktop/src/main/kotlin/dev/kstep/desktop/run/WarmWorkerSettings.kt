package dev.kstep.desktop.run

/**
 * How the live preview uses a warm render worker (ADR-0081): off (a fresh process per run, the cold path), spare (a
 * fresh process is started in the background after each run and serves exactly one run, so the isolation is the one of
 * the cold path) or reuse (one process serves up to [maxRuns] runs of the same script, isolation between those runs is
 * best effort). The kill switch is the system property [PROPERTY] or the environment variable [ENV]; the property wins.
 *
 * An unknown value gives [DEFAULT_MODE]; anything that reads as "off" switches off. An unknown value never gives reuse.
 */
data class WarmWorkerSettings(
    val mode: Mode,
    val maxRuns: Int,
    val idleMillis: Long,
) {
    enum class Mode { OFF, SPARE, REUSE }

    /** `true` if this setting uses a warm worker at all. */
    val enabled: Boolean get() = mode != Mode.OFF

    companion object {
        const val PROPERTY: String = "kstep.desktop.warmWorker"
        const val ENV: String = "KSTEP_DESKTOP_WARM_WORKER"
        const val MAX_RUNS_PROPERTY: String = "kstep.desktop.warmWorker.maxRuns"
        const val IDLE_PROPERTY: String = "kstep.desktop.warmWorker.idleMillis"

        val DEFAULT_MODE: Mode = Mode.SPARE
        const val DEFAULT_MAX_RUNS: Int = 20
        const val MIN_MAX_RUNS: Int = 2
        const val MAX_MAX_RUNS: Int = 100
        const val DEFAULT_IDLE_MILLIS: Long = 120_000L
        const val MIN_IDLE_MILLIS: Long = 1_000L
        const val MAX_IDLE_MILLIS: Long = 600_000L

        private val OFF_WORDS = setOf("off", "false", "0", "no", "none", "cold", "disabled")

        fun resolve(
            property: String? = System.getProperty(PROPERTY),
            env: String? = System.getenv(ENV),
            maxRuns: String? = System.getProperty(MAX_RUNS_PROPERTY),
            idle: String? = System.getProperty(IDLE_PROPERTY),
        ): WarmWorkerSettings {
            val word = (property ?: env)?.trim()?.lowercase()
            val mode =
                when {
                    word == null || word.isEmpty() -> DEFAULT_MODE
                    word in OFF_WORDS -> Mode.OFF
                    word == "spare" -> Mode.SPARE
                    word == "reuse" -> Mode.REUSE
                    else -> DEFAULT_MODE
                }
            val runs = maxRuns?.trim()?.toIntOrNull()?.takeIf { it in MIN_MAX_RUNS..MAX_MAX_RUNS } ?: DEFAULT_MAX_RUNS
            val idleMillis =
                idle?.trim()?.toLongOrNull()?.takeIf { it in MIN_IDLE_MILLIS..MAX_IDLE_MILLIS } ?: DEFAULT_IDLE_MILLIS
            return WarmWorkerSettings(mode, if (mode == Mode.REUSE) runs else 1, idleMillis)
        }
    }
}
