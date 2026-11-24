package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.watch.DependencyWatch
import dev.kstep.desktop.watch.DependencyWatcher
import dev.kstep.desktop.watch.FileStamp
import dev.kstep.desktop.watch.Scheduler
import dev.kstep.desktop.watch.WatchSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** The controller's handling of the input files a run reports (ADR-0080), with a fake runner and fake watches. */
class LiveRunDependenciesTest :
    StringSpec({
        val file = Path.of("/tmp/never-read.kstep.kts")
        val other = Path.of("/tmp/other.kstep.kts")
        val a = Path.of("/tmp/a.txt")
        val b = Path.of("/tmp/b.txt")

        fun model(generation: Long) = DesktopModel(null, 0, generation, Instant.EPOCH, 1, emptyList())

        class FakeWatch : DependencyWatch {
            val sets = CopyOnWriteArrayList<Set<Path>>()
            val closed = AtomicBoolean(false)

            override fun replace(paths: Set<Path>) {
                sets += paths
            }

            override fun close() {
                closed.set(true)
            }
        }

        class Harness(
            val reports: (Long) -> List<Path>? = { null },
        ) {
            val started = CopyOnWriteArrayList<Long>()
            val gates = HashMap<Long, CountDownLatch>()
            val watches = CopyOnWriteArrayList<FakeWatch>()
            val callbacks = CopyOnWriteArrayList<() -> Unit>()
            var signal: (WatchSignal) -> Unit = {}
            val diskSignals = CopyOnWriteArrayList<WatchSignal>()
            val filterCalls = CopyOnWriteArrayList<WatchSignal>()

            @Synchronized
            fun gate(gen: Long): CountDownLatch = gates.getOrPut(gen) { CountDownLatch(1) }

            val controller =
                LiveRunController(
                    runner = { _, gen, _ ->
                        started += gen
                        gate(gen).await(30, TimeUnit.SECONDS)
                        RunResult.Success(model(gen), reports(gen))
                    },
                    watcherFactory = { _, onEvent ->
                        signal = onEvent
                        AutoCloseable { }
                    },
                    dependencyWatchFactory = { callback ->
                        callbacks += callback
                        FakeWatch().also { watches += it }
                    },
                ).also {
                    it.onDiskSignal = { s -> diskSignals += s }
                    it.runFilter = { s ->
                        filterCalls += s
                        true
                    }
                }

            fun finish(gen: Long) {
                gate(gen).countDown()
            }
        }

        "a change of an input starts a run and reaches neither the editor nor the run filter".config(
            timeout = 60.seconds,
        ) {
            val h = Harness()
            try {
                h.controller.open(file)
                TestSupport.await(10_000) { h.started.contains(1L) } shouldBe true
                h.finish(1)
                TestSupport.awaitState(h.controller, 10_000) { it is DesktopState.Ok }
                h.callbacks.single().invoke()
                TestSupport.await(10_000) { h.started.contains(2L) } shouldBe true
                h.diskSignals.toList() shouldBe emptyList()
                h.filterCalls.toList() shouldBe emptyList()
                h.finish(2)
            } finally {
                h.controller.close()
            }
        }

        "the watched set follows each report, ignores runs without one, and filters outsiders and the script".config(
            timeout = 60.seconds,
        ) {
            val h =
                Harness { gen ->
                    when (gen) {
                        1L -> listOf(a, file, Path.of("/elsewhere/c.txt"))
                        2L -> null
                        else -> listOf(b)
                    }
                }
            try {
                h.controller.open(file)
                TestSupport.await(10_000) { h.started.contains(1L) } shouldBe true
                h.finish(1)
                TestSupport.awaitState(h.controller, 10_000) { it is DesktopState.Ok }
                h.watches
                    .single()
                    .sets
                    .toList() shouldBe listOf(setOf(a))
                h.signal(WatchSignal.CHANGED)
                TestSupport.await(10_000) { h.started.contains(2L) } shouldBe true
                h.finish(2)
                TestSupport.awaitState(h.controller, 10_000) { it.generation == 2L && it is DesktopState.Ok }
                h.watches
                    .single()
                    .sets
                    .toList() shouldBe listOf(setOf(a)) // no report: the old set stays
                h.signal(WatchSignal.CHANGED)
                TestSupport.await(10_000) { h.started.contains(3L) } shouldBe true
                h.finish(3)
                TestSupport.awaitState(h.controller, 10_000) { it.generation == 3L && it is DesktopState.Ok }
                h.watches
                    .single()
                    .sets
                    .toList() shouldBe listOf(setOf(a), setOf(b))
            } finally {
                h.controller.close()
            }
        }

        "the report of an outdated generation installs nothing".config(timeout = 60.seconds) {
            val h = Harness { gen -> if (gen == 1L) listOf(a) else listOf(b) }
            try {
                h.controller.open(file)
                TestSupport.await(10_000) { h.started.contains(1L) } shouldBe true
                h.signal(WatchSignal.CHANGED) // generation 2 queued behind the first
                h.finish(1) // the late report of generation 1
                TestSupport.await(10_000) { h.started.contains(2L) } shouldBe true
                h.finish(2)
                TestSupport.awaitState(h.controller, 10_000) { it.generation == 2L && it is DesktopState.Ok }
                h.watches
                    .single()
                    .sets
                    .toList() shouldBe listOf(setOf(b))
            } finally {
                h.controller.close()
            }
        }

        "opening another file, clear and close end the observation, and an old callback is ignored".config(
            timeout = 60.seconds,
        ) {
            val h = Harness { listOf(a) }
            try {
                h.controller.open(file)
                TestSupport.await(10_000) { h.started.contains(1L) } shouldBe true
                h.finish(1)
                TestSupport.awaitState(h.controller, 10_000) { it is DesktopState.Ok }
                val oldCallback = h.callbacks.single()
                h.controller.open(other)
                h.watches
                    .first()
                    .closed
                    .get() shouldBe true
                h.watches.size shouldBe 2
                TestSupport.await(10_000) { h.started.contains(2L) } shouldBe true
                oldCallback.invoke() // a late callback of the first watcher
                Thread.sleep(300)
                h.started.toList() shouldBe listOf(1L, 2L)
                h.finish(2)
                h.controller.clear()
                h.watches
                    .last()
                    .closed
                    .get() shouldBe true
                h.callbacks[h.callbacks.size - 1].invoke() // after clear nothing runs
                Thread.sleep(300)
                h.started.toList() shouldBe listOf(1L, 2L)
            } finally {
                h.controller.close()
            }
            h.watches.all { it.closed.get() } shouldBe true
        }

        "a vanished input runs once and does not loop (MISSING, same report, no further run)".config(
            timeout = 60.seconds,
        ) {
            var now = 0L
            var stamp: FileStamp? = FileStamp(1, 1, "k")
            var task: (() -> Unit)? = null
            val started = CopyOnWriteArrayList<Long>()
            val gates = HashMap<Long, CountDownLatch>()

            fun gate(g: Long): CountDownLatch = synchronized(gates) { gates.getOrPut(g) { CountDownLatch(1) } }
            val controller =
                LiveRunController(
                    runner = { _, gen, _ ->
                        started += gen
                        gate(gen).await(30, TimeUnit.SECONDS)
                        RunResult.Success(model(gen), listOf(a)) // always names the same input, even when it is gone
                    },
                    watcherFactory = { _, _ -> AutoCloseable { } },
                    dependencyWatchFactory = { callback ->
                        DependencyWatcher(
                            clock = { now },
                            scheduler =
                                Scheduler { _, t ->
                                    task = t
                                    AutoCloseable { task = null }
                                },
                            stat = { stamp },
                            onChange = callback,
                        )
                    },
                )
            try {
                controller.open(file)
                TestSupport.await(10_000) { started.contains(1L) } shouldBe true
                gate(1).countDown()
                TestSupport.awaitState(controller, 10_000) { it is DesktopState.Ok }

                fun tick(ms: Long) {
                    now += ms
                    task?.invoke()
                }
                tick(150) // baseline
                stamp = null
                tick(150) // sees it gone
                tick(300) // quiet window over: one callback
                TestSupport.await(10_000) { started.contains(2L) } shouldBe true
                gate(2).countDown()
                TestSupport.awaitState(controller, 10_000) { it.generation == 2L && it is DesktopState.Ok }
                repeat(10) { tick(500) }
                Thread.sleep(300)
                started.toList() shouldBe listOf(1L, 2L)
                stamp = FileStamp(5, 5, "k2") // the file comes back: one more run
                tick(150)
                tick(300)
                TestSupport.await(10_000) { started.contains(3L) } shouldBe true
                gate(3).countDown()
            } finally {
                controller.close()
            }
        }
    })
