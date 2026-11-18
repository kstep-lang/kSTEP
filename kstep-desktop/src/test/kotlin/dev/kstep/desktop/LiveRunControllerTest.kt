package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.watch.WatchSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The controller's generation logic with a fake runner (no process): late results of old runs are dropped. */
class LiveRunControllerTest :
    StringSpec({
        val file = Path.of("/tmp/never-read.kstep.kts")

        fun model(generation: Long) = DesktopModel(null, 0, generation, Instant.EPOCH, 1, emptyList())

        class Harness {
            val started = CopyOnWriteArrayList<Long>()
            val gates = HashMap<Long, CountDownLatch>()
            var signal: (WatchSignal) -> Unit = {}

            @Synchronized
            fun gate(gen: Long): CountDownLatch = gates.getOrPut(gen) { CountDownLatch(1) }

            val controller =
                LiveRunController(
                    runner = { _, gen, _ ->
                        started += gen
                        gate(gen).await(30, TimeUnit.SECONDS)
                        RunResult.Success(model(gen))
                    },
                    watcherFactory = { _, onEvent ->
                        signal = onEvent
                        AutoCloseable { }
                    },
                )
        }

        "the late result of an old generation is discarded".config(timeout = 60.seconds) {
            val h = Harness()
            try {
                h.controller.open(file) // gen 1, blocked in the runner
                TestSupport.await(10_000) { h.started.contains(1L) } shouldBe true
                h.signal(WatchSignal.CHANGED) // gen 2 queued behind it
                h.gate(1).countDown() // the old run finishes late
                TestSupport.await(10_000) { h.started.contains(2L) } shouldBe true
                // the late result of gen 1 did not change the state: still the running gen 2
                h.controller.state.value
                    .shouldBeInstanceOf<DesktopState.Running>()
                    .generation shouldBe 2
                h.gate(2).countDown()
                val ok = TestSupport.awaitState(h.controller, 10_000) { it is DesktopState.Ok }
                ok.generation shouldBe 2
            } finally {
                h.controller.close()
            }
        }

        "queued runs of outdated generations never start a runner".config(timeout = 60.seconds) {
            val h = Harness()
            try {
                h.controller.open(file)
                TestSupport.await(10_000) { h.started.contains(1L) } shouldBe true
                repeat(5) { h.signal(WatchSignal.CHANGED) } // gens 2..6
                h.gate(1).countDown()
                TestSupport.await(10_000) { h.started.contains(6L) } shouldBe true
                h.gate(6).countDown()
                TestSupport.awaitState(h.controller, 10_000) { it is DesktopState.Ok }
                h.started.toList() shouldBe listOf(1L, 6L)
            } finally {
                h.controller.close()
            }
        }

        "Open counts for the camera reset and close is idempotent".config(timeout = 60.seconds) {
            val h = Harness()
            h.controller.openCount.value shouldBe 0
            h.controller.open(file)
            h.controller.openCount.value shouldBe 1
            h.gate(1).countDown()
            h.controller.close()
            h.controller.close()
        }

        "a run filter that says no starts no run but the editor still sees the signal; MISSING always runs".config(
            timeout = 60.seconds,
        ) {
            val h = Harness()
            try {
                val seen =
                    java.util.concurrent.atomic
                        .AtomicInteger()
                h.controller.onDiskSignal = { seen.incrementAndGet() }
                h.controller.runFilter = { false }
                h.controller.open(file)
                TestSupport.await(10_000) { h.started.contains(1L) } shouldBe true
                h.gate(1).countDown()
                TestSupport.awaitState(h.controller, 10_000) { it is DesktopState.Ok }
                h.signal(WatchSignal.CHANGED)
                seen.get() shouldBe 1
                Thread.sleep(300)
                h.started.toList() shouldBe listOf(1L)
                h.controller.state.value.generation shouldBe 1
                h.signal(WatchSignal.MISSING)
                seen.get() shouldBe 2
                h.controller.state.value
                    .shouldBeInstanceOf<DesktopState.Error>()
                h.controller.runFilter = { true }
                h.signal(WatchSignal.CHANGED)
                TestSupport.await(10_000) { h.started.contains(3L) || h.started.contains(2L) } shouldBe true
            } finally {
                h.controller.close()
            }
        }

        "clear returns to Idle, drops the late result and the next open gets a fresh generation".config(
            timeout = 60.seconds,
        ) {
            val h = Harness()
            try {
                h.controller.open(file) // gen 1, blocked
                TestSupport.await(10_000) { h.started.contains(1L) } shouldBe true
                h.controller.clear()
                h.controller.state.value shouldBe DesktopState.Idle
                h.gate(1).countDown()
                Thread.sleep(200)
                h.controller.state.value shouldBe DesktopState.Idle
                h.controller.open(file)
                h.controller.state.value.generation shouldBe 2
            } finally {
                h.controller.close()
            }
        }
    })
