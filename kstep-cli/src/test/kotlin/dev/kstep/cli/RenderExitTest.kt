package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** `runRender` under [RenderExit.capture] ends a request, not the JVM (ADR-0081). */
class RenderExitTest :
    StringSpec({
        fun renderIn(
            dir: java.nio.file.Path,
            name: String,
            text: String,
        ): Pair<Int, String> {
            val script = dir.resolve("$name.kstep.kts")
            Files.writeString(script, text)
            val command =
                resolveCommand(
                    arrayOf(
                        "render",
                        script.toString(),
                        "--format",
                        "glb",
                        "--out",
                        dir.resolve("o.glb").toString(),
                        "--output",
                        "json",
                    ),
                ) as CliCommand.Render
            val saved = System.out
            val buffer = ByteArrayOutputStream()
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            try {
                val code = RenderExit.capture { runRender(command, includeParts = true) }
                return code to buffer.toString(Charsets.UTF_8)
            } finally {
                System.setOut(saved)
            }
        }

        "a compile error, a runtime error and a missing file end the request with 1 and leave the JVM alive".config(
            timeout = 180.seconds,
        ) {
            val dir = Files.createTempDirectory("exit-test")
            try {
                val compile = renderIn(dir, "c", "val x: Int = \"no\"\n")
                compile.first shouldBe 1
                compile.second.contains("\"status\":\"error\"") shouldBe true
                val runtime = renderIn(dir, "r", "error(\"boom\")\n")
                runtime.first shouldBe 1
                runtime.second.contains("boom") shouldBe true
                Files.delete(dir.resolve("r.kstep.kts"))
                val command =
                    resolveCommand(
                        arrayOf("render", dir.resolve("r.kstep.kts").toString(), "--format", "glb", "--output", "json"),
                    ) as CliCommand.Render
                val saved = System.out
                System.setOut(PrintStream(ByteArrayOutputStream(), true))
                try {
                    RenderExit.capture { runRender(command, includeParts = true) } shouldBe 1
                } finally {
                    System.setOut(saved)
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "capture returns 0 for a block that returns and removes the handler" {
            RenderExit.capture { } shouldBe 0
            RenderExit.capture { RenderExit.exit(5) } shouldBe 5
            // a nested thread without handler would exit the process, so only check the handler is thread-local
            var other = -1
            val t = Thread { other = RenderExit.capture { RenderExit.exit(9) } }
            t.start()
            t.join()
            other shouldBe 9
        }
    })
