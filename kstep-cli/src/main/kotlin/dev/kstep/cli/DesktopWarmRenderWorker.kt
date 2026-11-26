package dev.kstep.cli

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.preview.PreviewSource
import dev.kstep.script.KStepScriptHost
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Entry point of the warm render worker of kstep-desktop (ADR-0081). Not a user command: only the desktop's
 * `WarmWorkerClient` starts this class, as a separate JVM, through [ImportWorkerLaunch].
 *
 * The worker loads the JVM, the script compiler and the kstep classes once, compiles a tiny script as warm-up, prints a
 * `hello` line and then serves requests (see [WarmRenderProtocol]) one at a time. Each render runs `runRender` exactly as
 * [DesktopRenderWorker] does, on a fresh thread, with stdout and stderr captured (bounded) and `exit` calls turned into
 * the end of the request ([RenderExit]). After `maxRuns` replies, after a reply that left a trace (see [WarmRunState]),
 * on stdin end-of-file, on a malformed request and after the idle time the process ends with `halt`, never `exit`, so
 * no shutdown hook of a script runs. The desktop does not rely on any of this: it kills the process on every irregularity.
 */
object DesktopWarmRenderWorker {
    const val MAIN_CLASS: String = "dev.kstep.cli.DesktopWarmRenderWorker"

    /** System property (set by the desktop): how many requests this process serves, 1..100. */
    const val MAX_RUNS_PROPERTY: String = "kstep.desktop.warm.maxRuns"

    /** System property (set by the desktop): milliseconds without a request after which the process ends. */
    const val IDLE_PROPERTY: String = "kstep.desktop.warm.idleMillis"

    private const val MAX_RUNS_LIMIT = 100
    private const val DEFAULT_IDLE_MILLIS = 120_000L
    private const val USAGE_EXIT = 2
    private const val RUN_THREAD_JOIN_MILLIS = 1_000L
    private const val IDLE_POLL_MILLIS = 250L

    @Volatile
    private var idleSince: Long = System.nanoTime()

    @Volatile
    private var serving: Boolean = false

    @JvmStatic
    fun main(args: Array<String>) {
        // Same reason as in MainKt.main: kotlin-logging's start-up banner would go to stdout.
        KotlinLoggingConfiguration.logStartupMessage = false
        val maxRuns = (System.getProperty(MAX_RUNS_PROPERTY)?.toIntOrNull() ?: 1).coerceIn(1, MAX_RUNS_LIMIT)
        val idleMillis = System.getProperty(IDLE_PROPERTY)?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_IDLE_MILLIS
        val stdoutCharset = ImportWorkerLaunch.stdoutCharset()
        val stderrCharset = ImportWorkerLaunch.stderrCharset()
        // The protocol channel and the request channel are the real descriptors; the script gets neither.
        val protocol = FileOutputStream(FileDescriptor.out)
        val requests = BufferedInputStream(FileInputStream(FileDescriptor.`in`))
        System.setIn(ByteArrayInputStream(ByteArray(0)))

        warmUp()
        startIdleWatchdog(idleMillis)
        val state = WarmRunState.capture()
        val baseline = WarmRunState.threadSnapshot()
        writeLine(protocol, WarmRenderProtocol.helloLine())

        var served = 0
        while (true) {
            idleSince = System.nanoTime()
            serving = false
            val request =
                when (val line = WarmRenderProtocol.readLine(requests, WarmRenderProtocol.MAX_REQUEST_LINE_BYTES)) {
                    is WarmRenderProtocol.Line.Text ->
                        WarmRenderProtocol.decodeRequest(
                            String(line.bytes, StandardCharsets.UTF_8),
                        )
                    WarmRenderProtocol.Line.EndOfStream -> halt(0)
                    WarmRenderProtocol.Line.TooLong -> halt(USAGE_EXIT)
                } ?: halt(USAGE_EXIT)
            serving = true
            val reply = serve(request, state, baseline, stdoutCharset, stderrCharset)
            served++
            val retire = served >= maxRuns || reply.recycle
            writeLine(protocol, WarmRenderProtocol.encodeReply(reply))
            if (retire) halt(0)
        }
    }

    // One box through the whole geometry path (kernel, mesh, GLB): the native library and the threads it starts belong to
    // the baseline, and the first real geometry run is not the one that pays for loading them.
    internal val GEOMETRY_WARMUP =
        """
        val appCtx = applicationContext { application = "warm-up" }.getOrThrow()
        val prodCtx = productContext { name = "w"; frameOfReference = appCtx; disciplineType = "w" }.getOrThrow()
        val defCtx = productDefinitionContext { name = "w"; frameOfReference = appCtx; lifeCycleStage = "w" }.getOrThrow()
        val part = product("W-1") { name = "W"; frameOfReference = setOf(prodCtx) }.getOrThrow()
        val wf = productDefinitionFormation("W-1-F") { ofProduct = part }.getOrThrow()
        val definition = productDefinition("W-1-D") { formation = wf; frameOfReference = defCtx }.getOrThrow()
        stepFile(fileName = "warmup.step") {
            root(definition)
            shape(definition, OcctKernel.makeBox(10.0, 20.0, 30.0))
        }
        """.trimIndent() + "\n"

    private const val PLAIN_WARMUP = "val warmup = 1\n"

