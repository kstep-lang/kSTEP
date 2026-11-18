package dev.kstep.desktop.ai.keys

import dev.kstep.desktop.ai.provider.AiProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Real OS keychain; run with `./gradlew :kstep-desktop-ai:liveKeychainTest` only (not part of `check`, not in CI). */
class LiveKeychainContractTest :
    FunSpec({
        test("put, get, has, delete against the platform keychain") {
            val b = platformKeychain()
            if (!b.isAvailable()) error("no platform keychain here; this task is for a machine with one")
            val p = AiProvider.ANTHROPIC
            val secret = Secret.fromEnv("kstep-live-contract-" + System.nanoTime())
            b.put(p, secret) shouldBe null
            b.has(p) shouldBe true
            val got = b.get(p)
            got.shouldBeInstanceOf<KeyLookup.Found>()
            (got as KeyLookup.Found).secret.length() shouldBe secret.length()
            b.delete(p) shouldBe null
            b.has(p) shouldBe false
        }
    })
