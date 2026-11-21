package dev.kstep.desktop.aiwire

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.ai.AiErrorCodes
import dev.kstep.desktop.ai.diff.HunkPlan
import dev.kstep.desktop.ai.diff.LineDiff
import dev.kstep.desktop.ai.extract.Diagnostic
import dev.kstep.desktop.ai.keys.KeyLookup
import dev.kstep.desktop.ai.keys.KeychainBackend
import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.keys.SessionKeyStore
import dev.kstep.desktop.ai.keys.StoreFailure
import dev.kstep.desktop.ai.llm.LlmClient
import dev.kstep.desktop.ai.llm.LlmReply
import dev.kstep.desktop.ai.llm.LlmRequest
import dev.kstep.desktop.ai.provider.AiProvider
import dev.kstep.desktop.ai.repair.CandidateCompiler
import dev.kstep.desktop.ai.repair.CompileOutcome
import dev.kstep.desktop.ai.repair.NotVerifiedReason
import dev.kstep.desktop.ai.repair.ProposalOrigin
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.ai.repair.Verdict
import dev.kstep.desktop.ai.settings.SettingsStore
import dev.kstep.desktop.recent.PromptHistoryStore
import dev.kstep.desktop.run.WorkerSandbox
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/** Choosing single changes: re-check in the sandbox, apply only on COMPILES, fail closed otherwise (ADR-0069). */
class AiControllerSelectionTest :
    StringSpec({
        val base = (1..40).joinToString("\n") { "l$it" } + "\n"
        val answer =
            base
                .trimEnd('\n')
                .split('\n')
                .toMutableList()
                .also {
                    it[4] = "X5"
                    it[19] = "X20"
                    it[34] = "X35"
                }.joinToString("\n")

        class FakeKeychain : KeychainBackend {
            override val displayName = "Fake keychain"

            override fun isAvailable() = true

            override fun put(
                provider: AiProvider,
                secret: Secret,
            ): StoreFailure? = null

            override fun get(provider: AiProvider): KeyLookup = KeyLookup.Found(Secret.fromEnv("sk-test-secret-1"))

            override fun has(provider: AiProvider): Boolean? = true

            override fun delete(provider: AiProvider): StoreFailure? = null
        }

        class Fixture(
            sandboxAvailable: Boolean = true,
        ) {
            val dir = TestSupport.newDir()
            val compiles = CopyOnWriteArrayList<String>()

            @Volatile var next: () -> CompileOutcome = { CompileOutcome.Ok }
            val applied = CopyOnWriteArrayList<Pair<String, String>>()

            @Volatile var applyResult = true
            val llm =
                object : LlmClient {
                    override suspend fun complete(request: LlmRequest) = LlmReply.Text("```kotlin\n$answer\n```", 5)
                }
            val controller =
                AiController(
                    SettingsStore(dir),
                    FakeKeychain(),
                    SessionKeyStore(),
                    env = { null },
                    compiler =
                        CandidateCompiler { s, _ ->
                            compiles += s
                            next()
                        },
                    sandbox = {
                        if (sandboxAvailable) {
                            WorkerSandbox.MacSandboxExec("p", dir, dir)
                        } else {
                            WorkerSandbox.Unavailable("none")
                        }
                    },
                    clientFactory = { _, _ -> llm },
                    historyStore = PromptHistoryStore(dir),
                )
            val applyIf: (String, String) -> Boolean = { e, t ->
                applied += e to t
                applyResult
            }

            fun waitRepair(cond: (RepairState) -> Boolean) {
                TestSupport.await(30_000) { cond(controller.repair.value) } shouldBe true
            }

            /** Generates a proposal with three change groups and waits until it is there and the sandbox is known. */
            fun proposal(): Pair<RepairState.Proposal, HunkPlan.Ready> {
                TestSupport.await(30_000) { controller.ui.value.sandbox != SandboxStatus.CHECKING } shouldBe true
                controller.grantConsent()
                controller.generate("make it", base, true)
                waitRepair { it is RepairState.Proposal }
                val p = controller.repair.value as RepairState.Proposal
                compiles.clear()
                return p to (LineDiff.plan(base, p.script) as HunkPlan.Ready)
            }
        }

        "a subset is built, checked once in the sandbox and applied atomically only on COMPILES".config(
            timeout = 90.seconds,
        ) {
            val f = Fixture()
            try {
                val (p, plan) = f.proposal()
                plan.hunks.size shouldBe 3
                f.controller.applySelection(p, plan, setOf(0, 2), f.applyIf)
                f.waitRepair { it == RepairState.Idle }
                f.compiles.size shouldBe 1
                f.applied.size shouldBe 1
                f.applied[0].first shouldBe base
                f.applied[0].second shouldBe f.compiles[0] // exactly the buffer that was checked
                f.applied[0].second.lines()[4] shouldBe "X5"
                f.applied[0].second.lines()[19] shouldBe "l20"
                f.applied[0].second.lines()[34] shouldBe "X35"
            } finally {
                f.controller.close()
            }
        }

        "a subset that does not compile is not applied; Apply anyway applies it, Back returns".config(
            timeout = 90.seconds,
        ) {
            val f = Fixture()
            try {
                val (p, plan) = f.proposal()
                f.next = { CompileOutcome.Errors(listOf(Diagnostic("KSTEP-S-003", 5, 1, "unresolved"))) }
                f.controller.applySelection(p, plan, setOf(1), f.applyIf)
                f.waitRepair { it is RepairState.Proposal && it.origin == ProposalOrigin.SELECTION }
                val sel = f.controller.repair.value as RepairState.Proposal
                sel.verdict shouldBe Verdict.DOES_NOT_COMPILE
                sel.original shouldBe p
                f.applied.size shouldBe 0
                f.controller.backToProposal()
                f.controller.repair.value shouldBe p
                f.controller.applySelection(p, plan, setOf(1), f.applyIf)
                f.waitRepair { it is RepairState.Proposal && it.origin == ProposalOrigin.SELECTION }
                val sel2 = f.controller.repair.value as RepairState.Proposal
                f.controller.applyChecked(sel2, f.applyIf)
                f.waitRepair { it == RepairState.Idle }
                f.applied.single() shouldBe (base to sel2.script)
            } finally {
                f.controller.close()
            }
        }

        "a geometry-engine gap on a subset is NOT_VERIFIED, a missing sandbox is a failure".config(
            timeout = 90.seconds,
        ) {
            val f = Fixture()
            try {
                val (p, plan) = f.proposal()
                f.next = { CompileOutcome.Unverifiable("no engine", NotVerifiedReason.GEOMETRY_ENGINE_UNAVAILABLE) }
                f.controller.applySelection(p, plan, setOf(0), f.applyIf)
                f.waitRepair { it is RepairState.Proposal && it.origin == ProposalOrigin.SELECTION }
                (f.controller.repair.value as RepairState.Proposal).verdict shouldBe Verdict.NOT_VERIFIED
                f.controller.backToProposal()

                f.next = { CompileOutcome.Unverifiable("no sandbox", NotVerifiedReason.SANDBOX_UNAVAILABLE) }
                f.controller.applySelection(p, plan, setOf(0), f.applyIf)
                f.waitRepair { it is RepairState.Failed }
                val failed = f.controller.repair.value as RepairState.Failed
                failed.code shouldBe AiErrorCodes.AI_PARTIAL_NEEDS_SANDBOX
                failed.back shouldBe p
                f.applied.size shouldBe 0
            } finally {
                f.controller.close()
            }
        }

        "a refused check and a throwing compiler fail with the way back and never apply".config(timeout = 90.seconds) {
            val f = Fixture()
            try {
                val (p, plan) = f.proposal()
                f.next = { CompileOutcome.Refused("KSTEP-D-029", "sandbox refused") }
                f.controller.applySelection(p, plan, setOf(0), f.applyIf)
                f.waitRepair { it is RepairState.Failed }
                (f.controller.repair.value as RepairState.Failed).code shouldBe "KSTEP-D-029"
                f.controller.backToProposal()
                f.controller.repair.value shouldBe p
                f.next = { error("boom") }
                f.controller.applySelection(p, plan, setOf(0), f.applyIf)
                f.waitRepair { it is RepairState.Failed }
                (f.controller.repair.value as RepairState.Failed).code shouldBe AiErrorCodes.AI_SANDBOX_START_FAILED
                f.applied.size shouldBe 0
            } finally {
                f.controller.close()
            }
        }

        "without a sandbox a subset is refused with D-034 and nothing is compiled".config(timeout = 90.seconds) {
            val f = Fixture(sandboxAvailable = false)
            try {
                val (p, plan) = f.proposal()
                f.controller.sandboxAvailable shouldBe false
                f.controller.applySelection(p, plan, setOf(0), f.applyIf)
                f.waitRepair { it is RepairState.Failed }
                (f.controller.repair.value as RepairState.Failed).code shouldBe AiErrorCodes.AI_PARTIAL_NEEDS_SANDBOX
                f.compiles.size shouldBe 0
                f.applied.size shouldBe 0
            } finally {
                f.controller.close()
            }
        }

        "a full selection needs no new check; an empty one does nothing".config(timeout = 90.seconds) {
            val f = Fixture()
            try {
                val (p, plan) = f.proposal()
                f.controller.applySelection(p, plan, emptySet(), f.applyIf)
                f.controller.repair.value shouldBe p
                f.controller.applySelection(p, plan, setOf(0, 1, 2), f.applyIf)
                f.waitRepair { it == RepairState.Idle }
                f.compiles.size shouldBe 0
                f.applied.single() shouldBe (base to p.script)
            } finally {
                f.controller.close()
            }
        }

        "when the editor changed during the check nothing is applied (D-035)".config(timeout = 90.seconds) {
            val f = Fixture()
            try {
                val (p, plan) = f.proposal()
                f.applyResult = false
                f.controller.applySelection(p, plan, setOf(0), f.applyIf)
                f.waitRepair { it is RepairState.Failed }
                val failed = f.controller.repair.value as RepairState.Failed
                failed.code shouldBe AiErrorCodes.AI_EDITOR_CHANGED
                failed.back shouldBe p
            } finally {
                f.controller.close()
            }
        }

        "Stop during the check returns to the proposal and a late result is dropped".config(timeout = 90.seconds) {
            val f = Fixture()
            try {
                val (p, plan) = f.proposal()
                val release = CountDownLatch(1)
                f.next = {
                    release.await()
                    CompileOutcome.Ok
                }
                f.controller.applySelection(p, plan, setOf(0), f.applyIf)
                f.waitRepair { it is RepairState.CheckingSelection }
                f.controller.stop()
                f.controller.repair.value shouldBe p
                release.countDown()
                Thread.sleep(300)
                f.controller.repair.value shouldBe p
                f.applied.size shouldBe 0
            } finally {
                f.controller.close()
            }
        }

        "the prompt history keeps prompts only: no key, answer or model, and not a prompt with the key".config(
            timeout = 90.seconds,
        ) {
            val f = Fixture()
            try {
                f.controller.grantConsent()
                f.controller.generate("build a flange", base, false)
                f.waitRepair { it is RepairState.Proposal }
                TestSupport.await(30_000) { f.controller.history.value == listOf("build a flange") } shouldBe true
                f.controller.generate("my key is sk-test-secret-1 ok", base, false)
                f.waitRepair { it is RepairState.Proposal }
                Thread.sleep(300)
                f.controller.history.value shouldBe listOf("build a flange")
                val text = Files.readString(f.dir.resolve(PromptHistoryStore.FILE_NAME))
                text shouldContain "build a flange"
                text shouldNotContain "sk-test"
                text shouldNotContain "X5"
                text shouldNotContain f.controller.ui.value.model.id
                PromptHistoryStore(f.dir).load().entries shouldBe listOf("build a flange")
                f.controller.clearHistory()
                TestSupport.await(30_000) {
                    f.controller.history.value
                        .isEmpty()
                } shouldBe true
                TestSupport.await(30_000) {
                    !Files.exists(f.dir.resolve(PromptHistoryStore.FILE_NAME))
                } shouldBe true
            } finally {
                f.controller.close()
            }
        }

        "a key with an invisible character inside is not stored either".config(timeout = 90.seconds) {
            val f = Fixture()
            try {
                f.controller.grantConsent()
                f.controller.generate("build a flange", base, false)
                f.waitRepair { it is RepairState.Proposal }
                TestSupport.await(30_000) { f.controller.history.value == listOf("build a flange") } shouldBe true
                f.controller.generate("my key is sk-test-secret\u200E-1 ok", base, false)
                f.waitRepair { it is RepairState.Proposal }
                Thread.sleep(300)
                f.controller.history.value shouldBe listOf("build a flange")
                Files.readString(f.dir.resolve(PromptHistoryStore.FILE_NAME)) shouldNotContain "secret"
            } finally {
                f.controller.close()
            }
        }

        "a refused generate (no consent) records no prompt".config(timeout = 90.seconds) {
            val f = Fixture()
            try {
                f.controller.generate("no consent yet", base, false)
                TestSupport.await(30_000) {
                    f.controller.ui.value.notice
                        ?.contains("Consent") == true
                } shouldBe true
                f.controller.history.value shouldBe emptyList()
                Files.exists(f.dir.resolve(PromptHistoryStore.FILE_NAME)) shouldBe false
                f.controller.repair.value
                    .shouldBeInstanceOf<RepairState.Idle>()
            } finally {
                f.controller.close()
            }
        }
    })