    private fun warmUp() {
        // The result is irrelevant; one real render of a tiny script loads the compiler, the script host, the preview
        // path and the threads they start (which the baseline must then contain).
        val savedOut = System.out
        val savedErr = System.err
        val dir =
            try {
                Files.createTempDirectory("kstep-warm-")
            } catch (_: Exception) {
                null
            }
        try {
            System.setOut(PrintStream(OutputStream.nullOutputStream()))
            System.setErr(PrintStream(OutputStream.nullOutputStream()))
            if (dir != null) {
                val script = dir.resolve("warmup.kstep.kts")
                Files.writeString(
                    script,
                    if (OcctKernel.availability() is OcctAvailability.Available) GEOMETRY_WARMUP else PLAIN_WARMUP,
                )
                val command =
                    resolveCommand(
                        arrayOf(
                            "render",
                            script.toString(),
                            "--format",
                            "glb",
                            "--out",
                            dir.resolve("warmup.glb").toString(),
                            "--output",
                            "json",
                        ),
                    )
                if (command is CliCommand.Render) {
                    val thread =
                        Thread({ RenderExit.capture { runRender(command, includeParts = true) } }, "kstep-warm-up")
                    thread.start()
                    thread.join()
                }
            } else {
                KStepScriptHost.eval("", "warmup.kstep.kts")
            }
        } catch (_: Throwable) {
            // a failing warm-up only costs the saved time
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
            dir?.toFile()?.deleteRecursively()
        }
    }

    private fun serve(
        request: WarmRenderProtocol.Request,
        state: WarmRunState,
        baseline: Set<Thread>,
        stdoutCharset: java.nio.charset.Charset,
        stderrCharset: java.nio.charset.Charset,
    ): WarmRenderProtocol.Reply {
        val outCapture = BoundedCapture(WarmRenderProtocol.MAX_CAPTURED_STDOUT_BYTES)
        val errCapture = BoundedCapture(WarmRenderProtocol.MAX_CAPTURED_STDERR_BYTES)
        val command =
            resolveCommand(
                arrayOf("render", request.script, "--format", "glb", "--out", request.out, "--output", "json"),
            )
        if (command !is CliCommand.Render || !command.jsonOutput) {
            return reply(request, USAGE_EXIT, outCapture, errCapture, listOf("usage"))
        }
        // An unsaved buffer (ADR-0086) is validated first. A refusal names only the reason, never a path or content,
        // and recycles the process: the cause can be a planted file.
        var source: PreviewSource? = null
        val unsaved = request.unsaved
        if (unsaved != null) {
            val loaded = UnsavedBufferInput.load(request.script, unsaved.scriptDir, unsaved.displayName, unsaved.sha256)
            if (loaded is UnsavedBufferInput.Result.Refused) {
                val marker = "${UnsavedBufferInput.REFUSED_MARKER}${loaded.reason.name}\n"
                errCapture.write(marker.toByteArray(StandardCharsets.UTF_8))
                return reply(request, USAGE_EXIT, outCapture, errCapture, listOf("unsaved-refused"))
            }
            source = (loaded as UnsavedBufferInput.Result.Ok).source
        }
        val watchdog =
            ImportWatchdog(ImportWatchdog.resolveTimeoutMillis() + ImportWorkerLaunch.ORPHAN_GRACE_MILLIS) {
                Runtime.getRuntime().halt(ImportWorkerLaunch.ORPHAN_EXIT_CODE)
            }.start()
        System.setOut(PrintStream(outCapture, false, stdoutCharset))
        System.setErr(PrintStream(errCapture, false, stderrCharset))
        var code = 1
        val reasons = ArrayList<String>()
        val thread =
            Thread({
                try {
                    code = RenderExit.capture { runRender(command, includeParts = true, source = source) }
                } catch (t: Throwable) {
                    t.printStackTrace()
                    code = 1
                    reasons += "uncaught"
                }
            }, "kstep-warm-run-${request.id}")
        thread.start()
        thread.join()
        System.out.flush()
        System.err.flush()
        watchdog.disarm()
        // A reply is built only after the streams are back, so a late write of a script thread cannot reach it.
        reasons += state.restoreAndDiff(baseline)
        if (outCapture.overflowed || errCapture.overflowed) reasons += "overflow"
        return reply(request, code, outCapture, errCapture, reasons)
    }

    private fun reply(
        request: WarmRenderProtocol.Request,
        code: Int,
        out: BoundedCapture,
        err: BoundedCapture,
        reasons: List<String>,
    ): WarmRenderProtocol.Reply =
        WarmRenderProtocol.Reply(
            id = request.id,
            exit = code,
            stdout = out.bytes(),
            stderr = err.bytes(),
            overflow = out.overflowed || err.overflowed,
            recycle = reasons.isNotEmpty(),
            recycleReason = reasons.firstOrNull(),
        )

    private fun startIdleWatchdog(idleMillis: Long) {
        val thread =
            Thread({
                while (true) {
                    Thread.sleep(IDLE_POLL_MILLIS)
                    if (!serving && (System.nanoTime() - idleSince) / NANOS_PER_MILLI > idleMillis) halt(0)
                }
            }, "kstep-warm-idle")
        thread.isDaemon = true
        thread.start()
    }

    private fun writeLine(
        protocol: OutputStream,
        line: String,
    ) {
        synchronized(protocol) {
            protocol.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            protocol.flush()
        }
    }

    private fun halt(code: Int): Nothing {
        Runtime.getRuntime().halt(code)
        throw IllegalStateException("unreachable")
    }

    private const val NANOS_PER_MILLI = 1_000_000L

    /** A stream that keeps at most [limit] bytes and notes that more was written. */
    private class BoundedCapture(
        private val limit: Int,
    ) : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        @Volatile
        var overflowed: Boolean = false
            private set

        @Synchronized
        override fun write(b: Int) {
            if (buffer.size() >= limit) overflowed = true else buffer.write(b)
        }

        @Synchronized
        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            val room = limit - buffer.size()
            if (len > room) {
                overflowed = true
                if (room > 0) buffer.write(b, off, room)
            } else {
                buffer.write(b, off, len)
            }
        }

        @Synchronized
        fun bytes(): ByteArray = buffer.toByteArray()
    }
}
