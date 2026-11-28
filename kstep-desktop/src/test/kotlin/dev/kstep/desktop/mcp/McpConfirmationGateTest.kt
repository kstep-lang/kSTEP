package dev.kstep.desktop.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private fun req(
    id: Long,
    at: Long = 0,
) = McpConfirmRequest(id, McpFileAction.SAVE, "a", "b", "c", at)

private class GateRig(
    cooldown: Long = 10_000,
) {
    val scheduler = FakeConfirmScheduler()

    @Volatile var now = 0L
    val gate = McpConfirmationGate(scheduler, { now }, 60_000, cooldown)

    fun ask(): CompletableFuture<McpConfirmOutcome> =
        CompletableFuture.supplyAsync { gate.request { id -> req(id, now) } }

    fun waitPending(): McpConfirmRequest {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (gate.pending.value == null) {
            check(System.nanoTime() < end) { "no request appeared" }
            Thread.sleep(2)
        }
        return gate.pending.value!!
    }
}

class McpConfirmationGateTest :
    StringSpec({
        "the timeout is scheduled for 60 000 ms and firing it gives TimedOut and closes the dialog" {
            val rig = GateRig()
            val answer = rig.ask()
            rig.waitPending()
            rig.scheduler.delays.toList() shouldBe listOf(60_000L)
            rig.scheduler.fire()
            answer.get(30, TimeUnit.SECONDS) shouldBe McpConfirmOutcome.TimedOut
            rig.gate.pending.value shouldBe null
        }

        "allow and deny come back to the caller" {
            val rig = GateRig(cooldown = 0)
            val a = rig.ask()
            rig.gate.resolve(rig.waitPending().id, McpConfirmDecision.ALLOW)
            a.get(30, TimeUnit.SECONDS) shouldBe McpConfirmOutcome.Allowed
            val d = rig.ask()
            rig.gate.resolve(rig.waitPending().id, McpConfirmDecision.DENY)
            d.get(30, TimeUnit.SECONDS) shouldBe McpConfirmOutcome.Denied
            rig.gate.pending.value shouldBe null
        }

        "a second request while one is open is Busy and the first stays" {
            val rig = GateRig()
            val first = rig.ask()
            val open = rig.waitPending()
            rig.gate.request { id -> req(id) } shouldBe McpConfirmOutcome.Busy
            rig.gate.pending.value!!
                .id shouldBe open.id
            rig.gate.resolve(open.id, McpConfirmDecision.ALLOW)
            first.get(30, TimeUnit.SECONDS) shouldBe McpConfirmOutcome.Allowed
        }

        "a wrong or old id is ignored" {
            val rig = GateRig()
            val a = rig.ask()
            val open = rig.waitPending()
            rig.gate.resolve(open.id + 100, McpConfirmDecision.ALLOW)
            rig.gate.pending.value!!
                .id shouldBe open.id
            rig.gate.resolve(open.id, McpConfirmDecision.DENY)
            a.get(30, TimeUnit.SECONDS) shouldBe McpConfirmOutcome.Denied
            rig.gate.resolve(open.id, McpConfirmDecision.ALLOW)
            rig.gate.pending.value shouldBe null
        }

        "cancelAll ends the request with the code" {
            val rig = GateRig()
            val a = rig.ask()
            rig.waitPending()
            rig.gate.cancelAll(McpToolCodes.FILES_DISABLED)
            val outcome = a.get(30, TimeUnit.SECONDS)
            outcome.shouldBeInstanceOf<McpConfirmOutcome.Cancelled>().code shouldBe McpToolCodes.FILES_DISABLED
            rig.gate.pending.value shouldBe null
        }

        "after a denial the next request waits for the cool-down; a timeout or allow does not start one" {
            val rig = GateRig()
            val a = rig.ask()
            rig.gate.resolve(rig.waitPending().id, McpConfirmDecision.DENY)
            a.get(30, TimeUnit.SECONDS) shouldBe McpConfirmOutcome.Denied
            rig.now = 4_000
            val cool = rig.gate.request { id -> req(id) }
            cool.shouldBeInstanceOf<McpConfirmOutcome.CoolingDown>().retryAfterMs shouldBe 6_000L
            rig.now = 10_000
            val b = rig.ask()
            rig.gate.resolve(rig.waitPending().id, McpConfirmDecision.ALLOW)
            b.get(30, TimeUnit.SECONDS) shouldBe McpConfirmOutcome.Allowed
            val c = rig.ask()
            rig.gate.resolve(rig.waitPending().id, McpConfirmDecision.ALLOW)
            c.get(30, TimeUnit.SECONDS) shouldBe McpConfirmOutcome.Allowed
        }

        "display text makes control and bidi characters visible and cuts on code points" {
            McpDisplayText.forDialog("a\u202Eb", 80) shouldBe "a\\u{202E}b"
            McpDisplayText.forDialog("x\u0007", 80) shouldBe "x\\u{0007}"
            McpDisplayText.forDialog("abcdef", 3) shouldBe "abc…"
            McpDisplayText.leftClip("/aaaa/bbbb", 6) shouldBe "…/bbbb"
            McpDisplayText.forDialog("\uD83D\uDE00\uD83D\uDE00", 1) shouldBe "\uD83D\uDE00…"
            McpDisplayText.forDialog("lone\uD800", 80) shouldBe "lone\\u{D800}"
        }
    })
