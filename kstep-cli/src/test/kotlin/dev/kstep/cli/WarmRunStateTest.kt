package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.concurrent.CountDownLatch

class WarmRunStateTest :
    StringSpec({
        "a clean run leaves nothing to report" {
            val state = WarmRunState.capture()
            val threads = WarmRunState.threadSnapshot()
            state.restoreAndDiff(threads) shouldBe emptyList()
        }

        "a set property is restored and reported, a removed one too" {
            val key = "kstep.warm.test.key"
            val base = "kstep.warm.test.base"
            System.setProperty(base, "keep")
            val state = WarmRunState.capture()
            val threads = WarmRunState.threadSnapshot()
            System.setProperty(key, "1")
            System.clearProperty(base)
            try {
                state.restoreAndDiff(threads).any { it.startsWith("properties:") } shouldBe true
                System.getProperty(key) shouldBe null
                System.getProperty(base) shouldBe "keep"
            } finally {
                System.clearProperty(key)
                System.clearProperty(base)
            }
        }

        "the default locale is restored and reported" {
            val before = Locale.getDefault()
            val state = WarmRunState.capture()
            val threads = WarmRunState.threadSnapshot()
            Locale.setDefault(Locale.JAPAN)
            try {
                state.restoreAndDiff(threads) shouldContain "locale"
                Locale.getDefault() shouldBe before
            } finally {
                Locale.setDefault(before)
            }
        }

        "a thread that outlives the run is reported" {
            val state = WarmRunState.capture()
            val threads = WarmRunState.threadSnapshot()
            val stop = CountDownLatch(1)
            val t = Thread { stop.await() }.apply { isDaemon = true }
            t.start()
            try {
                state.restoreAndDiff(threads).any { it.startsWith("threads:") } shouldBe true
            } finally {
                stop.countDown()
                t.join()
            }
        }
    })
