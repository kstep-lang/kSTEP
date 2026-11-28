package dev.kstep.desktop.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class McpActivityTrackerTest :
    StringSpec({
        "it starts ready and empty" {
            McpActivityTracker().state.value shouldBe McpActivity()
        }

        "a call is busy until it ends, then ready, and counted once" {
            val tracker = McpActivityTracker { 5L }
            val id = tracker.begin("kstep_desktop_get_status")
            tracker.state.value.phase shouldBe McpPhase.Busy
            tracker.state.value.running shouldBe 1
            tracker.end(id, McpCallOutcome.Ok)
            tracker.end(id, McpCallOutcome.Ok)
            val s = tracker.state.value
            s.phase shouldBe McpPhase.Ready
            s.running shouldBe 0
            s.finished shouldBe 1L
            s.recent.single().outcome shouldBe McpCallOutcome.Ok
            s.recent.single().startedAtMs shouldBe 5L
        }

        "two parallel calls keep it busy until both end" {
            val tracker = McpActivityTracker()
            val a = tracker.begin("a")
            val b = tracker.begin("b")
            tracker.end(a, McpCallOutcome.Ok)
            tracker.state.value.phase shouldBe McpPhase.Busy
            tracker.end(b, McpCallOutcome.Ok)
            tracker.state.value.phase shouldBe McpPhase.Ready
        }

        "a failed call shows failed until the next success; declined is not a failure and changes nothing" {
            val tracker = McpActivityTracker()
            tracker.end(tracker.begin("x"), McpCallOutcome.Failed)
            tracker.state.value.phase shouldBe McpPhase.Failed
            tracker.end(tracker.begin("x"), McpCallOutcome.Declined)
            tracker.state.value.phase shouldBe McpPhase.Failed
            tracker.end(tracker.begin("x"), McpCallOutcome.Ok)
            tracker.state.value.phase shouldBe McpPhase.Ready
            val fresh = McpActivityTracker()
            fresh.end(fresh.begin("x"), McpCallOutcome.Declined)
            fresh.state.value.phase shouldBe McpPhase.Ready
        }

        "discard removes the call without counting it" {
            val tracker = McpActivityTracker()
            tracker.discard(tracker.begin("x"))
            tracker.state.value shouldBe McpActivity()
        }

        "only the five newest calls are kept, newest first" {
            val tracker = McpActivityTracker()
            repeat(8) { tracker.end(tracker.begin("call $it"), McpCallOutcome.Ok) }
            tracker.state.value.recent
                .map { it.label } shouldBe (7 downTo 3).map { "call $it" }
            tracker.state.value.finished shouldBe 8L
        }

        "reset empties the state, keeps the count, and a late end changes nothing and never goes negative" {
            val tracker = McpActivityTracker()
            tracker.end(tracker.begin("done"), McpCallOutcome.Failed)
            val late = tracker.begin("late")
            tracker.reset()
            tracker.state.value.phase shouldBe McpPhase.Ready
            tracker.state.value.recent shouldBe emptyList()
            tracker.end(late, McpCallOutcome.Failed)
            tracker.discard(late)
            val s = tracker.state.value
            s.running shouldBe 0
            s.recent shouldBe emptyList()
            s.phase shouldBe McpPhase.Ready
            s.finished shouldBe 1L
        }

        "sanitize turns control characters into single spaces and trims" {
            McpActivityTracker.sanitize("  a\u0000\n\tb‮  c ") shouldBe "a b‮ c"
        }

        "sanitize cuts at 48 characters with an ellipsis and never splits a surrogate pair" {
            val long = "😀".repeat(100)
            val cut = McpActivityTracker.sanitize(long)
            cut.codePointCount(0, cut.length) shouldBe 48
            cut shouldEndWith "…"
            cut.dropLast(1) shouldBe "😀".repeat(47)
            McpActivityTracker.sanitize("a".repeat(48)) shouldBe "a".repeat(48)
        }

        "eight threads can begin and end calls at once and the counts are exact" {
            val tracker = McpActivityTracker()
            val done = CountDownLatch(8)
            repeat(8) {
                thread {
                    repeat(1000) { tracker.end(tracker.begin("t"), McpCallOutcome.Ok) }
                    done.countDown()
                }
            }
            done.await(60, TimeUnit.SECONDS) shouldBe true
            tracker.state.value.finished shouldBe 8000L
            tracker.state.value.running shouldBe 0
            tracker.state.value.phase shouldBe McpPhase.Ready
        }
    })
