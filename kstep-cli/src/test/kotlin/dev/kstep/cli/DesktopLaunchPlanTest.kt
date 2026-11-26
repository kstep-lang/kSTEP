package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

internal object DesktopTestFiles {
    private val base: Path =
        Path.of("build", "desktop-launch-test").toAbsolutePath().also { Files.createDirectories(it) }

    fun newDir(): Path = Files.createTempDirectory(base, "t").toRealPath()

    fun fakeApp(
        dir: Path,
        name: String = "kSTEP.app",
        bundleId: String = "dev.kstep.desktop",
        executable: Boolean = true,
        withLauncher: Boolean = true,
    ): Path {
        val app = dir.resolve(name)
        Files.createDirectories(app.resolve("Contents/MacOS"))
        Files.writeString(
            app.resolve("Contents/Info.plist"),
            "<plist><dict>\n<key>CFBundleIdentifier</key>\n  <string>$bundleId</string>\n</dict></plist>",
        )
        if (withLauncher) {
            val launcher = app.resolve("Contents/MacOS/kSTEP")
            Files.writeString(launcher, "#!/bin/sh\n")
            launcher.toFile().setExecutable(executable)
        }
        return app
    }

    fun env(
        os: String = "Mac OS X",
        vars: Map<String, String> = emptyMap(),
        candidates: List<Path> = emptyList(),
        openTool: Path = Path.of("/usr/bin/open"),
    ) = DesktopLaunchEnv(os, { vars[it] }, candidates, openTool)
}

