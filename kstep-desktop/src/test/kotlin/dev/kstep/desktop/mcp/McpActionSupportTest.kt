package dev.kstep.desktop.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class McpUiBridgeMutatingTest :
    StringSpec({
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "bridge-ui").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()

        fun bridge(timeoutMs: Long) = McpUiBridge(dispatcher, { Thread.currentThread().name == "bridge-ui" }, timeoutMs)

        "a block that did not start before the timeout never runs, not even later" {
            val b = bridge(150)
            val gate = CountDownLatch(1)
            val ran = AtomicInteger()
            executor.submit { gate.await() } // the UI thread is busy
            val e = shouldThrow<McpToolException> { b.onUiMutating { ran.incrementAndGet() } }
            e.code shouldBe McpToolCodes.UI_BUSY
            gate.countDown()
            Thread.sleep(200)
            ran.get() shouldBe 0
        }

        "a block that started before the timeout and takes longer still delivers its result" {
            val b = bridge(100)
            val result = b.onUiMutating { Thread.sleep(400).let { "done" } }
            result shouldBe "done"
        }

        "a block that has begun but hangs is given up after the upper limit with INTERNAL" {
            val b = McpUiBridge(dispatcher, { Thread.currentThread().name == "bridge-ui" }, 50, startedLimitMs = 300)
            val release = CountDownLatch(1)
            val e =
                shouldThrow<McpToolException> {
                    b.onUiMutating {
                        release.await()
                        1
                    }
                }
            release.countDown()
            e.code shouldBe McpToolCodes.INTERNAL
        }

        "an exception of the block is INTERNAL without text" {
            val b = bridge(500)
            val e = shouldThrow<McpToolException> { b.onUiMutating { error("secret detail") } }
            e.code shouldBe McpToolCodes.INTERNAL
            e.reason shouldBe null
            e.message shouldBe null
        }

        "a tool exception of the block passes through" {
            val b = bridge(500)
            shouldThrow<McpToolException> { b.onUiMutating { throw McpToolException(McpToolCodes.NOT_FOUND) } }
                .code shouldBe McpToolCodes.NOT_FOUND
        }

        "calling it from the UI thread is refused" {
            val b = bridge(500)
            val failure = arrayOfNulls<Throwable>(1)
            executor.submit { failure[0] = runCatching { b.onUiMutating { 1 } }.exceptionOrNull() }.get()
            failure[0].shouldBeInstanceOf<IllegalStateException>()
        }
    })

