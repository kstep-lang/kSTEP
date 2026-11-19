package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/** The Linux bubblewrap sandbox against real hostile candidates (ADR-0059); skipped when bubblewrap is unusable. */
class WorkerSandboxLinuxTest :
    StringSpec({
        val linux = System.getProperty("os.name").orEmpty().startsWith("Linux")
        // -Pkstep.sandbox.require=true (the Linux CI job installs bubblewrap): a missing or unusable sandbox fails the run.
        val required = linux && System.getProperty("kstep.sandbox.require") == "true"
        val on =
            linux &&
                (
                    required ||
                        TestSupport.newDir().let { dir ->
                            try {
                                WorkerSandbox.detect(dir, dir) is WorkerSandbox.Bubblewrap
                            } finally {
                                dir.toFile().deleteRecursively()
                            }
                        }
                )
        val sandbox: SandboxFor = { dir -> WorkerSandbox.detect(dir, dir) }

        "bubblewrap is usable when the job requires it".config(enabled = required, timeout = 120.seconds) {
            val dir = TestSupport.newDir()
            try {
                WorkerSandbox.detect(dir, dir).shouldBeInstanceOf<WorkerSandbox.Bubblewrap>()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "network is refused; the control reaches both (skipped when bubblewrap is unusable)".config(
            enabled = on,
            timeout = 200.seconds,
        ) {
            SandboxProbes.networkCase(null)
            SandboxProbes.networkCase(sandbox)
        }

        "writes outside the session folder are refused (skipped when bubblewrap is unusable)".config(
            enabled = on,
            timeout = 200.seconds,
        ) {
            SandboxProbes.writeCase(null)
            SandboxProbes.writeCase(sandbox)
        }

        "a harmless candidate succeeds (skipped when bubblewrap is unusable)".config(
            enabled = on,
            timeout = 120.seconds,
        ) {
            SandboxProbes.harmlessCase(sandbox)
        }

        "the user D-Bus socket is not visible (skipped when bubblewrap is unusable)".config(
            enabled = on,
            timeout = 120.seconds,
        ) {
            val script =
                """
                val uid = java.nio.file.Files.getAttribute(java.nio.file.Path.of("/proc/self"), "unix:uid")
                if (java.nio.file.Files.exists(java.nio.file.Path.of("/run/user/${'$'}uid/bus"))) error("bus visible")
                """.trimIndent()
            SandboxProbes.run(sandbox, { script }) { _, r -> r.shouldBeInstanceOf<RunResult.Success>() }
        }
    })
