package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/** The argv of the sandbox wrappers and the detection, with injected OS and probe (no process is started). */
class WorkerSandboxCommandTest :
    StringSpec({
        val java = "/jdk/bin/java"

        "the macOS argv carries the paths only as -D values" {
            val s = WorkerSandbox.MacSandboxExec(WorkerSandbox.MAC_PROFILE, Path.of("/s"), Path.of("/t"))
            s.wrap(listOf(java, "-version")) shouldBe
                listOf(
                    "/usr/bin/sandbox-exec",
                    "-D",
                    "SESSION=/s",
                    "-D",
                    "TMP=/t",
                    "-D",
                    "JAVA_HOME=/jdk",
                    "-p",
                    WorkerSandbox.MAC_PROFILE,
                    java,
                    "-version",
                )
        }

        "the profile text contains no path of this run" {
            WorkerSandbox.MAC_PROFILE shouldNotContain "/Users"
            WorkerSandbox.MAC_PROFILE shouldNotContain "/var"
            WorkerSandbox.MAC_PROFILE shouldNotContain "kstep"
        }

        "paths with quotes, parentheses and spaces stay one argv element and never enter the profile" {
            val nasty = Path.of("/a \") (allow default) \"b")
            val s = WorkerSandbox.MacSandboxExec(WorkerSandbox.MAC_PROFILE, nasty, nasty)
            val argv = s.wrap(listOf(java))
            argv.contains("SESSION=$nasty") shouldBe true
            argv[argv.indexOf("-p") + 1] shouldBe WorkerSandbox.MAC_PROFILE
            argv.count { it.contains("allow default") } shouldBe 3
            val b = WorkerSandbox.Bubblewrap(Path.of("/usr/bin/bwrap"), nasty, nasty).wrap(listOf(java))
            b.contains(nasty.toString()) shouldBe true
        }

        "the bubblewrap argv: tmpfs before the bind, no network, dies with the parent" {
            val b = WorkerSandbox.Bubblewrap(Path.of("/usr/bin/bwrap"), Path.of("/s"), Path.of("/t"))
            b.wrap(listOf(java, "x")) shouldBe
                listOf(
                    "/usr/bin/bwrap",
                    "--ro-bind",
                    "/",
                    "/",
                    "--dev",
                    "/dev",
                    "--unshare-pid",
                    "--proc",
                    "/proc",
                    "--tmpfs",
                    "/tmp",
                    "--tmpfs",
                    "/run",
                    "--bind",
                    "/s",
                    "/s",
                    "--bind",
                    "/t",
                    "/t",
                    "--unshare-net",
                    "--unsetenv",
                    "DBUS_SESSION_BUS_ADDRESS",
                    "--new-session",
                    "--die-with-parent",
                    "--",
                    java,
                    "x",
                )
        }

        "an unavailable sandbox cannot wrap" {
            shouldThrow<IllegalStateException> { WorkerSandbox.Unavailable("none").wrap(listOf(java)) }
        }

        fun detect(
            os: String,
            probeResult: Int?,
            bwrapPresent: Boolean = true,
            calls: MutableList<List<String>> = mutableListOf(),
        ): WorkerSandbox {
            val dir = TestSupport.newDir()
            try {
                return WorkerSandbox.detect(
                    dir,
                    dir,
                    osName = os,
                    probe = {
                        calls.add(it)
                        probeResult
                    },
                    pathEnv = "/nowhere:/opt/bin",
                    isExecutable = {
                        it.toString() == "/usr/bin/sandbox-exec" ||
                            (bwrapPresent && it.fileName.toString() == "bwrap")
                    },
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "detect: Windows has no sandbox and starts no probe" {
            val calls = mutableListOf<List<String>>()
            detect("Windows 11", 0, calls = calls).shouldBeInstanceOf<WorkerSandbox.Unavailable>()
            calls.size shouldBe 0
        }

        "detect: macOS with a probe that succeeds" {
            val calls = mutableListOf<List<String>>()
            detect("Mac OS X", 0, calls = calls).shouldBeInstanceOf<WorkerSandbox.MacSandboxExec>()
            calls.size shouldBe 1
            calls.single().first() shouldBe "/usr/bin/sandbox-exec"
        }

        "detect: macOS with a failing probe (already sandboxed, exit 71) is unavailable" {
            detect("Mac OS X", 71).shouldBeInstanceOf<WorkerSandbox.Unavailable>()
        }

        "detect: Linux without bwrap is unavailable and starts no probe" {
            val calls = mutableListOf<List<String>>()
            detect("Linux", 0, bwrapPresent = false, calls = calls).shouldBeInstanceOf<WorkerSandbox.Unavailable>()
            calls.size shouldBe 0
        }

        "detect: Linux with bwrap and a probe that succeeds" {
            val calls = mutableListOf<List<String>>()
            detect("Linux", 0, calls = calls).shouldBeInstanceOf<WorkerSandbox.Bubblewrap>()
            calls.size shouldBe 1
        }

        "detect: Linux with a probe that timed out (null) or failed is unavailable" {
            detect("Linux", null).shouldBeInstanceOf<WorkerSandbox.Unavailable>()
            detect("Linux", 1).shouldBeInstanceOf<WorkerSandbox.Unavailable>()
        }

        "detect: an unknown OS is unavailable" {
            detect("Plan 9", 0).shouldBeInstanceOf<WorkerSandbox.Unavailable>()
        }

        "hideTmp = false drops only the tmpfs on /tmp and keeps every other flag in order (ADR-0062)" {
            val hidden =
                WorkerSandbox
                    .Bubblewrap(
                        Path.of("/usr/bin/bwrap"),
                        Path.of("/s"),
                        Path.of("/t"),
                    ).wrap(listOf(java))
            val open =
                WorkerSandbox
                    .Bubblewrap(Path.of("/usr/bin/bwrap"), Path.of("/s"), Path.of("/t"), hideTmp = false)
                    .wrap(listOf(java))
            hidden.zipWithNext().any { it == ("--tmpfs" to "/tmp") } shouldBe true
            open.zipWithNext().any { it == ("--tmpfs" to "/tmp") } shouldBe false
            open shouldBe
                hidden.take(hidden.indexOf("/tmp") - 1) + hidden.drop(hidden.indexOf("/tmp") + 1)
            open.contains("--tmpfs") shouldBe true
            open.zipWithNext().any { it == ("--tmpfs" to "/run") } shouldBe true
            open.contains("--unshare-net") shouldBe true
            open.contains("--unshare-pid") shouldBe true
        }

        "the AI default argv is byte-identical to the one before ADR-0062" {
            WorkerSandbox
                .Bubblewrap(
                    Path.of("/usr/bin/bwrap"),
                    Path.of("/s"),
                    Path.of("/t"),
                ).wrap(listOf(java)) shouldBe
                listOf(
                    "/usr/bin/bwrap",
                    "--ro-bind",
                    "/",
                    "/",
                    "--dev",
                    "/dev",
                    "--unshare-pid",
                    "--proc",
                    "/proc",
                    "--tmpfs",
                    "/tmp",
                    "--tmpfs",
                    "/run",
                    "--bind",
                    "/s",
                    "/s",
                    "--bind",
                    "/t",
                    "/t",
                    "--unshare-net",
                    "--unsetenv",
                    "DBUS_SESSION_BUS_ADDRESS",
                    "--new-session",
                    "--die-with-parent",
                    "--",
                    java,
                )
        }

        fun kindOf(
            os: String,
            probeResult: Int?,
            bwrapPresent: Boolean = true,
        ) = (detect(os, probeResult, bwrapPresent) as WorkerSandbox.Unavailable).kind

        "detect reports the kind of every unavailable case and keeps the reason strings" {
            kindOf("Windows 11", 0) shouldBe WorkerSandbox.UnavailableKind.WINDOWS
            kindOf("Linux", 0, bwrapPresent = false) shouldBe WorkerSandbox.UnavailableKind.BWRAP_MISSING
            kindOf("Linux", 1) shouldBe WorkerSandbox.UnavailableKind.BWRAP_PROBE_FAILED
            kindOf("Linux", null) shouldBe WorkerSandbox.UnavailableKind.BWRAP_PROBE_FAILED
            kindOf("Mac OS X", 71) shouldBe WorkerSandbox.UnavailableKind.MAC_PROBE_FAILED
            kindOf("Plan 9", 0) shouldBe WorkerSandbox.UnavailableKind.OTHER_OS
            (detect("Windows 11", 0) as WorkerSandbox.Unavailable).reason shouldBe "Windows: no worker sandbox"
            (detect("Linux", 1) as WorkerSandbox.Unavailable).reason shouldBe
                "Linux: bubblewrap (bwrap) not installed or not usable"
            (detect("Mac OS X", 71) as WorkerSandbox.Unavailable).reason shouldBe
                "sandbox-exec cannot apply the worker profile"
        }

        "detect(hideTmp = false) probes with the flags of the user flows" {
            val dir = TestSupport.newDir()
            try {
                val calls = mutableListOf<List<String>>()
                val found =
                    WorkerSandbox.detect(
                        dir,
                        dir,
                        osName = "Linux",
                        probe = {
                            calls.add(it)
                            0
                        },
                        pathEnv = "/opt/bin",
                        isExecutable = { it.fileName.toString() == "bwrap" },
                        hideTmp = false,
                    )
                found.shouldBeInstanceOf<WorkerSandbox.Bubblewrap>().hideTmp shouldBe false
                calls.single().zipWithNext().any { it == ("--tmpfs" to "/tmp") } shouldBe false
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
