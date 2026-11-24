package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertIssue
import dev.kstep.cli.ConvertOutcome
import dev.kstep.cli.ConvertTarget
import dev.kstep.cli.ConvertedFile
import dev.kstep.cli.DesktopConvertRequest
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The controller with a fake runner: states, the conflict flow and the one-run rule, without touching a disk. */
class ConvertControllerTest :
    StringSpec({
        val source: Path = Path.of("/tmp/kstep-convert-source")
        val request = ConvertRequest(source, ConvertTarget.KTS, null, recursive = true)

        fun file(name: String) = ConvertedFile(name, "a.md", null, null, null)

        fun success(vararg names: String) = ConvertOutcome.Success(names.map(::file), emptyList(), emptyList())

        fun conflict(names: List<String>) =
            ConvertOutcome.Failure(
                listOf(ConvertIssue("KSTEP-C-008", "target_exists", "exists", null)),
                names,
                emptyList(),
                emptyList(),
                emptyList(),
            )

        fun failure(written: List<ConvertedFile> = emptyList()) =
            ConvertOutcome.Failure(
                listOf(ConvertIssue("KSTEP-C-010", "write_failed", "x: disk", "a.md")),
                emptyList(),
                written,
                emptyList(),
                emptyList(),
            )

        class Harness(
            val script: (DesktopConvertRequest) -> ConvertOutcome,
        ) {
            val calls = CopyOnWriteArrayList<DesktopConvertRequest>()
            val done = CopyOnWriteArrayList<ConvertRequest>()
            val controller =
                ConvertController(
                    runner = { r ->
                        calls += r
                        script(r)
                    },
                    onDone = { done += it },
                )

            fun await(predicate: (ConvertState) -> Boolean): ConvertState {
                check(TestSupport.await(20_000) { predicate(controller.state.value) }) {
                    "unexpected state ${controller.state.value}"
                }
                return controller.state.value
            }

            fun toConflict(): ConvertState.Conflict {
                controller.begin(request)
                controller.start()
                return await { it is ConvertState.Conflict } as ConvertState.Conflict
            }
        }

        fun with(
            script: (DesktopConvertRequest) -> ConvertOutcome,
            body: (Harness) -> Unit,
        ) {
            val h = Harness(script)
            try {
                body(h)
            } finally {
                h.controller.close()
            }
        }

        "idle, begin, start, running, done and onDone" {
            val latch = CountDownLatch(1)
            with({
                latch.await(10, TimeUnit.SECONDS)
                success("a.kstep.kts")
            }) { h ->
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Idle>()
                h.controller.begin(request)
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Editing>()
                    .request shouldBe request
                h.controller.start()
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Running>()
                    .replacing shouldBe false
                latch.countDown()
                val done = h.await { it is ConvertState.Done } as ConvertState.Done
                done.written.map { it.name } shouldBe listOf("a.kstep.kts")
                TestSupport.await(5_000) { h.done.size == 1 } shouldBe true
                h.done.single() shouldBe request
                h.calls.single().force shouldBe false
            }
        }

        "start without a source is ignored" {
            with({ success() }) { h ->
                h.controller.begin(request.copy(source = null))
                h.controller.start()
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Editing>()
                h.calls.size shouldBe 0
            }
        }

        "begin, edit, start and replace during a run are ignored, one runner call in flight" {
            val latch = CountDownLatch(1)
            with({
                latch.await(10, TimeUnit.SECONDS)
                success("a.kstep.kts")
            }) { h ->
                h.controller.begin(request)
                h.controller.start()
                h.controller.start()
                h.controller.begin(request.copy(recursive = false))
                h.controller.edit(request.copy(recursive = false))
                h.controller.replace()
                h.controller.back()
                h.controller.closeDialog()
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Running>()
                latch.countDown()
                h.await { it is ConvertState.Done }
                h.calls.size shouldBe 1
            }
        }

        "a failure keeps what was written" {
            with({ failure(listOf(file("w.kstep.kts"))) }) { h ->
                h.controller.begin(request)
                h.controller.start()
                val failed = h.await { it is ConvertState.Failed } as ConvertState.Failed
                failed.written.map { it.name } shouldBe listOf("w.kstep.kts")
                failed.errors.single().code shouldBe "KSTEP-C-010"
                h.done.size shouldBe 0
            }
        }

        "an exception becomes KSTEP-D-036 without a path" {
            with({ throw IllegalStateException("/secret/path/file.md") }) { h ->
                h.controller.begin(request)
                h.controller.start()
                val failed = h.await { it is ConvertState.Failed } as ConvertState.Failed
                failed.errors.single().code shouldBe DesktopErrorCodes.CONVERT_FAILED
                failed.errors.single().message shouldNotContain "secret"
            }
        }

        "a stale result is discarded" {
            val latch = CountDownLatch(1)
            with({
                latch.await(10, TimeUnit.SECONDS)
                success("a.kstep.kts")
            }) { h ->
                h.controller.begin(request)
                h.controller.start()
                // the dialog cannot be left while running, so force a newer generation through close
                h.controller.close()
                latch.countDown()
                Thread.sleep(300)
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Running>()
                h.done.size shouldBe 0
            }
        }

        "after close nothing starts" {
            with({ success("a.kstep.kts") }) { h ->
                h.controller.begin(request)
                h.controller.close()
                h.controller.start()
                h.calls.size shouldBe 0
            }
        }

        "the first run never forces, 120 conflicts show 50 and 70 more" {
            val names = (1..120).map { "n$it.kstep.kts" }
            with({ conflict(names) }) { h ->
                val c = h.toConflict()
                c.shown.size shouldBe 50
                c.more shouldBe 70
                c.allConflicts shouldBe 120
                c.request shouldBe request
                h.calls.map { it.force } shouldBe listOf(false)
            }
        }

        "replace re-checks then forces, and only from a conflict" {
            val names = listOf("a.kstep.kts", "b.kstep.kts")
            with({ r -> if (r.force) success("a.kstep.kts", "b.kstep.kts") else conflict(names) }) { h ->
                h.controller.replace()
                h.calls.size shouldBe 0
                h.toConflict()
                h.controller.replace()
                h.await { it is ConvertState.Done }
                h.calls.map { it.force } shouldBe listOf(false, false, true)
                h.done.size shouldBe 1
            }
        }

        "a double replace gives one force run" {
            val names = listOf("a.kstep.kts")
            val latch = CountDownLatch(1)
            with({ r ->
                if (r.force) {
                    latch.await(10, TimeUnit.SECONDS)
                    success("a.kstep.kts")
                } else {
                    conflict(names)
                }
            }) { h ->
                h.toConflict()
                h.controller.replace()
                h.controller.replace()
                latch.countDown()
                h.await { it is ConvertState.Done }
                h.calls.count { it.force } shouldBe 1
            }
        }

        "a different conflict set on re-check gives a new conflict and no force run" {
            var round = 0
            with({
                round++
                if (round == 1) conflict(listOf("a.kstep.kts")) else conflict(listOf("a.kstep.kts", "new.kstep.kts"))
            }) { h ->
                h.toConflict()
                h.controller.replace()
                val c = h.await { it is ConvertState.Conflict && it.allConflicts == 2 } as ConvertState.Conflict
                c.shown shouldBe listOf("a.kstep.kts", "new.kstep.kts")
                h.calls.none { it.force } shouldBe true
            }
        }

        "conflicts that vanished mean the re-check already wrote: done without force" {
            var round = 0
            with({
                round++
                if (round == 1) conflict(listOf("a.kstep.kts")) else success("a.kstep.kts")
            }) { h ->
                h.toConflict()
                h.controller.replace()
                h.await { it is ConvertState.Done }
                h.calls.none { it.force } shouldBe true
            }
        }

        "edit after a conflict goes back to editing and the next start does not force" {
            with({ conflict(listOf("a.kstep.kts")) }) { h ->
                h.toConflict()
                h.controller.edit(request.copy(recursive = false))
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Editing>()
                h.controller.replace()
                h.calls.size shouldBe 1
                h.controller.start()
                h.await { it is ConvertState.Conflict }
                h.calls.map { it.force } shouldBe listOf(false, false)
            }
        }

        "a target that appeared during writing is a failure, not a conflict" {
            val out =
                ConvertOutcome.Failure(
                    listOf(ConvertIssue("KSTEP-C-008", "target_exists", "b appeared meanwhile", "a.md")),
                    emptyList(),
                    listOf(file("a.kstep.kts")),
                    emptyList(),
                    emptyList(),
                )
            with({ out }) { h ->
                h.controller.begin(request)
                h.controller.start()
                val failed = h.await { it is ConvertState.Failed } as ConvertState.Failed
                failed.written.size shouldBe 1
            }
        }

        "back from a result returns to editing, closeDialog goes idle" {
            with({ success("a.kstep.kts") }) { h ->
                h.controller.begin(request)
                h.controller.start()
                h.await { it is ConvertState.Done }
                h.controller.back()
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Editing>()
                h.controller.closeDialog()
                h.controller.state.value
                    .shouldBeInstanceOf<ConvertState.Idle>()
            }
        }
    })