class DesktopLaunchPlanTest :
    StringSpec({
        fun start(plan: DesktopLaunchPlan) = plan.shouldBeInstanceOf<DesktopLaunchPlan.Start>()

        fun refused(
            plan: DesktopLaunchPlan,
            code: String,
        ): String {
            val r = plan.shouldBeInstanceOf<DesktopLaunchPlan.Refused>()
            r.code shouldBe code
            return r.message
        }

        "a valid app from the environment gives the exact argv with and without a file" {
            val dir = DesktopTestFiles.newDir()
            val app = DesktopTestFiles.fakeApp(dir)
            val file = dir.resolve("a.kstep.kts").also { Files.writeString(it, "x") }
            val env = DesktopTestFiles.env(vars = mapOf("KSTEP_DESKTOP_APP" to app.toString()))
            start(DesktopLauncher.plan(null, env)).argv shouldBe listOf("/usr/bin/open", "-n", "-a", app.toString())
            start(DesktopLauncher.plan(file.toString(), env)).argv shouldBe
                listOf("/usr/bin/open", "-n", "-a", app.toString(), "--args", file.toString())
        }

        "the first valid candidate wins and an invalid first one is skipped" {
            val dir = DesktopTestFiles.newDir()
            val bad =
                DesktopTestFiles.fakeApp(
                    dir.resolve("a").also { Files.createDirectories(it) },
                    bundleId = "other.app",
                )
            val good = DesktopTestFiles.fakeApp(dir.resolve("b").also { Files.createDirectories(it) })
            val env = DesktopTestFiles.env(candidates = listOf(bad, good))
            start(DesktopLauncher.plan(null, env)).app shouldBe good
        }

        "an invalid KSTEP_DESKTOP_APP does not fall back to the candidates" {
            val dir = DesktopTestFiles.newDir()
            val good = DesktopTestFiles.fakeApp(dir)
            val env =
                DesktopTestFiles.env(
                    vars = mapOf("KSTEP_DESKTOP_APP" to dir.resolve("typo.app").toString()),
                    candidates = listOf(good),
                )
            refused(DesktopLauncher.plan(null, env), "KSTEP-D-039") shouldContain "KSTEP_DESKTOP_APP"
        }

        "a wrong bundle id, a missing or non-executable launcher, a big plist or a name without .app are refused" {
            val dir = DesktopTestFiles.newDir()

            fun check(app: Path) =
                refused(
                    DesktopLauncher.plan(
                        null,
                        DesktopTestFiles.env(vars = mapOf("KSTEP_DESKTOP_APP" to app.toString())),
                    ),
                    "KSTEP-D-039",
                )
            check(DesktopTestFiles.fakeApp(dir.resolve("1").also { Files.createDirectories(it) }, bundleId = "x.y"))
            check(DesktopTestFiles.fakeApp(dir.resolve("2").also { Files.createDirectories(it) }, withLauncher = false))
            check(DesktopTestFiles.fakeApp(dir.resolve("3").also { Files.createDirectories(it) }, executable = false))
            val big = DesktopTestFiles.fakeApp(dir.resolve("4").also { Files.createDirectories(it) })
            Files.writeString(
                big.resolve("Contents/Info.plist"),
                "<key>CFBundleIdentifier</key><string>dev.kstep.desktop</string>" + " ".repeat(70_000),
            )
            check(big)
            check(DesktopTestFiles.fakeApp(dir.resolve("5").also { Files.createDirectories(it) }, name = "kSTEP"))
        }

        "nothing found gives D-039 with the build hint" {
            val env = DesktopTestFiles.env(candidates = listOf(Path.of("/nonexistent/kSTEP.app")))
            refused(DesktopLauncher.plan(null, env), "KSTEP-D-039") shouldContain "packageDmg"
        }

        "a non-mac system gives D-039 with the gradle hint" {
            for (os in listOf("Linux", "Windows 11")) {
                refused(DesktopLauncher.plan(null, DesktopTestFiles.env(os = os)), "KSTEP-D-039") shouldContain
                    ":kstep-desktop:run"
            }
        }

        "unusual path names become one verbatim argv element" {
            val dir = DesktopTestFiles.newDir()
            val app = DesktopTestFiles.fakeApp(dir)
            val env = DesktopTestFiles.env(vars = mapOf("KSTEP_DESKTOP_APP" to app.toString()))
            val names =
                listOf(
                    "a b.kstep.kts",
                    "q\"uote.kstep.kts",
                    "s'ingle.kstep.kts",
                    "\$(touch pwned).kstep.kts",
                    "semi;colon.kstep.kts",
                    "back`tick`.kstep.kts",
                    "and && more.kstep.kts",
                    "umlaut-äöü.kstep.kts",
                    "rtl-‮.kstep.kts",
                    "-n.kstep.kts",
                )
            for (name in names) {
                val file = dir.resolve(name).also { Files.writeString(it, "x") }
                val plan = start(DesktopLauncher.plan(file.toString(), env))
                plan.argv.last() shouldBe file.toString()
                plan.argv.size shouldBe 6
            }
            // relative path with a leading dash: normalized to an absolute one
            val rel = DesktopLauncher.checkPath("./-n.kstep.kts")
            rel.shouldBeInstanceOf<DesktopLauncher.Result.Refused>() // not in the working directory
        }

        "the path checks refuse what is not an openable file" {
            val dir = DesktopTestFiles.newDir()
            val app = DesktopTestFiles.fakeApp(dir)
            val env = DesktopTestFiles.env(vars = mapOf("KSTEP_DESKTOP_APP" to app.toString()))
            val script = dir.resolve("s.kstep.kts").also { Files.writeString(it, "x") }
            val link = dir.resolve("link.kstep.kts")
            val linked =
                try {
                    Files.createSymbolicLink(link, script)
                    true
                } catch (_: Exception) {
                    false
                }
            val txt = dir.resolve("n.txt").also { Files.writeString(it, "x") }
            val bad =
                buildList {
                    add("")
                    add("a\nb.kstep.kts")
                    add("a\u0000b.kstep.kts")
                    add("x".repeat(4097))
                    add(dir.resolve("missing.kstep.kts").toString())
                    add(txt.toString())
                    if (linked) add(link.toString())
                }
            for (raw in bad) {
                val message = refused(DesktopLauncher.plan(raw, env), "KSTEP-D-040")
                message.none { it.code < 0x20 && it != '\n' } shouldBe true
                message shouldNotContain "\u0000"
            }
            DesktopLauncher.checkPath(dir.toString()).shouldBeInstanceOf<DesktopLauncher.Result.Ok>()
        }

        "upper-case endings are accepted" {
            val dir = DesktopTestFiles.newDir()
            val md = dir.resolve("N.MD").also { Files.writeString(it, "x") }
            val kts = dir.resolve("S.KSTEP.KTS").also { Files.writeString(it, "x") }
            DesktopLauncher.checkPath(md.toString()).shouldBeInstanceOf<DesktopLauncher.Result.Ok>()
            DesktopLauncher.checkPath(kts.toString()).shouldBeInstanceOf<DesktopLauncher.Result.Ok>()
        }
    })
