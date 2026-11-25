package dev.kstep.desktop.run

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.cli.WarmRenderProtocol
import dev.kstep.cli.WorkerTempDir
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One warm render worker process as the desktop sees it (ADR-0081): its stdin for requests, a reader thread that cuts
 * its stdout into bounded lines, a drainer for the real stderr (JVM and sandbox messages), and its private temp folder.
 * Everything the process prints is untrusted; nothing here interprets a line, [WarmWorkerClient] does.
 */
internal class WarmWorkerProcess(
    val process: Process,
    val tmpDir: Path,
) {
    /** What the stdout reader found. */
    sealed interface Event {
        class Line(
            val bytes: ByteArray,
        ) : Event

        data object TooLong : Event

        data object StderrOverflow : Event

        data object End : Event
    }

    val pid: Long = process.pid()

    /** Bounded: a worker that floods its stdout is stopped by the pipe, not by memory. */
    val events = LinkedBlockingQueue<Event>(EVENT_CAPACITY)

    @Volatile
    var runsServed: Int = 0

    @Volatile
    var boundScript: Path? = null

    @Volatile
    var lastUsedNanos: Long = System.nanoTime()

    @Volatile
    private var ended = false
    private val ready = CountDownLatch(1)
    private val dead = AtomicBoolean(false)
    private val stderrTailBuffer = ByteArrayOutputStream()
    private val foreignBuffer = ByteArrayOutputStream()

    init {
        daemon("kstep-warm-stdout") { readStdout() }
        daemon("kstep-warm-stderr") { drainStderr() }
    }

    fun isAlive(): Boolean = !dead.get() && !ended && process.isAlive

    /** `true` once the worker printed its hello line; waits up to [timeoutMillis] and gives up when the process ended. */
    fun awaitHello(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            if (ready.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) return true
            if (ended || dead.get() || System.nanoTime() >= deadline) return false
        }
    }

    /** Writes one request line; `false` if the worker's stdin is gone. */
    fun send(line: String): Boolean =
        try {
            val out = process.outputStream
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (_: IOException) {
            false
        }

    /** The last bytes the worker printed to its real stderr (for a crash that gave no reply). */
    fun stderrTail(): ByteArray = synchronized(stderrTailBuffer) { stderrTailBuffer.toByteArray() }

    /** Lines of stdout that were not protocol lines (the JVM's own messages, a script writing to the raw descriptor). */
    fun foreignTail(): ByteArray = synchronized(foreignBuffer) { foreignBuffer.toByteArray() }

    fun noteForeign(line: ByteArray) {
        synchronized(foreignBuffer) {
            if (foreignBuffer.size() < TAIL_BYTES) {
                foreignBuffer.write(line, 0, minOf(line.size, TAIL_BYTES - foreignBuffer.size()))
                foreignBuffer.write('\n'.code)
            }
        }
    }

    /** Non-blocking: asks the OS to end the process and its descendants. The reader then reports the end. */
    fun killNow() {
        try {
            process.descendants().forEach { it.destroyForcibly() }
        } catch (_: UnsupportedOperationException) {
            // only the process itself
        }
        process.destroyForcibly()
    }

    /** Ends the process tree, waits (bounded) and removes the temp folder; safe to call repeatedly. */
    fun kill() {
        if (!dead.compareAndSet(false, true)) return
        ImportWorkerSupervisor.kill(process)
        events.clear()
        if (!WorkerTempDir.deleteRecursively(tmpDir)) {
            // best effort: the session folder is removed as a whole when the desktop closes
        }
    }

    private fun readStdout() {
        try {
            BufferedInputStream(process.inputStream).use { input ->
                while (true) {
                    when (val line = WarmRenderProtocol.readLine(input, WarmRenderProtocol.MAX_REPLY_LINE_BYTES)) {
                        is WarmRenderProtocol.Line.Text -> {
                            if (ready.count > 0L && WarmRenderProtocol.isHello(String(line.bytes, Charsets.UTF_8))) {
                                ready.countDown()
                            } else if (looksLikeProtocol(line.bytes)) {
                                offer(Event.Line(line.bytes))
                            } else {
                                // Not a protocol line (the JVM's own messages, a script writing to the raw descriptor):
                                // only a bounded tail is kept, so it can neither fill the queue nor the memory.
                                noteForeign(line.bytes)
                            }
                        }
                        WarmRenderProtocol.Line.TooLong -> {
                            offer(Event.TooLong)
                            return
                        }
                        WarmRenderProtocol.Line.EndOfStream -> return
                    }
                }
            }
        } catch (_: IOException) {
            // the pipe was closed by a kill
        } finally {
            ended = true
            offer(Event.End)
        }
    }

    private fun looksLikeProtocol(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size && (bytes[i] == ' '.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
        return i < bytes.size && bytes[i] == '{'.code.toByte()
    }

    private fun drainStderr() {
        var total = 0L
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            process.errorStream.use { stream ->
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    total += n
                    synchronized(stderrTailBuffer) {
                        stderrTailBuffer.write(chunk, 0, n)
                        if (stderrTailBuffer.size() > 2 * TAIL_BYTES) {
                            val keep =
                                stderrTailBuffer.toByteArray().copyOfRange(
                                    stderrTailBuffer.size() - TAIL_BYTES,
                                    stderrTailBuffer.size(),
                                )
                            stderrTailBuffer.reset()
                            stderrTailBuffer.write(keep)
                        }
                    }
                    if (total > WarmRenderProtocol.MAX_CAPTURED_STDERR_BYTES) {
                        offer(Event.StderrOverflow)
                        return
                    }
                }
            }
        } catch (_: IOException) {
            // closed by a kill
        }
    }

    // Blocks while the consumer is slow, but gives up as soon as the process is dead, so no thread outlives a kill.
    private fun offer(event: Event) {
        while (!dead.get()) {
            if (events.offer(event, POLL_MILLIS, TimeUnit.MILLISECONDS)) return
        }
    }

    private fun daemon(
        name: String,
        body: () -> Unit,
    ) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    private companion object {
        const val EVENT_CAPACITY = 2
        const val POLL_MILLIS = 100L
        const val TAIL_BYTES = 64 * 1024
        const val CHUNK_BYTES = 8 * 1024
    }
}
