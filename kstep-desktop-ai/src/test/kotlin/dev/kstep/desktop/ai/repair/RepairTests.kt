package dev.kstep.desktop.ai.repair

import dev.kstep.desktop.ai.AiErrorCodes
import dev.kstep.desktop.ai.extract.Diagnostic
import dev.kstep.desktop.ai.llm.FailureKind
import dev.kstep.desktop.ai.llm.LlmClient
import dev.kstep.desktop.ai.llm.LlmReply
import dev.kstep.desktop.ai.llm.LlmRequest
import dev.kstep.desktop.ai.prompt.PromptBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

private val answer = LlmReply.Text("```kotlin\nval a = 1\n```", 5)
private val bad = CompileOutcome.Errors(listOf(Diagnostic("KSTEP-S-003", 14, 7, "unresolved reference: foo")))

class RepairMachineTest :
    FunSpec({
        val limits = AiLimits()
        val m = RepairMachine(limits, PromptBuilder(limits, "GUIDE"))

        fun start() = m.reduce(RepairState.Idle, RepairEvent.Start(1, "make a box", "old", true, 0))

        fun answer(
            s: RepairState,
            reply: LlmReply = answer,
            now: Long = 1,
        ) = m.reduce(s, RepairEvent.LlmAnswered(1, reply, now))

        fun compiled(
            s: RepairState,
            o: CompileOutcome,
            now: Long = 2,
        ) = m.reduce(s, RepairEvent.Compiled(1, o, now))

        test("happy path: compiles in the first attempt") {
            val (s1, e1) = start()
            e1.single().shouldBeInstanceOf<RepairEffect.CallLlm>()
            val (s2, e2) = answer(s1)
            e2.single().shouldBeInstanceOf<RepairEffect.Compile>()
            val (s3, _) = compiled(s2, CompileOutcome.Ok)
            (s3 as RepairState.Proposal).verdict shouldBe Verdict.COMPILES
            s3.script shouldBe "val a = 1"
            s3.baseText shouldBe "old"
        }

        test("repair succeeds in the third attempt; the repair request carries the errors, not the history") {
            var s: RepairState = start().first
            repeat(2) {
                s = answer(s).first
                val (n, eff) = compiled(s, bad)
                s = n
                val req = (eff.single() as RepairEffect.CallLlm).request
                req.messages.size shouldBe 1
                req.messages.single().text shouldContain "KSTEP-S-003 14:7"
            }
            s = answer(s).first
            (compiled(s, CompileOutcome.Ok).first as RepairState.Proposal).attemptsUsed shouldBe 3
        }

        test("four failures end as DOES_NOT_COMPILE after exactly four model calls") {
            var s: RepairState = start().first
            var calls = 1
            while (s !is RepairState.Proposal) {
                s = answer(s).first
                val (n, eff) = compiled(s, bad)
                s = n
                calls += eff.count { it is RepairEffect.CallLlm }
            }
            calls shouldBe 4
            (s as RepairState.Proposal).verdict shouldBe Verdict.DOES_NOT_COMPILE
            s.attempts.size shouldBe 4
        }

        test("unverifiable ends at once without a second call") {
            val s = answer(start().first).first
            val (n, eff) = compiled(s, CompileOutcome.Unverifiable("no occt"))
            (n as RepairState.Proposal).verdict shouldBe Verdict.NOT_VERIFIED
            eff shouldBe emptyList()
        }

        test("unverifiable because of a missing sandbox is a not-checked proposal with the reason") {
            val s = answer(start().first).first
            val (n, eff) =
                compiled(s, CompileOutcome.Unverifiable("no sandbox", NotVerifiedReason.SANDBOX_UNAVAILABLE))
            val p = n as RepairState.Proposal
            p.verdict shouldBe Verdict.NOT_VERIFIED
            p.notVerifiedReason shouldBe NotVerifiedReason.SANDBOX_UNAVAILABLE
            p.attempts.last().text shouldBe "not checked (no worker sandbox on this system)"
            eff shouldBe emptyList()
        }

        test("a refused check ends the run as failed without a second model call") {
            val s = answer(start().first).first
            val (n, eff) = compiled(s, CompileOutcome.Refused("KSTEP-D-030", "cannot start the worker sandbox"))
            (n as RepairState.Failed).code shouldBe "KSTEP-D-030"
            eff shouldBe emptyList()
        }

        test("stop in Requesting and Compiling kills the worker; late answers are dropped") {
            val s1 = start().first
            val (st, eff) = m.reduce(s1, RepairEvent.Stop)
            st shouldBe RepairState.Stopped
            eff shouldBe listOf(RepairEffect.KillWorker)
            answer(st).first shouldBe RepairState.Stopped
            val s2 = answer(s1).first
            m.reduce(s2, RepairEvent.Stop).first shouldBe RepairState.Stopped
            compiled(RepairState.Stopped, CompileOutcome.Ok).first shouldBe RepairState.Stopped
        }

        test("an answer of an older run is ignored") {
            val s1 = start().first
            m.reduce(s1, RepairEvent.LlmAnswered(99, answer, 1)).first shouldBe s1
        }

        test("total time over 180 s fails with D-017") {
            val s1 = start().first
            val (f, eff) = m.reduce(s1, RepairEvent.Tick(180_001))
            (f as RepairState.Failed).code shouldBe AiErrorCodes.AI_LIMIT_REACHED
            eff shouldBe listOf(RepairEffect.KillWorker)
            (answer(s1, now = 200_000).first as RepairState.Failed).code shouldBe AiErrorCodes.AI_LIMIT_REACHED
        }

        test("a rejected key fails with D-015 and no retry") {
            val (f, eff) = answer(start().first, LlmReply.Failed(FailureKind.KEY_REJECTED, 401))
            (f as RepairState.Failed).code shouldBe AiErrorCodes.AI_PROVIDER_REJECTED
            eff shouldBe emptyList()
            (
                (
                    answer(
                        start().first,
                        LlmReply.Failed(FailureKind.RATE_LIMITED, 429),
                    )
                ).first as RepairState.Failed
            ).message shouldContain
                "Rate"
            (
                (
                    answer(
                        start().first,
                        LlmReply.Failed(FailureKind.UNREACHABLE),
                    )
                ).first as RepairState.Failed
            ).code shouldBe
                AiErrorCodes.AI_PROVIDER_UNREACHABLE
        }

        test("an answer without a code block counts as an attempt and triggers a repair") {
            val (s, eff) = answer(start().first, LlmReply.Text("sorry, no code", null))
            (s as RepairState.Requesting).attempt shouldBe 2
            (eff.single() as RepairEffect.CallLlm)
                .request.messages
                .single()
                .text shouldContain "No valid script"
        }

        test("without includeScript the request carries no script") {
            val (_, e) = m.reduce(RepairState.Idle, RepairEvent.Start(1, "p", "SECRET-BASE", false, 0))
            (e.single() as RepairEffect.CallLlm)
                .request.messages
                .single()
                .text shouldNotContain "SECRET-BASE"
        }

        test("Start clears the attempt list") {
            var s: RepairState = start().first
            s = answer(s).first
            s = compiled(s, bad).first
            (s as RepairState.Requesting).attempts.size shouldBe 1
            (m.reduce(s, RepairEvent.Start(2, "p", "b", false, 5)).first as RepairState.Requesting).attempts shouldBe
                emptyList()
        }
    })

