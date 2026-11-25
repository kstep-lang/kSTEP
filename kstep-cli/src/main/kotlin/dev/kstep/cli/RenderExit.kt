package dev.kstep.cli

import kotlin.system.exitProcess

/**
 * How a render ends (ADR-0081). In the CLI and the cold desktop worker a failed render ends the process
 * ([exitProcess]); the warm desktop worker runs many renders in one process and installs a handler through
 * [capture] that turns the end of one render into a [RenderExitSignal] on that thread instead.
 *
 * Without a handler the behaviour is byte-identical to a plain `exitProcess(code)`.
 */
internal object RenderExit {
    private val handler = ThreadLocal<((Int) -> Nothing)?>()

    /** Ends the current render with [code]: the process, or (under [capture]) only this request. */
    fun exit(code: Int): Nothing {
        val h = handler.get()
        if (h != null) h(code)
        exitProcess(code)
    }

    /** Runs [block] on this thread and returns the exit code it asked for (0 if it returned normally). */
    fun capture(block: () -> Unit): Int {
        handler.set { code -> throw RenderExitSignal(code) }
        return try {
            block()
            0
        } catch (signal: RenderExitSignal) {
            signal.code
        } finally {
            handler.remove()
        }
    }
}

/** Thrown by [RenderExit.exit] under [RenderExit.capture]; carries no stack trace. */
internal class RenderExitSignal(
    val code: Int,
) : Throwable(null, null, false, false)
