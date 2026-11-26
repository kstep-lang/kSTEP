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
                unsaved: WarmRenderProtocol.Unsaved? = null,
            ): WarmRenderProtocol.Reply {
                send(
                    WarmRenderProtocol.encodeRequest(
                        WarmRenderProtocol.Request(id, script.toString(), out.toString(), unsaved),
                    ),
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
            extra: List<String> = emptyList(),
        ): Pair<Int, String> {
            val p =
                ProcessBuilder(
                    ImportWorkerLaunch.command(
                        extra +
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

        // A buffer outside the script folder reads the input files of the script folder (ADR-0086).
        val bufferScript =
            """
            check(readInput("data.txt") == "hello")
            val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
            val prodCtx = productContext { name = "e"; frameOfReference = appCtx; disciplineType = "m" }.getOrThrow()
            val bracket = product("B-1") { name = "B"; frameOfReference = setOf(prodCtx) }.getOrThrow()
            stepFile(fileName = "t.step") { root(bracket) }
            """.trimIndent() + "\n"

        fun unsavedFor(
            buffer: Path,
            scriptDir: Path,
        ): WarmRenderProtocol.Unsaved {
            val hash =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(Files.readAllBytes(buffer))
                    .joinToString("") { "%02x".format(it) }
            return WarmRenderProtocol.Unsaved(scriptDir.toString(), "part.kstep.kts", hash)
        }

        "an unsaved buffer reads its input files from the script folder, not from the buffer folder, warm and cold"
            .config(timeout = 300.seconds) {
                val bufDir = Files.createTempDirectory("warm-buf").toRealPath()
                val scriptDir = Files.createTempDirectory("warm-script").toRealPath()
                val w = Worker(maxRuns = 5)
                try {
                    WarmRenderProtocol.isHello(w.line()) shouldBe true
                    Files.writeString(scriptDir.resolve("data.txt"), "hello")
                    val buffer =
                        bufDir.resolve(
                            java.util.UUID
                                .randomUUID()
                                .toString() + ".kstep.kts",
                        )
                    Files.writeString(buffer, bufferScript)
                    val unsaved = unsavedFor(buffer, scriptDir)
                    val warm = w.request(1, buffer, bufDir.resolve("o.glb"), unsaved)
                    warm.exit shouldBe 0
                    warm.stdout.decodeToString().contains("data.txt") shouldBe true
                    val cold =
                        cold(
                            buffer,
                            bufDir.resolve("c.glb"),
                            listOf(
                                "--unsaved-script-dir",
                                scriptDir.toString(),
                                "--unsaved-display-name",
                                "part.kstep.kts",
                                "--unsaved-sha256",
                                unsaved.sha256,
                            ),
                        )
                    cold.first shouldBe 0
                    // Counter-test: the file exists only next to the buffer, not in the script folder.
                    Files.delete(scriptDir.resolve("data.txt"))
                    Files.writeString(bufDir.resolve("data.txt"), "hello")
                    val missing = w.request(2, buffer, bufDir.resolve("o2.glb"), unsaved)
                    missing.exit shouldBe 1
                    missing.stdout.decodeToString().contains("KSTEP-S-017") shouldBe true
                } finally {
                    w.close()
                    bufDir.toFile().deleteRecursively()
                    scriptDir.toFile().deleteRecursively()
                }
            }

        "a tampered buffer is refused with the marker and the process is recycled".config(timeout = 300.seconds) {
            val bufDir = Files.createTempDirectory("warm-buf").toRealPath()
            val scriptDir = Files.createTempDirectory("warm-script").toRealPath()
            val w = Worker(maxRuns = 5)
            try {
                WarmRenderProtocol.isHello(w.line()) shouldBe true
                val buffer =
                    bufDir.resolve(
                        java.util.UUID
                            .randomUUID()
                            .toString() + ".kstep.kts",
                    )
                Files.writeString(buffer, "val x = 1\n")
                val unsaved = unsavedFor(buffer, scriptDir)
                Files.writeString(buffer, "val x = 2\n")
                val reply = w.request(1, buffer, bufDir.resolve("o.glb"), unsaved)
                reply.exit shouldBe 2
                reply.stderr.decodeToString().trim() shouldBe "kstep-unsaved-refused:BUFFER_HASH"
                reply.recycle shouldBe true
                w.ended() shouldBe true
            } finally {
                w.close()
                bufDir.toFile().deleteRecursively()
                scriptDir.toFile().deleteRecursively()
            }
        }

        "the worker's own reply is not a hello" {
            WarmRenderProtocol.isHello("{}") shouldBe false
            DesktopWarmRenderWorker.MAIN_CLASS.shouldBeInstanceOf<String>()
        }
    })