class FakeLlm(
    private val replies: MutableList<LlmReply>,
    private val block: CompletableDeferred<Unit>? = null,
) : LlmClient {
    val requests = CopyOnWriteArrayList<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmReply {
        requests += request
        block?.await()
        return if (replies.isEmpty()) answer else replies.removeAt(0)
    }
}

class RepairSessionTest :
    FunSpec({
        val limits = AiLimits()

        fun session(
            client: LlmClient?,
            compiler: CandidateCompiler = CandidateCompiler { _, _ -> CompileOutcome.Ok },
            consent: Boolean = true,
            scope: CoroutineScope,
            lim: AiLimits = limits,
        ) = RepairSession(RepairMachine(lim, PromptBuilder(lim, "G")), lim, { client }, compiler, { consent }, scope)

        suspend fun RepairSession.settle() =
            withTimeout(60_000) {
                while (state.value.let {
                        it !is RepairState.Proposal &&
                            it !is RepairState.Failed &&
                            it !is RepairState.Stopped
                    }
                ) {
                    delay(10)
                }
            }

        test("without consent nothing is sent") {
            val fake = FakeLlm(mutableListOf())
            val s = session(fake, consent = false, scope = CoroutineScope(Dispatchers.Default))
            s.generate("p", "b", true) shouldBe GenerateResult.NoConsent
            fake.requests.size shouldBe 0
        }

        test("without a key nothing is sent") {
            val s = session(null, scope = CoroutineScope(Dispatchers.Default))
            s.generate("p", "b", true) shouldBe GenerateResult.NoKey
        }

        test("invalid prompts are not allowed") {
            val s = session(FakeLlm(mutableListOf()), scope = CoroutineScope(Dispatchers.Default))
            s.generate("   ", "b", false) shouldBe GenerateResult.NotAllowed
            s.generate("x".repeat(4001), "b", false) shouldBe GenerateResult.NotAllowed
            s.generate("p", "x".repeat(128 * 1024 + 1), true) shouldBe GenerateResult.NotAllowed
        }

        test("with consent: the script is sent only on request, and never a key, path or candidate name") {
            val fake = FakeLlm(mutableListOf())
            val s = session(fake, scope = CoroutineScope(Dispatchers.Default))
            runBlocking {
                s.generate("make a box", "BASE-TEXT", false) shouldBe GenerateResult.Started
                s.settle()
                (s.state.value as RepairState.Proposal).verdict shouldBe Verdict.COMPILES
                fake.requests
                    .single()
                    .messages
                    .single()
                    .text shouldNotContain "BASE-TEXT"
                s.generate("make a box", "BASE-TEXT", true)
                s.settle()
                fake.requests
                    .last()
                    .messages
                    .single()
                    .text shouldContain "BASE-TEXT"
            }
            fake.requests.forEach {
                (it.system + it.messages.joinToString { m -> m.text }) shouldNotContain "candidate-"
            }
            s.close()
        }

        test("stop kills the worker process and leaves no proposal") {
            val started = CompletableDeferred<Process>()
            val block = CompletableDeferred<Unit>()
            val compiler =
                CandidateCompiler { _, onStarted ->
                    val p = ProcessBuilder("sleep", "60").start()
                    onStarted(p)
                    started.complete(p)
                    p.waitFor()
                    CompileOutcome.Ok
                }
            val s = session(FakeLlm(mutableListOf()), compiler, scope = CoroutineScope(Dispatchers.Default))
            runBlocking {
                s.generate("p", "b", false)
                val p = withTimeout(60_000) { started.await() }
                p.isAlive shouldBe true
                s.stop()
                withTimeout(60_000) { while (p.isAlive) delay(20) }
                s.state.value shouldBe RepairState.Stopped
                block.complete(Unit)
            }
        }

        test("a blocked model call is cut by the per-call timeout") {
            val lim = limits.copy(perCallMillis = 100, totalMillis = 60_000)
            val fake = FakeLlm(mutableListOf(), CompletableDeferred())
            val s = session(fake, scope = CoroutineScope(Dispatchers.Default), lim = lim)
            runBlocking {
                s.generate("p", "b", false)
                s.settle()
                (s.state.value as RepairState.Failed).code shouldBe AiErrorCodes.AI_PROVIDER_UNREACHABLE
            }
        }

        test("checkSelection refuses a stale proposal, Stop returns to the proposal, a late result is dropped") {
            runBlocking {
                val scope = CoroutineScope(Dispatchers.Default)
                val gate = CompletableDeferred<Unit>()
                val s =
                    session(
                        FakeLlm(mutableListOf()),
                        compiler =
                            CandidateCompiler { script, _ ->
                                if (script == "slow") runBlocking { gate.await() }
                                CompileOutcome.Ok
                            },
                        scope = scope,
                    )
                s.generate("p", "b", false)
                s.settle()
                val prop = s.state.value as RepairState.Proposal
                // not the current state
                s.checkSelection(prop.copy(script = "other"), "x", setOf(0), 2, "b") { RepairState.Idle } shouldBe false
                var called = 0
                s.checkSelection(prop, "slow", setOf(0), 2, "b") {
                    called++
                    RepairState.Idle
                } shouldBe true
                s.state.value.shouldBeInstanceOf<RepairState.CheckingSelection>()
                s.stop()
                s.state.value shouldBe prop
                gate.complete(Unit)
                delay(200)
                called shouldBe 0
                s.state.value shouldBe prop
                // a normal check hands the outcome to the callback and takes its state
                s.checkSelection(prop, "ok", setOf(0), 2, "b") { o ->
                    o shouldBe CompileOutcome.Ok
                    RepairState.Idle
                } shouldBe true
                withTimeout(30_000) { while (s.state.value != RepairState.Idle) delay(10) }
            }
        }

        test("a check that overruns the total time ends in D-017 with the way back") {
            runBlocking {
                val lim = AiLimits(totalMillis = 300)
                val s =
                    session(
                        FakeLlm(mutableListOf()),
                        compiler =
                            CandidateCompiler { script, _ ->
                                if (script == "hang") Thread.sleep(5_000)
                                CompileOutcome.Ok
                            },
                        scope = CoroutineScope(Dispatchers.Default),
                        lim = lim,
                    )
                s.generate("p", "b", false)
                s.settle()
                val prop = s.state.value as RepairState.Proposal
                s.checkSelection(prop, "hang", setOf(0), 2, "b") { RepairState.Idle } shouldBe true
                withTimeout(30_000) { while (s.state.value !is RepairState.Failed) delay(10) }
                val f = s.state.value as RepairState.Failed
                f.code shouldBe AiErrorCodes.AI_LIMIT_REACHED
                f.back shouldBe prop
            }
        }

        test("a throwing compiler during a selection check reaches the callback as a D-030 refusal") {
            runBlocking {
                val s =
                    session(
                        FakeLlm(mutableListOf()),
                        compiler =
                            CandidateCompiler { script, _ ->
                                if (script == "boom") error("boom")
                                CompileOutcome.Ok
                            },
                        scope = CoroutineScope(Dispatchers.Default),
                    )
                s.generate("p", "b", false)
                s.settle()
                val prop = s.state.value as RepairState.Proposal
                s.checkSelection(prop, "boom", setOf(0), 2, "b") { o ->
                    val r = o.shouldBeInstanceOf<CompileOutcome.Refused>()
                    RepairState.Failed(r.code, r.message, emptyList(), prop)
                }
                withTimeout(30_000) { while (s.state.value !is RepairState.Failed) delay(10) }
                (s.state.value as RepairState.Failed).code shouldBe AiErrorCodes.AI_SANDBOX_START_FAILED
            }
        }
    })
