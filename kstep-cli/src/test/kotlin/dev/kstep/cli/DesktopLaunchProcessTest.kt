package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val isWindows =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("win")

/** Real process start with a fake `open` tool: no shell is involved (ADR-0083). */
class DesktopLaunchProcessTest :
    StringSpec({
        fun tool(
            dir: Path,
            body: String,
        ): Path {
            val t = dir.resolve("fake-open")
            Files.writeString(t, "#!/bin/sh\n$body\n")
            t.toFile().setExecutable(true)
            return t
        }

        fun run(
            env: DesktopLaunchEnv,
            path: String?,
            start: (List<String>) -> StartOutcome = { DesktopLauncher.startDetached(it) },
        ): Triple<Int, String, String> {
            val o = ByteArrayOutputStream()
            val e = ByteArrayOutputStream()
            val code =
                runDesktop(
                    CliCommand.Desktop(path),
                    env,
                    start,
                    PrintStream(e, true, "UTF-8"),
                    PrintStream(o, true, "UTF-8"),
                )
            return Triple(code, o.toString("UTF-8"), e.toString("UTF-8"))
        }

        "the tool receives exactly the argv and nothing is evaluated by a shell (skipped on Windows)".config(
            enabled = !isWindows,
            timeout = 60.seconds,
        ) {
            val dir = DesktopTestFiles.newDir()
            val app = DesktopTestFiles.fakeApp(dir)
            val record = dir.resolve("args.bin")
            val open = tool(dir, "for a in \"\$@\"; do printf '%s\\0' \"\$a\" >> '$record'; done")
            val file = dir.resolve("\$(touch pwned) x.kstep.kts").also { Files.writeString(it, "x") }
            val env = DesktopTestFiles.env(vars = mapOf("KSTEP_DESKTOP_APP" to app.toString()), openTool = open)
            val (code, out, _) = run(env, file.toString())
            code shouldBe 0
            out shouldContain "Started kSTEP Desktop with"
            val got = Files.readString(record).split('\u0000').dropLast(1)
            got shouldBe listOf("-n", "-a", app.toString(), "--args", file.toString())
            Files.exists(dir.resolve("pwned")) shouldBe false
            Files.exists(Path.of("pwned")) shouldBe false
        }

        "a tool exiting with 3 gives D-041 and exit 1 (skipped on Windows)".config(
            enabled = !isWindows,
            timeout = 60.seconds,
        ) {
            val dir = DesktopTestFiles.newDir()
            val app = DesktopTestFiles.fakeApp(dir)
            val open = tool(dir, "echo boom >&2; exit 3")
            val env = DesktopTestFiles.env(vars = mapOf("KSTEP_DESKTOP_APP" to app.toString()), openTool = open)
            val (code, _, err) = run(env, null)
            code shouldBe 1
            err shouldContain "KSTEP-D-041"
            err shouldContain "code 3"
        }

        "a tool that cannot be run gives D-041 (skipped on Windows)".config(
            enabled = !isWindows,
            timeout = 60.seconds,
        ) {
            val dir = DesktopTestFiles.newDir()
            val app = DesktopTestFiles.fakeApp(dir)
            val env =
                DesktopTestFiles.env(
                    vars = mapOf("KSTEP_DESKTOP_APP" to app.toString()),
                    openTool = dir.resolve("none"),
                )
            val (code, _, err) = run(env, null)
            code shouldBe 1
            err shouldContain "KSTEP-D-041"
        }

        "a tool that hangs is stopped after the timeout (skipped on Windows)".config(
            enabled = !isWindows,
            timeout = 60.seconds,
        ) {
            val dir = DesktopTestFiles.newDir()
            val app = DesktopTestFiles.fakeApp(dir)
            val open = tool(dir, "sleep 60")
            val env = DesktopTestFiles.env(vars = mapOf("KSTEP_DESKTOP_APP" to app.toString()), openTool = open)
            val (code, _, err) = run(env, null) { DesktopLauncher.startDetached(it, timeoutSeconds = 2) }
            code shouldBe 1
            err shouldContain "KSTEP-D-041"
            err shouldContain "2 seconds"
        }

        "a refused plan prints the code and exits 1 without starting anything" {
            var started = false
            val (code, _, err) =
                run(DesktopTestFiles.env(os = "Linux"), null) {
                    started = true
                    StartOutcome.Ok
                }
            code shouldBe 1
            started shouldBe false
            err shouldContain "error[KSTEP-D-039]"
        }
    })