class McpAgentTextTest :
    StringSpec({
        fun ok(t: String) = (McpAgentText.validate(t) as McpAgentText.Validation.Ok).text

        "revisionOf has the shape get_script reports and isRevision accepts exactly that" {
            val r = McpAgentText.revisionOf("abc")
            McpAgentText.isRevision(r) shouldBe true
            r shouldBe "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            McpAgentText.isRevision(r.uppercase()) shouldBe false
            McpAgentText.isRevision("sha256:" + "a".repeat(63)) shouldBe false
            McpAgentText.isRevision("sha256:" + "a".repeat(65)) shouldBe false
            McpAgentText.isRevision("a".repeat(71)) shouldBe false
            McpAgentText.isRevision("sha256:" + "g".repeat(64)) shouldBe false
            McpAgentText.isRevision("") shouldBe false
        }

        "the size limit is 1 MiB of UTF-8, also with multi-byte characters" {
            // lines of exactly 1024 bytes each, so that the line limit does not interfere
            val ascii = ("a".repeat(1023) + "\n").repeat(1024)
            val twoByte = ("é".repeat(511) + "a\n").repeat(1024)
            val fourByte = ("\uD83D\uDE00".repeat(255) + "aaa\n").repeat(1024)
            for (text in listOf(ascii, twoByte, fourByte)) {
                text.toByteArray().size shouldBe 1_048_576
                McpAgentText.validate(text).shouldBeInstanceOf<McpAgentText.Validation.Ok>()
                McpAgentText.validate(text + "a") shouldBe McpAgentText.Validation.TooLarge
            }
        }

        "lone surrogates and control, bidi and separator characters are invalid" {
            val codePoints =
                listOf(
                    0x0000,
                    0x001B,
                    0x007F,
                    0x0085,
                    0x009F,
                    0xFEFF,
                    0xFFFE,
                    0xFFFF,
                    0x202A,
                    0x202E,
                    0x2066,
                    0x2069,
                    0x200E,
                    0x200F,
                    0x061C,
                    0x2028,
                    0x2029,
                )
            codePoints.forEach { cp ->
                McpAgentText.validate("x" + cp.toChar() + "y") shouldBe McpAgentText.Validation.Invalid
            }
            listOf("\uD83D", "\uDE00", "a\uDE00\uD83D").forEach {
                McpAgentText.validate("x${it}y") shouldBe McpAgentText.Validation.Invalid
            }
        }

        "tab, line feed and carriage return are allowed and line endings become line feeds" {
            ok("a\tb\nc") shouldBe "a\tb\nc"
            ok("a\r\nb") shouldBe "a\nb"
            ok("a\rb") shouldBe "a\nb"
            ok("a\r\n\r\nb\r") shouldBe "a\n\nb\n"
            ok("日本語 😀") shouldBe "日本語 😀"
        }

        "a line of 20,000 characters is fine, 20,001 is not" {
            McpAgentText.validate("a".repeat(20_000) + "\nb").shouldBeInstanceOf<McpAgentText.Validation.Ok>()
            McpAgentText.validate("a".repeat(20_001)) shouldBe McpAgentText.Validation.Invalid
            McpAgentText.validate("a".repeat(20_000) + "\n" + "b".repeat(20_001)) shouldBe
                McpAgentText.Validation.Invalid
        }

        "checkChars=false skips the character rules but keeps the limits" {
            McpAgentText.validate("a‮b", checkChars = false).shouldBeInstanceOf<McpAgentText.Validation.Ok>()
            McpAgentText.validate(("a".repeat(1023) + "\n").repeat(1024) + "a", checkChars = false) shouldBe
                McpAgentText.Validation.TooLarge
        }

        "findLiteral counts 0, 1, 2 and overlapping hits" {
            McpAgentText.findLiteral("abc", "x").count shouldBe 0
            McpAgentText.findLiteral("abc", "b").let { it.count to it.first } shouldBe (1 to 1)
            McpAgentText.findLiteral("abab", "ab").count shouldBe 2
            McpAgentText.findLiteral("aaa", "aa").count shouldBe 2
            McpAgentText.findLiteral("aaa", "aaaa").count shouldBe 0
            McpAgentText.findLiteral("abc", "").count shouldBe 0
        }

        "findLiteral stops at the cap" {
            val r = McpAgentText.findLiteral("a".repeat(5000), "a", maxCount = 1000)
            r.count shouldBe 1000
            r.capped shouldBe true
        }

        "a hostile needle cannot make the search slow (linear time)" {
            val text = "a".repeat(1_048_576)
            val needle = "a".repeat(65_535) + "b"
            val ms = measureTimeMillis { McpAgentText.findLiteral(text, needle).count shouldBe 0 }
            (ms < 1000) shouldBe true
        }
    })

class McpRateLimiterTest :
    StringSpec({
        "20 calls in a second pass, the 21st does not, and the window frees up" {
            val w = SlidingWindow()
            repeat(20) { w.tryAcquire(1000L + it) shouldBe true }
            w.tryAcquire(1500) shouldBe false
            w.tryAcquire(1999) shouldBe false
            w.tryAcquire(2000) shouldBe true
        }

        "the run spacing asks for the rest of the second and is set only by mark" {
            val s = RunSpacing()
            s.check(0) shouldBe null
            s.mark(1000)
            s.check(1999) shouldBe 1L
            s.check(2000) shouldBe null
            s.check(1001) shouldBe 999L
        }

        "only one waiter at a time" {
            val w = SingleWaiter()
            w.tryEnter() shouldBe true
            w.tryEnter() shouldBe false
            w.leave()
            w.tryEnter() shouldBe true
        }
    })
