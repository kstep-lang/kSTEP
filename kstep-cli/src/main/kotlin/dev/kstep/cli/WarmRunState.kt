package dev.kstep.cli

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.Security
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.ForkJoinPool

/**
 * The process-wide state a script can change and a warm worker (ADR-0081, reuse mode) must hand back clean. [capture]
 * takes the snapshot after the warm-up; [restoreAndDiff] puts back what can be put back and names what cannot
 * (a worker with such a trace is recycled: it ends after the reply). Best effort: library statics, native state and
 * anything reached by reflection are not observable, which is why reuse is opt-in and bound to one script path.
 */
internal class WarmRunState private constructor(
    private val properties: Properties,
    private val locales: Map<Locale.Category, Locale>,
    private val defaultLocale: Locale,
    private val timeZone: TimeZone,
    private val handler: Thread.UncaughtExceptionHandler?,
    private val providers: List<String>,
    private val out: PrintStream,
    private val err: PrintStream,
    private val fdCount: Int?,
) {
    fun restoreAndDiff(baselineThreads: Set<Thread>): List<String> {
        val reasons = ArrayList<String>()
        val changed = changedProperties()
        if (changed.isNotEmpty()) {
            // The name of a changed property is the reason: it says what the script touched and holds no script data.
            reasons += "properties:" + changed.first().take(MAX_NAME_CHARS)
            restoreProperties()
        }
        if (Locale.getDefault() != defaultLocale || locales.any { (c, l) -> Locale.getDefault(c) != l }) {
            reasons += "locale"
            Locale.setDefault(defaultLocale)
            locales.forEach { (category, locale) -> Locale.setDefault(category, locale) }
        }
        if (TimeZone.getDefault() != timeZone) {
            reasons += "timezone"
            TimeZone.setDefault(timeZone)
        }
        if (Thread.getDefaultUncaughtExceptionHandler() !== handler) {
            reasons += "handler"
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
        if (Security.getProviders().map { it.name } != providers) reasons += "security"
        System.setOut(out)
        System.setErr(err)
        extraThreads(baselineThreads)?.let { reasons += "threads:$it" }
        val pool = ForkJoinPool.commonPool()
        if (pool.activeThreadCount > 0 || pool.hasQueuedSubmissions()) reasons += "commonpool"
        val fds = openFds()
        if (fdCount != null && fds != null && fds > fdCount + FD_SLACK) reasons += "fds"
        return reasons
    }

    private fun changedProperties(): List<String> {
        val now = System.getProperties()
        val before = properties.stringPropertyNames()
        val after = now.stringPropertyNames()
        return (
            (before + after) -
                (before.intersect(after).filter { now.getProperty(it) == properties.getProperty(it) }.toSet())
        ).sorted()
    }

    private fun restoreProperties() {
        val live = System.getProperties()
        live.stringPropertyNames().filter { it !in properties.stringPropertyNames() }.forEach { live.remove(it) }
        properties.stringPropertyNames().forEach { live.setProperty(it, properties.getProperty(it)) }
    }

    private fun extraThreads(baseline: Set<Thread>): String? {
        val deadline = System.nanoTime() + THREAD_GRACE_NANOS
        while (true) {
            val extra =
                Thread.getAllStackTraces().keys.filter {
                    it.isAlive &&
                        it !in baseline &&
                        it !== Thread.currentThread()
                }
            if (extra.isEmpty()) return null
            if (System.nanoTime() >=
                deadline
            ) {
                return extra
                    .joinToString(",") {
                        it.name
                            .filter { c ->
                                c.isLetterOrDigit() || c in " _.-"
                            }.take(MAX_NAME_CHARS)
                    }.take(MAX_NAME_CHARS)
            }
            Thread.sleep(THREAD_POLL_MILLIS)
        }
    }

    companion object {
        private const val MAX_NAME_CHARS = 40
        private const val FD_SLACK = 8
        private const val THREAD_GRACE_NANOS = 200_000_000L
        private const val THREAD_POLL_MILLIS = 10L

        fun capture(): WarmRunState {
            // The JDK sets some properties lazily (user.timezone at the first TimeZone.getDefault()): touch them first,
            // so the property snapshot already holds them.
            val zone = TimeZone.getDefault()
            val locale = Locale.getDefault()
            val perCategory = Locale.Category.entries.associateWith { Locale.getDefault(it) }
            val providers = Security.getProviders().map { it.name }
            return WarmRunState(
                properties = System.getProperties().clone() as Properties,
                locales = perCategory,
                defaultLocale = locale,
                timeZone = zone,
                handler = Thread.getDefaultUncaughtExceptionHandler(),
                providers = providers,
                out = System.out,
                err = System.err,
                fdCount = openFds(),
            )
        }

        /** The threads alive now (the baseline of a warm worker). */
        fun threadSnapshot(): Set<Thread> = Thread.getAllStackTraces().keys.toSet()

        /** Linux only; `null` where `/proc/self/fd` does not exist (the check is then simply off). */
        fun openFds(): Int? =
            try {
                val dir = Path.of("/proc/self/fd")
                if (Files.isDirectory(dir)) Files.list(dir).use { it.count().toInt() } else null
            } catch (_: Exception) {
                null
            }
    }
}
