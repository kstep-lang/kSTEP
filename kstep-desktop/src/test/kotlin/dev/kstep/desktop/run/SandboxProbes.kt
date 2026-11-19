package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.listDirectoryEntries

/** Creates the sandbox for one session directory (`null` instead of a factory: the control run without a sandbox). */
typealias SandboxFor = (Path) -> WorkerSandbox

/**
 * Candidate scripts that try to do what a hostile AI script would do, and the harness to run them with and without a
 * sandbox (ADR-0059). Every sandbox case has a control run without a sandbox: it proves that the same candidate really
 * achieves the effect, so a green sandbox case cannot come from a script that merely does not compile. Network cases
 * only talk to listeners inside the test JVM, never to the internet.
 */
object SandboxProbes {
    private const val LISTEN_MILLIS = 10_000

    /** A valid script that ends in `stepFile { ... }`: a candidate is its probe code followed by this tail. */
    private fun validTail(): String = Files.readString(TestSupport.fixture("hello-assembly.kstep.kts"))

    /** A fresh session directory, the run of [script] with a sandbox for it (or none), and the check that nothing is left behind. */
    fun run(
        sandbox: SandboxFor?,
        script: (Path) -> String,
        after: (Path, RunResult) -> Unit,
    ) {
        val dir = TestSupport.newDir()
        try {
            val file = TestSupport.script(dir, "candidate", script(dir) + "\n" + validTail())
            val result = WorkerRunner(dir, timeoutMillis = 90_000, sandbox = sandbox?.invoke(dir)).run(file, 1) { }
            after(dir, result)
            dir
                .listDirectoryEntries()
                .filter { it.fileName.toString().let { n -> n.startsWith("kstep-import-") || n.endsWith(".glb") } }
                .shouldBeEmpty()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    class Listeners : AutoCloseable {
        val tcp = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val udp = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val gotTcp = AtomicBoolean(false)
        val gotUdp = AtomicBoolean(false)
        private val threads =
            listOf(
                Thread {
                    try {
                        tcp.soTimeout = LISTEN_MILLIS
                        tcp.accept().close()
                        gotTcp.set(true)
                    } catch (_: Exception) {
                        // timeout or closed: nothing arrived
                    }
                },
                Thread {
                    try {
                        udp.soTimeout = LISTEN_MILLIS
                        udp.receive(DatagramPacket(ByteArray(16), 16))
                        gotUdp.set(true)
                    } catch (_: Exception) {
                        // timeout or closed: nothing arrived
                    }
                },
            ).onEach {
                it.isDaemon = true
                it.start()
            }

        fun finish() = threads.forEach { it.join(LISTEN_MILLIS + 5_000L) }

        override fun close() {
            tcp.close()
            udp.close()
        }
    }

    fun networkScript(
        l: Listeners,
        withUrl: Boolean,
    ): String =
        """
        var denied = 0
        try { java.net.Socket("127.0.0.1", ${l.tcp.localPort}).close() } catch (e: Exception) { denied++ }
        try {
            java.net.DatagramSocket().use {
                it.send(java.net.DatagramPacket(ByteArray(4), 4, java.net.InetAddress.getByName("127.0.0.1"), ${l.udp.localPort}))
            }
        } catch (e: Exception) { denied++ }
        ${if (withUrl) {
            "try { java.net.URL(\"https://example.com\").openConnection().apply { connectTimeout = 3000 }.connect() } " +
                "catch (e: Exception) { denied++ }"
        } else {
            ""
        }}
        if (denied > 0) error("denied ${'$'}denied")
        """.trimIndent()

    fun writeScript(targets: List<Path>): String =
        """
        var denied = 0
        ${targets.joinToString("\n") {
            "try { java.io.File(\"$it\").writeText(\"x\") } catch (e: Exception) { denied++ }"
        }}
        if (denied > 0) error("denied ${'$'}denied")
        """.trimIndent()

    fun uniqueHomeFile(): Path = Path.of(System.getProperty("user.home"), "kstep-sandbox-probe-${UUID.randomUUID()}")

    fun outsideDir(): Path = Files.createTempDirectory("kstep-sandbox-outside").toRealPath()

    /** The network case: with [sandbox] the run fails and nothing arrives; without, both packets arrive. */
    fun networkCase(sandbox: SandboxFor?) {
        Listeners().use { l ->
            run(sandbox, { networkScript(l, withUrl = sandbox != null) }) { _, result ->
                if (sandbox != null) {
                    result.shouldBeInstanceOf<RunResult.Failure>()
                } else {
                    result.shouldBeInstanceOf<RunResult.Success>()
                }
            }
            l.finish()
            l.gotTcp.get() shouldBe (sandbox == null)
            l.gotUdp.get() shouldBe (sandbox == null)
        }
    }

    /** The write case: the sandbox refuses a file in the home folder and one outside the session; the control writes the latter. */
    fun writeCase(sandbox: SandboxFor?) {
        val outside = outsideDir()
        val outFile = outside.resolve("probe.txt")
        val homeFile = uniqueHomeFile()
        try {
            val targets = if (sandbox != null) listOf(homeFile, outFile) else listOf(outFile)
            run(sandbox, { writeScript(targets) }) { _, result ->
                if (sandbox != null) {
                    result.shouldBeInstanceOf<RunResult.Failure>()
                } else {
                    result.shouldBeInstanceOf<RunResult.Success>()
                }
            }
            Files.exists(outFile) shouldBe (sandbox == null)
            Files.exists(homeFile) shouldBe false
        } finally {
            Files.deleteIfExists(homeFile)
            outside.toFile().deleteRecursively()
        }
    }

    /** A harmless candidate writing into the session folder still succeeds. */
    fun harmlessCase(sandbox: SandboxFor?) {
        run(sandbox, { dir -> writeScript(listOf(dir.resolve("note.txt"))) }) { _, result ->
            result.shouldBeInstanceOf<RunResult.Success>()
        }
    }
}
