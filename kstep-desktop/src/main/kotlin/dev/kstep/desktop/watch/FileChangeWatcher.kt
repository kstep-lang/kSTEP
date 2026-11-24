package dev.kstep.desktop.watch

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What one poll sees of the watched file; two equal stamps mean "unchanged" (a rename-replace changes [fileKey]). */
data class FileStamp(
    val size: Long,
    val modifiedNanos: Long,
    val fileKey: Any?,
)

enum class WatchSignal { CHANGED, MISSING }

/** Runs [every] task periodically until the returned handle is closed. Injected so the watcher is testable. */
fun interface Scheduler {
    fun every(
        periodMs: Long,
        task: () -> Unit,
    ): AutoCloseable
}

/** The production scheduler: one daemon thread named `kstep-desktop-watch`. */
class DaemonScheduler : Scheduler {
    override fun every(
        periodMs: Long,
        task: () -> Unit,
    ): AutoCloseable {
        val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "kstep-desktop-watch").apply { isDaemon = true }
            }
        executor.scheduleWithFixedDelay(
            {
                try {
                    task()
                } catch (e: Exception) {
                    logger.warn { "file watcher poll failed (${e::class.simpleName})" }
                }
            },
            periodMs,
            periodMs,
            TimeUnit.MILLISECONDS,
        )
        return AutoCloseable { executor.shutdownNow() }
    }
}

private val logger = KotlinLogging.logger {}

/**
 * Watches ONE file by polling (ADR-0051, F4): `java.nio.file.WatchService` is poll-based and takes seconds on macOS.
 * A change of the [FileStamp] starts a quiet window; once the stamp stays the same for [quietMillis], exactly one
 * [WatchSignal.CHANGED] follows (editors write in several steps). A file that is gone for [quietMillis] gives exactly one
 * [WatchSignal.MISSING]; when it reappears, [WatchSignal.CHANGED]. [clock] and [scheduler] are injected.
 *
 * The first poll only records the current state: opening a file is not a change of it.
 */
class FileChangeWatcher(
    private val file: Path,
    pollMillis: Long = 150,
    quietMillis: Long = 250,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    scheduler: Scheduler = DaemonScheduler(),
    private val stat: (Path) -> FileStamp? = ::statFile,
    private val onEvent: (WatchSignal) -> Unit,
) : AutoCloseable {
    private val tracker = StampTracker(quietMillis)

    @Volatile
    private var closed = false

    private val handle: AutoCloseable = scheduler.every(pollMillis) { poll() }

    @Synchronized
    private fun poll() {
        if (closed) return
        tracker.observe(stat(file), clock())?.let(onEvent)
    }

    override fun close() {
        closed = true
        handle.close()
    }
}

/**
 * The state of one watched file between polls (the logic of [FileChangeWatcher], shared with the dependency watcher,
 * ADR-0080). Not thread-safe: callers synchronise. The first observation only records the current state.
 */
internal class StampTracker(
    private val quietMillis: Long,
) {
    private var lastStamp: FileStamp? = null
    private var initialised = false
    private var pendingSince: Long? = null
    private var reportedMissing = false

    /** Feeds one poll; returns the signal this poll completes, if any. */
    fun observe(
        stamp: FileStamp?,
        now: Long,
    ): WatchSignal? {
        if (!initialised) {
            initialised = true
            lastStamp = stamp
            reportedMissing = stamp == null
            return null
        }
        if (stamp != lastStamp) {
            lastStamp = stamp
            pendingSince = now
            return null
        }
        val since = pendingSince ?: return null
        if (now - since < quietMillis) return null
        pendingSince = null
        return if (stamp == null) {
            if (reportedMissing) {
                null
            } else {
                reportedMissing = true
                WatchSignal.MISSING
            }
        } else {
            reportedMissing = false
            WatchSignal.CHANGED
        }
    }
}

/**
 * Like [statFile], but never follows a link: a link, a directory or anything else that is not a regular file counts as
 * gone (`null`). Used for the input files of a script (ADR-0080): the desktop only asks for attributes, it never opens
 * them, so a FIFO or a device cannot hang it.
 */
fun statNoFollow(path: Path): FileStamp? =
    try {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isRegularFile) {
            null
        } else {
            FileStamp(attrs.size(), attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS), attrs.fileKey())
        }
    } catch (_: Exception) {
        null
    }

/** Follows symbolic links on purpose: a symlinked script is legitimate. `null` if the file is gone or unreadable. */
fun statFile(path: Path): FileStamp? =
    try {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (!attrs.isRegularFile) {
            null
        } else {
            val modified = attrs.lastModifiedTime()
            FileStamp(attrs.size(), modified.to(TimeUnit.NANOSECONDS), attrs.fileKey())
        }
    } catch (_: Exception) {
        null
    }
