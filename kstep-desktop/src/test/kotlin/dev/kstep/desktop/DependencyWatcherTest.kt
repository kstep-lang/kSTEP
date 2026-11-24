package dev.kstep.desktop

import dev.kstep.desktop.watch.DependencyWatcher
import dev.kstep.desktop.watch.FileStamp
import dev.kstep.desktop.watch.Scheduler
import dev.kstep.desktop.watch.statNoFollow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** The multi-file poller of the input files of a script (ADR-0080), driven by a fake clock, scheduler and stat. */
class DependencyWatcherTest :
    StringSpec({
        val a = Path.of("/x/a.txt")
        val b = Path.of("/x/b.txt")

        class Fixture {
            var now = 0L
            val stamps = HashMap<Path, FileStamp?>(mapOf(a to FileStamp(1, 1, "a"), b to FileStamp(1, 1, "b")))
            val changes = AtomicInteger()
            private var task: (() -> Unit)? = null
            var closedHandle = false
            val watcher =
                DependencyWatcher(
                    pollMillis = 150,
                    quietMillis = 250,
                    clock = { now },
                    scheduler =
                        Scheduler { _, t ->
                            task = t
                            AutoCloseable {
                                task = null
                                closedHandle = true
                            }
                        },
                    stat = { stamps[it] },
                    onChange = { changes.incrementAndGet() },
                )

            fun tick(advanceMs: Long = 150) {
                now += advanceMs
                task?.invoke()
            }
        }

        "the first poll of a path only records its state" {
            val f = Fixture()
            f.watcher.replace(setOf(a, b))
            repeat(4) { f.tick() }
            f.changes.get() shouldBe 0
        }

        "a change gives exactly one callback after the quiet window, an unchanged file none" {
            val f = Fixture()
            f.watcher.replace(setOf(a))
            f.tick()
            f.stamps[a] = FileStamp(2, 2, "a")
            f.tick() // sees the change
            f.changes.get() shouldBe 0
            f.tick(100)
            f.changes.get() shouldBe 0
            f.tick(200)
            f.changes.get() shouldBe 1
            f.tick(2000)
            f.changes.get() shouldBe 1
        }

        "two files changing together give one callback" {
            val f = Fixture()
            f.watcher.replace(setOf(a, b))
            f.tick()
            f.stamps[a] = FileStamp(2, 2, "a")
            f.stamps[b] = null
            f.tick()
            f.tick(300)
            f.changes.get() shouldBe 1
            f.tick(1000)
            f.changes.get() shouldBe 1
        }

        "a vanished file gives one callback, then none, and its return gives one more" {
            val f = Fixture()
            f.watcher.replace(setOf(a))
            f.tick()
            f.stamps[a] = null
            f.tick()
            f.tick(300)
            f.changes.get() shouldBe 1
            f.tick(5000)
            f.changes.get() shouldBe 1
            f.stamps[a] = FileStamp(3, 3, "a2")
            f.tick()
            f.tick(300)
            f.changes.get() shouldBe 2
        }

        "replace keeps the baseline of staying paths, starts new ones as baseline, and forgets removed ones" {
            val f = Fixture()
            f.watcher.replace(setOf(a))
            f.tick()
            // a changes while a "run" is in flight; the set is replaced with the same path meanwhile
            f.stamps[a] = FileStamp(2, 2, "a")
            f.tick()
            f.stamps[b] = FileStamp(9, 9, "b2") // b was never watched before: whatever it is now is its baseline
            f.watcher.replace(setOf(a, b))
            f.tick(300)
            f.changes.get() shouldBe 1 // the change of a was not lost, b did not count
            f.tick(5000)
            f.changes.get() shouldBe 1
            // a removed path never reports
            f.watcher.replace(setOf(a))
            f.stamps[b] = null
            f.tick()
            f.tick(300)
            f.changes.get() shouldBe 1
        }

        "nothing is reported after close" {
            val f = Fixture()
            f.watcher.replace(setOf(a))
            f.tick()
            f.watcher.close()
            f.closedHandle shouldBe true
            f.stamps[a] = null
            f.tick()
            f.tick(300)
            f.changes.get() shouldBe 0
        }

        "a link counts as gone" {
            val dir = Files.createTempDirectory("dep-watch").toRealPath()
            try {
                val real = dir.resolve("real.txt").also { Files.writeString(it, "x") }
                (statNoFollow(real) != null) shouldBe true
                val linked =
                    try {
                        Files.createSymbolicLink(dir.resolve("link.txt"), real)
                        true
                    } catch (_: Exception) {
                        false
                    }
                if (linked) statNoFollow(dir.resolve("link.txt")) shouldBe null
                statNoFollow(dir) shouldBe null // a directory is no input file
                statNoFollow(dir.resolve("missing.txt")) shouldBe null
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
