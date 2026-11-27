package dev.kstep.desktop.mcp

import java.util.concurrent.atomic.AtomicBoolean

/** At most [limit] acquisitions within any [windowMs]. Thread safe; the clock is passed in. */
internal class SlidingWindow(
    private val limit: Int = DEFAULT_LIMIT,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val times = LongArray(limit)
    private var head = 0
    private var size = 0

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        if (size == limit) {
            val oldest = times[head]
            if (now - oldest < windowMs) return false
            head = (head + 1) % limit
            size--
        }
        times[(head + size) % limit] = now
        size++
        return true
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val DEFAULT_WINDOW_MS = 1000L
    }
}

/** At least [minMs] between two started runs. [mark] is called only after a run was really started. */
internal class RunSpacing(
    private val minMs: Long = DEFAULT_MIN_MS,
) {
    private var last: Long? = null

    /** `null` when a run may start now, else how long to wait. */
    @Synchronized
    fun check(now: Long): Long? {
        val l = last ?: return null
        val wait = minMs - (now - l)
        return if (wait > 0) wait else null
    }

    @Synchronized
    fun mark(now: Long) {
        last = now
    }

    companion object {
        const val DEFAULT_MIN_MS = 1000L
    }
}

/** At most one call waits for a run at a time: only four HTTP workers exist and a wait holds one for up to 30 s. */
internal class SingleWaiter {
    private val busy = AtomicBoolean(false)

    fun tryEnter(): Boolean = busy.compareAndSet(false, true)

    fun leave() = busy.set(false)
}
