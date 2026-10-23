package dev.kstep.cli

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wall-clock timer of `kstep import` (ADR-0038). Since the worker model, the supervisor ([ImportWorkerSupervisor])
 * enforces the timeout by killing the worker process; inside the worker this class only bounds an orphan (a worker
 * whose supervisor was itself killed): [runImportInProcess] starts it with the timeout plus a grace period and an
 * action that halts silently. [resolveTimeoutMillis] is the single source of the effective timeout for both sides.
 *
 * [disarm] and the firing race are kept as a small, tested primitive: [disarm] returns `false` once the action has run.
 */
internal class ImportWatchdog(
    private val timeoutMillis: Long,
    private val onTimeout: () -> Unit,
) {
    private val state = AtomicInteger(ARMED)
    private val disarmed = CountDownLatch(1)

    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    /** Starts the daemon thread; a daemon never keeps the JVM alive on a normal exit. */
    fun start(): ImportWatchdog {
        val thread =
            Thread({
                val released = disarmed.await(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!released && state.compareAndSet(ARMED, FIRED)) onTimeout()
            }, "kstep-import-watchdog")
        thread.isDaemon = true
        thread.start()
        return this
    }

    /** `true` if the caller now owns the output (the watchdog will never fire), `false` if it has already fired. */
    fun disarm(): Boolean {
        val owned = state.compareAndSet(ARMED, DISARMED) || state.get() == DISARMED
        disarmed.countDown()
        return owned
    }

    companion object {
        private const val ARMED = 0
        private const val DISARMED = 1
        private const val FIRED = 2

        /** Default and upper bound of the import timeout. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000L

        /** System property that can LOWER the timeout (milliseconds); larger or invalid values are ignored. */
        const val TIMEOUT_PROPERTY: String = "kstep.import.timeoutMillis"

        /** Environment variable with the same meaning as [TIMEOUT_PROPERTY]; the property wins. */
        const val TIMEOUT_ENV: String = "KSTEP_IMPORT_TIMEOUT_MILLIS"

        /**
         * The effective timeout: [DEFAULT_TIMEOUT_MILLIS] unless [property] (else [env]) is a positive integer
         * below it. The bound can only be tightened, never raised or switched off.
         */
        fun resolveTimeoutMillis(
            property: String? = System.getProperty(TIMEOUT_PROPERTY),
            env: String? = System.getenv(TIMEOUT_ENV),
        ): Long {
            val requested = (property ?: env)?.trim()?.toLongOrNull() ?: return DEFAULT_TIMEOUT_MILLIS
            return if (requested in 1 until DEFAULT_TIMEOUT_MILLIS) requested else DEFAULT_TIMEOUT_MILLIS
        }

        /** Parks the calling thread until the firing watchdog halts the process. */
        fun awaitHalt(): Nothing {
            while (true) {
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (_: InterruptedException) {
                    // keep waiting: the watchdog thread is about to halt the JVM
                }
            }
        }
    }
}
