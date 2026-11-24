package dev.kstep.desktop.run

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/** The user-flow sandbox against real scripts on macOS (ADR-0062). No skip: the first case demands a sandbox. */
class UserFlowSandboxMacTest :
    StringSpec({
        val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
        val box by lazy { UserScriptSandbox.production() }
        val sb: SandboxFor by lazy { UserFlowSandboxProbes.factory(box) }

        "the probe of the production wiring finds a sandbox on macOS".config(enabled = mac, timeout = 120.seconds) {
            val state = box.resolve().shouldBeInstanceOf<UserSandboxState.Active>()
            state.sandbox.shouldBeInstanceOf<WorkerSandbox.MacSandboxExec>()
            sandboxNotice(box.state.value) shouldBe null
        }

        "writes outside the session folder fail; the control writes there".config(
            enabled = mac,
            timeout = 200.seconds,
        ) {
            SandboxProbes.writeCase(null)
            SandboxProbes.writeCase(sb)
        }

        "no network from the user's script; the control reaches both".config(enabled = mac, timeout = 200.seconds) {
            SandboxProbes.networkCase(null)
            SandboxProbes.networkCase(sb)
        }

        "reading a file in another temp folder still works".config(enabled = mac, timeout = 200.seconds) {
            UserFlowSandboxProbes.readCase(sb)
        }

        "readInput works in the sandbox and reports the input".config(enabled = mac, timeout = 200.seconds) {
            UserFlowSandboxProbes.inputCase(sb)
        }

        "a harmless script succeeds".config(enabled = mac, timeout = 120.seconds) {
            SandboxProbes.harmlessCase(sb)
        }

        "export through the sandboxed runner is never D-031 and the result can be copied out".config(
            enabled = mac,
            timeout = 240.seconds,
        ) {
            UserFlowSandboxProbes.exportCase(box)
        }

        "a knowledge block runs through the sandboxed runner".config(enabled = mac, timeout = 200.seconds) {
            UserFlowSandboxProbes.knowledgeCase(box)
        }

        "the timeout and the kill work under the sandbox".config(enabled = mac, timeout = 200.seconds) {
            UserFlowSandboxProbes.timeoutCase(box)
        }
    })
