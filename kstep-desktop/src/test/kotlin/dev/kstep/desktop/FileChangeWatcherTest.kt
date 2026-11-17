package dev.kstep.desktop

import dev.kstep.desktop.watch.FileChangeWatcher
import dev.kstep.desktop.watch.FileStamp
import dev.kstep.desktop.watch.Scheduler
import dev.kstep.desktop.watch.WatchSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class FileChangeWatcherTest :
    StringSpec({
        class Fixture {
            var now = 0L
            var stamp: FileStamp? = FileStamp(10, 1, "k1")
            val signals = CopyOnWriteArrayList<WatchSignal>()
            private var task: (() -> Unit)? = null
            val watcher =
                FileChangeWatcher(
                    Path.of("/x/a.kstep.kts"),
                    pollMillis = 150,
                    quietMillis = 250,
                    clock = { now },
                    scheduler =
                        Scheduler { _, t ->
                            task = t
                            AutoCloseable { task = null }
                        },
                    stat = { stamp },
                    onEvent = { signals += it },
                )

            fun tick(advanceMs: Long = 150) {
                now += advanceMs
                task?.invoke()
            }
        }

        "the first poll only records the state" {
            val f = Fixture()
            f.tick()
            f.tick()
            f.tick()
            f.signals.shouldBeEmpty()
        }

        "a change is reported once, after the quiet window" {
            val f = Fixture()
            f.tick()
            f.stamp = FileStamp(11, 2, "k1")
            f.tick() // sees the change, quiet window starts
            f.signals.shouldBeEmpty()
            f.tick(100) // 100 ms of 250
            f.signals.shouldBeEmpty()
            f.tick(200) // 300 ms
            f.signals shouldBe listOf(WatchSignal.CHANGED)
            f.tick(1000)
            f.signals shouldBe listOf(WatchSignal.CHANGED)
        }

        "five changes inside the window give exactly one CHANGED" {
            val f = Fixture()
            f.tick()
            for (i in 1..5) {
                f.stamp = FileStamp(10L + i, 2L + i, "k1")
                f.tick(100)
            }
            f.signals.shouldBeEmpty()
            f.tick(300)
            f.signals shouldBe listOf(WatchSignal.CHANGED)
        }

        "a replaced file (same size and time, new file key) is a change" {
            val f = Fixture()
            f.tick()
            f.stamp = FileStamp(10, 1, "k2")
            f.tick()
            f.tick(400)
            f.signals shouldBe listOf(WatchSignal.CHANGED)
        }

        "a missing file gives MISSING once, its return gives CHANGED" {
            val f = Fixture()
            f.tick()
            f.stamp = null
            f.tick()
            f.tick(300)
            f.signals shouldBe listOf(WatchSignal.MISSING)
            f.tick(1000)
            f.signals shouldBe listOf(WatchSignal.MISSING)
            f.stamp = FileStamp(5, 9, "k3")
            f.tick()
            f.tick(300)
            f.signals shouldBe listOf(WatchSignal.MISSING, WatchSignal.CHANGED)
        }

        "a closed watcher reports nothing".config(timeout = 30.seconds) {
            val f = Fixture()
            f.tick()
            f.watcher.close()
            f.stamp = null
            f.tick(1000)
            f.signals.shouldBeEmpty()
        }

        "a real file replaced atomically with the same size is detected".config(timeout = 60.seconds) {
            val dir = Files.createTempDirectory("d1-watch")
            try {
                val file = dir.resolve("a.kstep.kts")
                Files.writeString(file, "aaaa")
                val signals = CopyOnWriteArrayList<WatchSignal>()
                FileChangeWatcher(file, onEvent = { signals += it }).use {
                    Thread.sleep(600) // let the first poll record the state
                    val tmp = dir.resolve("a.tmp")
                    Files.writeString(tmp, "bbbb")
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    TestSupport.await(5_000) { signals.contains(WatchSignal.CHANGED) } shouldBe true
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
