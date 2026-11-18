package dev.kstep.desktop.aiwire

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.ai.keys.KeyLookup
import dev.kstep.desktop.ai.keys.KeySource
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
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.ai.settings.SettingsStore
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

/** The glue between key storage, consent and the repair session (ADR-0056), with a fake key store and a fake model. */
class AiControllerTest :
    StringSpec({
        class FakeKeychain : KeychainBackend {
            override val displayName = "Fake keychain"
            val stored = HashMap<AiProvider, String>()
            val issued = CopyOnWriteArrayList<Secret>()
            var getFailure: StoreFailure? = null
            var putFailure: StoreFailure? = null

            override fun isAvailable() = true

            override fun put(
                provider: AiProvider,
                secret: Secret,
            ): StoreFailure? {
                if (putFailure != null) return putFailure
                stored[provider] = "stored"
                return null
            }

            override fun get(provider: AiProvider): KeyLookup {
                getFailure?.let { return KeyLookup.StoreFailed(it) }
                if (provider !in stored) return KeyLookup.Missing
                return KeyLookup.Found(Secret.fromEnv("sk-test-${issued.size}").also { issued += it })
            }

            override fun has(provider: AiProvider): Boolean? = provider in stored

            override fun delete(provider: AiProvider): StoreFailure? {
                stored.remove(provider)
                return null
            }
        }

        class FakeLlm(
            private val gate: CompletableDeferred<Unit>? = null,
        ) : LlmClient {
            val requests = CopyOnWriteArrayList<LlmRequest>()

            override suspend fun complete(request: LlmRequest): LlmReply {
                requests += request
                gate?.await()
                return LlmReply.Text("```kotlin\nval a = 1\n```", 5)
            }
        }

        class Fixture(
            val llm: FakeLlm = FakeLlm(),
        ) {
            val dir = TestSupport.newDir()
            val keychain = FakeKeychain()
            val session = SessionKeyStore()
            val usedKeys = CopyOnWriteArrayList<Secret>()
            val settings = SettingsStore(dir)
            val controller =
                AiController(
                    settings,
                    keychain,
                    session,
                    env = { null },
                    compiler = CandidateCompiler { _, _ -> CompileOutcome.Ok },
                    clientFactory = { _, k ->
                        usedKeys += k
                        llm
                    },
                )

            fun waitUi(cond: (AiUiState) -> Boolean) {
                TestSupport.await(30_000) { cond(controller.ui.value) } shouldBe true
            }

            fun waitRepair(cond: (RepairState) -> Boolean) {
                TestSupport.await(30_000) { cond(controller.repair.value) } shouldBe true
            }
        }

        "generate without consent sends nothing and says why" {
            val f = Fixture()
            try {
                f.keychain.stored[AiProvider.ANTHROPIC] = "x"
                f.controller.generate("make a box", "", false)
                f.waitUi { it.notice?.contains("Consent") == true }
                f.llm.requests.size shouldBe 0
                f.controller.repair.value shouldBe RepairState.Idle
            } finally {
                f.controller.close()
            }
        }

        "generate with consent and a keychain key reaches a proposal" {
            val f = Fixture()
            try {
                f.keychain.stored[AiProvider.ANTHROPIC] = "x"
                f.controller.grantConsent()
                f.controller.generate("make a box", "", false)
                f.waitRepair { it is RepairState.Proposal }
                f.llm.requests.size shouldBe 1
                f.controller.ui.value.keyStatus shouldBe KeyStatus.PRESENT
            } finally {
                f.controller.close()
            }
        }

        "generate without a key reports it, and a failing key store reports a D-018 notice" {
            val f = Fixture()
            try {
                f.controller.grantConsent()
                f.controller.generate("p", "", false)
                f.waitUi { it.keyStatus == KeyStatus.MISSING && it.notice != null }
                f.keychain.getFailure = StoreFailure.DENIED
                f.controller.generate("p", "", false)
                f.waitUi { it.keyStatus == KeyStatus.UNKNOWN && it.notice?.contains("KSTEP-D-018") == true }
                f.llm.requests.size shouldBe 0
            } finally {
                f.controller.close()
            }
        }

        "saveKey and removeKey act on the chosen source" {
            val f = Fixture()
            try {
                f.controller.saveKey("sk-session".toCharArray(), KeySource.SESSION)
                f.waitUi { it.keyStatus == KeyStatus.PRESENT }
                (f.session.get(AiProvider.ANTHROPIC) != null) shouldBe true
                f.keychain.stored.isEmpty() shouldBe true

                f.controller.saveKey("sk-chain".toCharArray(), KeySource.KEYCHAIN)
                f.waitUi { it.keyStatus == KeyStatus.PRESENT && !it.busyKeyStore }
                (AiProvider.ANTHROPIC in f.keychain.stored) shouldBe true

                f.controller.removeKey()
                f.waitUi { it.keyStatus == KeyStatus.MISSING }
                (f.session.get(AiProvider.ANTHROPIC) == null) shouldBe true
                f.keychain.stored.isEmpty() shouldBe true
            } finally {
                f.controller.close()
            }
        }

        "a refused key store write is reported" {
            val f = Fixture()
            try {
                f.keychain.putFailure = StoreFailure.TIMEOUT
                f.controller.saveKey("sk".toCharArray(), KeySource.KEYCHAIN)
                f.waitUi { it.notice?.contains("KSTEP-D-018") == true }
                f.controller.ui.value.keyStatus shouldBe KeyStatus.UNKNOWN
            } finally {
                f.controller.close()
            }
        }

        "consent is granted, persisted and revoked" {
            val f = Fixture()
            try {
                f.controller.ui.value.hasConsent shouldBe false
                f.controller.grantConsent()
                f.controller.ui.value.hasConsent shouldBe true
                SettingsStore(f.dir).let { it.hasConsent(it.load(), AiProvider.ANTHROPIC) } shouldBe true
                f.controller.revokeConsent()
                f.controller.ui.value.hasConsent shouldBe false
                SettingsStore(f.dir).let { it.hasConsent(it.load(), AiProvider.ANTHROPIC) } shouldBe false
            } finally {
                f.controller.close()
            }
        }

        "the old key is wiped only when a new run really started" {
            val gate = CompletableDeferred<Unit>()
            val f = Fixture(FakeLlm(gate))
            try {
                f.keychain.stored[AiProvider.ANTHROPIC] = "x"
                f.controller.grantConsent()
                f.controller.generate("first", "", false)
                f.waitRepair { it is RepairState.Requesting }
                f.usedKeys.size shouldBe 1
                val first = f.usedKeys[0]

                // consent is gone: the second call is refused, the first run keeps its key
                f.controller.revokeConsent()
                f.controller.generate("second", "", false)
                f.waitUi { it.notice?.contains("Consent") == true }
                f.usedKeys.size shouldBe 2
                first.isWiped() shouldBe false
                f.usedKeys[1].isWiped() shouldBe true
                f.controller.repair.value
                    .shouldBeInstanceOf<RepairState.Requesting>()

                // consent again: the third call replaces the run, only now the first key goes away
                f.controller.grantConsent()
                f.controller.generate("third", "", false)
                TestSupport.await(30_000) { first.isWiped() } shouldBe true
                f.usedKeys[2].isWiped() shouldBe false
                gate.complete(Unit)
                f.waitRepair { it is RepairState.Proposal }
            } finally {
                gate.complete(Unit)
                f.controller.close()
            }
        }

        "a session key is never wiped by a new run" {
            val f = Fixture()
            try {
                f.controller.saveKey("sk-session".toCharArray(), KeySource.SESSION)
                f.waitUi { it.keyStatus == KeyStatus.PRESENT }
                f.controller.grantConsent()
                f.controller.generate("one", "", false)
                f.waitRepair { it is RepairState.Proposal }
                f.controller.generate("two", "", false)
                TestSupport.await(30_000) { f.llm.requests.size == 2 } shouldBe true
                f.usedKeys.size shouldBe 2
                f.usedKeys.all { !it.isWiped() } shouldBe true
                f.usedKeys.last().length() shouldBe "sk-session".length
            } finally {
                f.controller.close()
            }
        }
    })
