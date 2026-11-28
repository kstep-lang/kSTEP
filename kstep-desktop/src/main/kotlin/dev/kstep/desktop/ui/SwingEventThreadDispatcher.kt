package dev.kstep.desktop.ui

import kotlinx.coroutines.CoroutineDispatcher
import java.awt.EventQueue
import kotlin.coroutines.CoroutineContext

/**
 * Runs coroutine blocks on the Swing event thread. The app has no `kotlinx-coroutines-swing` on its class path, so
 * `Dispatchers.Main` is missing there (found by the hand test of Welle MCP-3: every action tool answered `INTERNAL`).
 */
internal object SwingEventThreadDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        EventQueue.invokeLater(block)
    }
}
