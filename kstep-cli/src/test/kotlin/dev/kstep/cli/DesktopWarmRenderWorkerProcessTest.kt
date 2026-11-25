package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The warm worker as a real process (ADR-0081): protocol, warm replies equal cold output, the ways it ends. */
class DesktopWarmRenderWorkerProcessTest :
    StringSpec({
        class Worker(
            maxRuns: Int,
            idleMillis: Long = 120_000,
        ) {
            val process: Process =
                ProcessBuilder(
                    ImportWorkerLaunch.command(
                        emptyList(),
                        60_000,
                        mainClass = DesktopWarmRenderWorker.MAIN_CLASS,
                        maxHeap = "256m",
                        extraJvmOptions =
                            listOf(
                                "-D${DesktopWarmRenderWorker.MAX_RUNS_PROPERTY}=$maxRuns",
                                "-D${DesktopWarmRenderWorker.IDLE_PROPERTY}=$idleMillis",
                            ),
                    ),
                ).start()
            private val input = BufferedInputStream(process.inputStream)

            fun line(): String {
                val l = WarmRenderProtocol.readLine(input, WarmRenderProtocol.MAX_REPLY_LINE_BYTES)
                return (l as WarmRenderProtocol.Line.Text).bytes.decodeToString()
            }

            fun send(text: String) {
                process.outputStream.write((text + "\n").toByteArray())
                process.outputStream.flush()
            }

            fun request(
                id: Long,
                script: Path,
                out: Path,
            ): WarmRenderProtocol.Reply {
                send(
                    WarmRenderProtocol.encodeRequest(WarmRenderProtocol.Request(id, script.toString(), out.toString())),
                )
                return WarmRenderProtocol.decodeReply(line(), id)!!
            }

            fun ended(seconds: Long = 60): Boolean = process.waitFor(seconds, TimeUnit.SECONDS)

            fun close() {
                process.destroyForcibly()
            }
        }

        fun script(
            dir: Path,
            name: String,
            text: String,
        ): Path = dir.resolve("$name.kstep.kts").also { Files.writeString(it, text) }

        // The cold path: the same worker arguments through the cold desktop worker class.
        fun cold(
            script: Path,
            out: Path,
        ): Pair<Int, String> {
            val p =
                ProcessBuilder(
                    ImportWorkerLaunch.command(
                        listOf(script.toString(), "--format", "glb", "--out", out.toString(), "--output", "json"),
                        60_000,
                        mainClass = DesktopRenderWorker.MAIN_CLASS,
                        maxHeap = "256m",
                    ),
                ).start()
            p.outputStream.close()
            val text = p.inputStream.readAllBytes().decodeToString()
            p.waitFor(90, TimeUnit.SECONDS)
            return p.exitValue() to text
        }

        "hello, two requests answered like the cold worker, then end of input ends the process".config(
            timeout = 300.seconds,
        ) {
            val dir = Files.createTempDirectory("warm-proc")
            val w = Worker(maxRuns = 5)
            try {
                WarmRenderProtocol.isHello(w.line()) shouldBe true
                val good = script(dir, "good", "val x = 1\n")
                val bad = script(dir, "bad", "error(\"boom\")\n")
                for ((id, s) in listOf(1L to good, 2L to bad)) {
                    val out = dir.resolve("o$id.glb")
                    val warm = w.request(id, s, out)
                    val (code, text) = cold(s, out)
                    warm.exit shouldBe code
                    warm.stdout.decodeToString() shouldBe text
                }
                w.process.outputStream.close()
                w.ended() shouldBe true
            } finally {
                w.close()
                dir.toFile().deleteRecursively()
            }
        }

        "maxRuns 1 ends the process after the first reply".config(timeout = 300.seconds) {
            val dir = Files.createTempDirectory("warm-proc")
            val w = Worker(maxRuns = 1)
            try {
                WarmRenderProtocol.isHello(w.line()) shouldBe true
                (w.request(1, script(dir, "a", "val x = 1\n"), dir.resolve("o.glb")).exit in 0..1) shouldBe true
                w.ended() shouldBe true
            } finally {
                w.close()
                dir.toFile().deleteRecursively()
            }
        }

        "a malformed request line ends the process" {
            val w = Worker(maxRuns = 5)
            try {
                WarmRenderProtocol.isHello(w.line()) shouldBe true
                w.send("garbage")
                w.ended() shouldBe true
                w.process.exitValue() shouldBe 2
            } finally {
                w.close()
            }
        }

        "a script that leaves a thread or a property behind asks for recycling and the process ends".config(
            timeout = 300.seconds,
        ) {
            val dir = Files.createTempDirectory("warm-proc")
            val w = Worker(maxRuns = 5)
            try {
                WarmRenderProtocol.isHello(w.line()) shouldBe true
                val leak = script(dir, "leak", "System.setProperty(\"kstep.leak\", \"1\")\n")
                val reply = w.request(1, leak, dir.resolve("o.glb"))
                reply.recycle shouldBe true
                reply.recycleReason shouldBe "properties:kstep.leak"
                w.ended() shouldBe true
            } finally {
                w.close()
                dir.toFile().deleteRecursively()
            }
        }

        "a clean script keeps the process, a second request is served by the same process".config(
            timeout = 300.seconds,
        ) {
            val dir = Files.createTempDirectory("warm-proc")
            val w = Worker(maxRuns = 5)
            try {
                WarmRenderProtocol.isHello(w.line()) shouldBe true
                val s = script(dir, "clean", "val x = 1\n")
                val first = w.request(1, s, dir.resolve("o.glb"))
                first.recycleReason shouldBe null
                w.process.isAlive shouldBe true
                w.request(2, s, dir.resolve("o.glb")).recycle shouldBe false
            } finally {
                w.close()
                dir.toFile().deleteRecursively()
            }
        }

        "an idle worker ends by itself".config(timeout = 300.seconds) {
            val w = Worker(maxRuns = 5, idleMillis = 1_000)
            try {
                WarmRenderProtocol.isHello(w.line()) shouldBe true
                w.ended() shouldBe true
            } finally {
                w.close()
            }
        }

        "the worker's own reply is not a hello" {
            WarmRenderProtocol.isHello("{}") shouldBe false
            DesktopWarmRenderWorker.MAIN_CLASS.shouldBeInstanceOf<String>()
        }
    })
