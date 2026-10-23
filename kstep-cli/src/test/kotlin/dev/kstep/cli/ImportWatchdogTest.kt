package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `kstep import` wall-clock watchdog (ADR-0038) as a unit with an injected action, so nothing halts the test JVM.
 * Every assertion waits on a latch or relies on the disarm state, never on a tight timing window.
 */
class ImportWatchdogTest :
    StringSpec({
        "fires its action once the import outlives the timeout, and the importer then loses the output" {
            val fired = CountDownLatch(1)
            val calls = AtomicInteger()
            val watchdog =
                ImportWatchdog(20) {
                    calls.incrementAndGet()
                    fired.countDown()
                }.start()
            // The "import" never finishes on its own; the watchdog must fire well within the generous wait.
            fired.await(10, TimeUnit.SECONDS) shouldBe true
            watchdog.disarm() shouldBe false
            calls.get() shouldBe 1
        }

        "a disarmed watchdog never fires, however late its thread wakes up" {
            val calls = AtomicInteger()
            val watchdog = ImportWatchdog(30) { calls.incrementAndGet() }.start()
            watchdog.disarm() shouldBe true
            // Disarming is idempotent for the owner of the output.
            watchdog.disarm() shouldBe true
            Thread.sleep(150)
            calls.get() shouldBe 0
        }

        "the timeout can only be lowered, never raised or switched off" {
            val default = ImportWatchdog.DEFAULT_TIMEOUT_MILLIS
            default shouldBe 60_000L
            ImportWatchdog.resolveTimeoutMillis(null, null) shouldBe default
            ImportWatchdog.resolveTimeoutMillis("5000", null) shouldBe 5_000L
            ImportWatchdog.resolveTimeoutMillis(null, "250") shouldBe 250L
            ImportWatchdog.resolveTimeoutMillis("100", "9000") shouldBe 100L
            ImportWatchdog.resolveTimeoutMillis("600000", null) shouldBe default
            ImportWatchdog.resolveTimeoutMillis("0", null) shouldBe default
            ImportWatchdog.resolveTimeoutMillis("-1", null) shouldBe default
            ImportWatchdog.resolveTimeoutMillis("soon", null) shouldBe default
        }

        "the timeout report is a structured limit_exceeded error (KSTEP-I-010) in JSON and text" {
            val json = Json.parseToJsonElement(importTimeoutReport(true, 1500)).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "error"
            json["command"]!!.jsonPrimitive.content shouldBe "import"
            json["errorKind"]!!.jsonPrimitive.content shouldBe "limit_exceeded"
            json["code"]!!.jsonPrimitive.content shouldBe "KSTEP-I-010"
            json["message"]!!.jsonPrimitive.content shouldContain "1500 ms"
            importTimeoutReport(false, 1500) shouldStartWith "Error: import did not finish within 1500 ms"
        }
    })
