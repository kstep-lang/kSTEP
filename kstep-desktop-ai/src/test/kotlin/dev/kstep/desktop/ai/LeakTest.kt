package dev.kstep.desktop.ai

import dev.kstep.desktop.ai.keys.KEY
import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.llm.FailureKind
import dev.kstep.desktop.ai.llm.LlmMessage
import dev.kstep.desktop.ai.llm.LlmRequest
import dev.kstep.desktop.ai.provider.FakeTransport
import dev.kstep.desktop.ai.provider.KoogAnthropicClient
import dev.kstep.desktop.ai.repair.AiLimits
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Runs with the DEBUG default of the test task and Koog silenced exactly as Main does (see build.gradle.kts). */
class LeakTest :
    FunSpec({
        test("a full run with failures leaves neither key, prompt nor script in the log output") {
            val buf = ByteArrayOutputStream()
            val old = System.err
            System.setErr(PrintStream(buf, true))
            try {
                val promptMarker = "PROMPT-MARKER-7731"
                val scriptMarker = "SCRIPT-MARKER-9912"
                val req =
                    LlmRequest("sys", listOf(LlmMessage(LlmMessage.Role.USER, "$promptMarker $scriptMarker")), 8192)
                val transports =
                    listOf(
                        FakeTransport(),
                        FakeTransport(401, "{\"error\":\"$KEY\"}"),
                        FakeTransport(500, "boom $promptMarker"),
                        FakeTransport(throwKind = FailureKind.UNREACHABLE),
                        FakeTransport(body = "garbage $scriptMarker"),
                    )
                for (t in transports) {
                    val c = KoogAnthropicClient("claude-sonnet-5", Secret.fromEnv(KEY), t, AiLimits())
                    runBlocking { c.complete(req) }
                }
            } finally {
                System.setErr(old)
            }
            val log = buf.toString()
            log shouldNotContain KEY
            log shouldNotContain "PROMPT-MARKER"
            log shouldNotContain "SCRIPT-MARKER"
            AiLogging.QUIET_PROPERTIES.keys.all { System.getProperty(it) != null } shouldBe true
        }
    })
