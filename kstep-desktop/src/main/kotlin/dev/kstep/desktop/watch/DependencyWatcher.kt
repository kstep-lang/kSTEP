package dev.kstep.desktop.watch

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/** Watches the input files a script read (ADR-0080); the set is replaced after every run that reports one. */
interface DependencyWatch : AutoCloseable {
    /** Makes [paths] the watched set: paths that stay keep their baseline, new ones start one, removed ones are dropped. */
    fun replace(paths: Set<Path>)
}

/**
 * Watches up to [MAX_PATHS] files with ONE scheduler thread, in the same rhythm and with the same quiet window as
 * [FileChangeWatcher] (ADR-0080). A poll in which any file changed or vanished gives exactly one [onChange], however
 * many files it was. Links count as gone ([statNoFollow]); nothing is ever read, only attributes are asked.
 *
 * A path that stays in the set across [replace] keeps its tracker, so a change that happens while a run is in flight is
 * not lost. A new path's first poll only records its state. After [close] there are no more callbacks.
 */
class DependencyWatcher(
    pollMillis: Long = 150,
    quietMillis: Long = 250,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    scheduler: Scheduler = DaemonScheduler(),
    private val stat: (Path) -> FileStamp? = ::statNoFollow,
    private val onChange: () -> Unit,
) : DependencyWatch {
    private val quiet = quietMillis
    private val trackers = LinkedHashMap<Path, StampTracker>()

    @Volatile
    private var closed = false

    private val handle: AutoCloseable = scheduler.every(pollMillis) { poll() }

    @Synchronized
    override fun replace(paths: Set<Path>) {
        if (closed) return
        val wanted = paths.take(MAX_PATHS).toSet()
        trackers.keys.retainAll(wanted)
        for (path in wanted) trackers.getOrPut(path) { StampTracker(quiet) }
    }

    private fun poll() {
        val fire =
            synchronized(this) {
                if (closed) return
                val now = clock()
                var any = false
                for ((path, tracker) in trackers) {
                    if (tracker.observe(stat(path), now) != null) any = true
                }
                any
            }
        if (fire && !closed) {
            try {
                onChange()
            } catch (e: Exception) {
                logger.warn { "the dependency listener failed (${e::class.simpleName})" }
            }
        }
    }

    override fun close() {
        closed = true
        handle.close()
    }

    companion object {
        const val MAX_PATHS: Int = 64
    }
}
