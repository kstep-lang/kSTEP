package dev.kstep.desktop.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.awt.EventQueue

class SwingEventThreadDispatcherTest :
    FunSpec({
        // Regression: Dispatchers.Main is missing in the app (no kotlinx-coroutines-swing), every MCP action answered INTERNAL.
        test("a block runs on the Swing event thread without Dispatchers.Main") {
            val onEdt = runBlocking { async(SwingEventThreadDispatcher) { EventQueue.isDispatchThread() }.await() }
            onEdt shouldBe true
        }
    })
