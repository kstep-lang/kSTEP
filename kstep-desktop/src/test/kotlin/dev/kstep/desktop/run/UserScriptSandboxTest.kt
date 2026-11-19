package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The state machine of the user-flow sandbox with an injected detector (ADR-0062); no process is started. */
class UserScriptSandboxTest :
    StringSpec({
        "the state is Unknown before the first flow and nothing is probed" {
            val calls = AtomicInteger()
            val box = UserScriptSandbox { calls.incrementAndGet().let { WorkerSandbox.Unavailable("x") } }
            box.state.value shouldBe UserSandboxState.Unknown
            calls.get() shouldBe 0
        }

        "an active macOS probe is rebound to the real path of each session folder" {
            val dir = TestSupport.newDir()
            try {
                val probe =
                    WorkerSandbox.MacSandboxExec(
                        WorkerSandbox.MAC_PROFILE,
                        Path.of("/probe"),
                        Path.of("/probe"),
                    )
                val box = UserScriptSandbox { probe }
                val bound = box.forSession(dir).shouldBeInstanceOf<WorkerSandbox.MacSandboxExec>()
                bound.session shouldBe dir.toRealPath()
                bound.tmp shouldBe dir.toRealPath()
                box.state.value.shouldBeInstanceOf<UserSandboxState.Active>()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "an active bubblewrap probe becomes hideTmp = false for the session" {
            val dir = TestSupport.newDir()
            try {
                val probe = WorkerSandbox.Bubblewrap(Path.of("/usr/bin/bwrap"), Path.of("/probe"), Path.of("/probe"))
                val bound = UserScriptSandbox { probe }.forSession(dir).shouldBeInstanceOf<WorkerSandbox.Bubblewrap>()
                bound.hideTmp shouldBe false
                bound.session shouldBe dir.toRealPath()
                bound.path shouldBe Path.of("/usr/bin/bwrap")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "an unavailable probe yields no sandbox and a state with the kind" {
            val dir = TestSupport.newDir()
            try {
                val box =
                    UserScriptSandbox {
                        WorkerSandbox.Unavailable("no bwrap", WorkerSandbox.UnavailableKind.BWRAP_MISSING)
                    }
                box.forSession(dir) shouldBe null
                box.state.value shouldBe
                    UserSandboxState.Unavailable(WorkerSandbox.UnavailableKind.BWRAP_MISSING, "no bwrap")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "the detector runs exactly once, also for eight parallel callers" {
            val dir = TestSupport.newDir()
            try {
                val calls = AtomicInteger()
                val box =
                    UserScriptSandbox {
                        calls.incrementAndGet()
                        Thread.sleep(200)
                        WorkerSandbox.Unavailable("x")
                    }
                val start = CountDownLatch(1)
                val threads =
                    (1..8).map {
                        thread {
                            start.await()
                            box.forSession(dir)
                        }
                    }
                start.countDown()
                threads.forEach { it.join() }
                calls.get() shouldBe 1
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a throwing detector becomes Unavailable(CHECK_FAILED)" {
            val box = UserScriptSandbox { throw IllegalStateException("boom") }
            val state = box.resolve().shouldBeInstanceOf<UserSandboxState.Unavailable>()
            state.kind shouldBe WorkerSandbox.UnavailableKind.CHECK_FAILED
        }

        "the production probe leaves no folder behind" {
            val before = tempProbeDirs()
            UserScriptSandbox.production().resolve()
            tempProbeDirs() shouldBe before
        }
    })

private fun tempProbeDirs(): Set<String> =
    Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { s ->
        s
            .map { it.fileName.toString() }
            .filter { it.startsWith("kstep-desktop-") }
            .toList()
            .toSet()
    }
