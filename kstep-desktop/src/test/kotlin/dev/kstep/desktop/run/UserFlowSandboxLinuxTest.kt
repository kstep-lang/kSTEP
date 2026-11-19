package dev.kstep.desktop.run

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/**
 * The user-flow sandbox on Linux with bubblewrap (ADR-0062). The key case is B3: /tmp stays readable, so scripts and
 * inputs there are found. The CI job passes -Pkstep.sandbox.require=true, then the first case is mandatory.
 */
class UserFlowSandboxLinuxTest :
    StringSpec({
        val linux = System.getProperty("os.name").orEmpty().startsWith("Linux")
        val required = linux && System.getProperty("kstep.sandbox.require") == "true"
        val box by lazy { UserScriptSandbox.production() }
        val on = linux && (required || box.resolve() is UserSandboxState.Active)
        val sb: SandboxFor by lazy { UserFlowSandboxProbes.factory(box) }

        "bubblewrap is usable for user flows when the job requires it".config(
            enabled = required,
            timeout = 120.seconds,
        ) {
            val state = box.resolve().shouldBeInstanceOf<UserSandboxState.Active>()
            state.sandbox.shouldBeInstanceOf<WorkerSandbox.Bubblewrap>()
        }

        "a script and an input file under /tmp are readable (skipped when bubblewrap is unusable)".config(
            enabled = on,
            timeout = 200.seconds,
        ) {
            UserFlowSandboxProbes.readCase(sb)
        }

        "writes outside the session folder are refused (skipped when bubblewrap is unusable)".config(
            enabled = on,
            timeout = 200.seconds,
        ) {
            SandboxProbes.writeCase(null)
            SandboxProbes.writeCase(sb)
        }

        "network is refused (skipped when bubblewrap is unusable)".config(enabled = on, timeout = 200.seconds) {
            SandboxProbes.networkCase(null)
            SandboxProbes.networkCase(sb)
        }

        "export, knowledge block and timeout work (skipped when bubblewrap is unusable)".config(
            enabled = on,
            timeout = 400.seconds,
        ) {
            UserFlowSandboxProbes.exportCase(box)
            UserFlowSandboxProbes.knowledgeCase(box)
            UserFlowSandboxProbes.timeoutCase(box)
        }
    })
