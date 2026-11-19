package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

/**
 * The macOS sandbox against real hostile candidates (ADR-0059). On macOS there is NO skip: the first case demands that a
 * sandbox is found. (When this runs inside another sandbox, `sandbox-exec` cannot apply a profile and the first case
 * fails on purpose: start Gradle outside such a sandbox.)
 */
class WorkerSandboxMacTest :
    StringSpec({
        val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
        // the sandbox is bound to the session directory of each run
        val sb: SandboxFor = { dir -> WorkerSandbox.detect(dir, dir) }

        "a sandbox is found on macOS".config(enabled = mac, timeout = 120.seconds) {
            val dir = TestSupport.newDir()
            try {
                sb(dir).shouldBeInstanceOf<WorkerSandbox.MacSandboxExec>()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "network: no TCP, no UDP, no URL from the candidate; the control reaches both".config(
            enabled = mac,
            timeout = 200.seconds,
        ) {
            SandboxProbes.networkCase(null)
            SandboxProbes.networkCase(sb)
        }

        "writes: nothing outside the session folder; the control writes outside".config(
            enabled = mac,
            timeout = 200.seconds,
        ) {
            SandboxProbes.writeCase(null)
            SandboxProbes.writeCase(sb)
        }

        "a harmless candidate that writes into the session folder succeeds".config(
            enabled = mac,
            timeout = 120.seconds,
        ) {
            SandboxProbes.harmlessCase(sb)
        }

        "starting another program is refused; the control starts it".config(enabled = mac, timeout = 200.seconds) {
            val script = "if (ProcessBuilder(\"/usr/bin/true\").start().waitFor() != 0) error(\"bad\")\n"
            SandboxProbes.run(null, { script }) { _, r -> r.shouldBeInstanceOf<RunResult.Success>() }
            SandboxProbes.run(sb, { script }) { _, r -> r.shouldBeInstanceOf<RunResult.Failure>() }
        }

        "no process can outlive the worker: starting a JDK child with redirected IO is refused; the control starts it"
            .config(
                enabled = mac,
                timeout = 200.seconds,
            ) {
                val script =
                    """
                    val j = System.getProperty("java.home") + "/bin/java"
                    val p = ProcessBuilder(j, "-version")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    if (p.waitFor() != 0) error("bad")
                    """.trimIndent() + "\n"
                SandboxProbes.run(null, { script }) { _, r -> r.shouldBeInstanceOf<RunResult.Success>() }
                SandboxProbes.run(sb, { script }) { _, r -> r.shouldBeInstanceOf<RunResult.Failure>() }
            }

        "asking LaunchServices to open a link reaches no listener".config(enabled = mac, timeout = 200.seconds) {
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
                var requests = 0
                val t =
                    Thread {
                        try {
                            server.soTimeout = 15_000
                            server.accept().use { requests++ }
                        } catch (_: Exception) {
                            // nothing arrived
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                val script =
                    """
                    System.setProperty("java.awt.headless", "false")
                    java.awt.Desktop.getDesktop().browse(java.net.URI("http://127.0.0.1:${server.localPort}/probe"))
                    """.trimIndent()
                SandboxProbes.run(sb, { script }) { _, _ -> }
                t.join(20_000)
                requests shouldBe 0
            }
        }

        "control for the link case (opens a browser; only with KSTEP_SANDBOX_BROWSE_CONTROL=true)".config(
            enabled = mac && System.getenv("KSTEP_SANDBOX_BROWSE_CONTROL") == "true",
            timeout = 200.seconds,
        ) {
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
                var requests = 0
                val t =
                    Thread {
                        try {
                            server.soTimeout = 30_000
                            server.accept().use { requests++ }
                        } catch (_: Exception) {
                            // nothing arrived
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                val script =
                    """
                    System.setProperty("java.awt.headless", "false")
                    java.awt.Desktop.getDesktop().browse(java.net.URI("http://127.0.0.1:${server.localPort}/probe"))
                    """.trimIndent()
                SandboxProbes.run(null, { script }) { _, _ -> }
                t.join(35_000)
                requests shouldBe 1
            }
        }

        "user preferences: a candidate cannot write them through cfprefsd; the control can".config(
            enabled = mac,
            timeout = 200.seconds,
        ) {
            fun probe(node: String) =
                """
                val p = java.util.prefs.Preferences.userRoot().node("$node")
                p.put("k", "v")
                p.flush()
                """.trimIndent()

            val free = "kstep-probe-free-" + System.nanoTime()
            val boxed = "kstep-probe-boxed-" + System.nanoTime()
            val root =
                java.util.prefs.Preferences
                    .userRoot()
            try {
                SandboxProbes.run(null, { probe(free) }) { _, _ -> }
                SandboxProbes.run(sb, { probe(boxed) }) { _, _ -> }
                root.nodeExists(free) shouldBe true
                root.nodeExists(boxed) shouldBe false
            } finally {
                for (n in listOf(free, boxed)) {
                    runCatching { if (root.nodeExists(n)) root.node(n).removeNode() }
                }
                runCatching { root.flush() }
            }
        }
    })
