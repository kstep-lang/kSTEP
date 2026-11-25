package dev.kstep.desktop.run

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The OS sandbox of the AI candidate check (ADR-0059). A candidate script is arbitrary Kotlin that runs before anyone
 * read it, so its worker gets no network and may write only to its own session folder. Reading stays possible
 * (ADR-0059, D1). Paths are never spliced into a profile text: they travel as `-D` parameters or as argv elements.
 *
 * [Unavailable] is a result, not an error: the caller must not run the candidate at all (fail closed).
 */
sealed interface WorkerSandbox {
    /** The argv that starts [command] inside the sandbox. [Unavailable] throws [IllegalStateException]. */
    fun wrap(command: List<String>): List<String>

    /** macOS `sandbox-exec` with [profile]; [session] and [tmp] are the only writable folders. */
    data class MacSandboxExec(
        val profile: String,
        val session: Path,
        val tmp: Path,
    ) : WorkerSandbox {
        override fun wrap(command: List<String>): List<String> =
            listOf(
                SANDBOX_EXEC,
                "-D",
                "SESSION=$session",
                "-D",
                "TMP=$tmp",
                "-D",
                "JAVA_HOME=${javaHomeOf(command.first())}",
                "-p",
                profile,
            ) + command
    }

    /** Linux bubblewrap at [path]; [session] and [tmp] are the only writable folders. */
    data class Bubblewrap(
        val path: Path,
        val session: Path,
        val tmp: Path,
        /** False for the user's own flows (ADR-0062): /tmp stays readable so scripts and inputs there are found. */
        val hideTmp: Boolean = true,
    ) : WorkerSandbox {
        override fun wrap(command: List<String>): List<String> =
            listOf(
                path.toString(),
                "--ro-bind",
                "/",
                "/",
                "--dev",
                "/dev",
                // --unshare-pid is required: without it the proc mount is owned by the host's init user namespace
                // and an unprivileged bwrap (Debian, Ubuntu, Fedora) fails with EPERM. It also hides the parent's
                // /proc/<ppid>/environ from the candidate.
                "--unshare-pid",
                "--proc",
                "/proc",
            ) + (if (hideTmp) listOf("--tmpfs", "/tmp") else emptyList()) +
                listOf(
                    "--tmpfs",
                    "/run",
                    "--bind",
                    session.toString(),
                    session.toString(),
                    "--bind",
                    tmp.toString(),
                    tmp.toString(),
                    "--unshare-net",
                    "--unsetenv",
                    "DBUS_SESSION_BUS_ADDRESS",
                    "--new-session",
                    "--die-with-parent",
                    "--",
                ) + command
    }

    /** Why no sandbox is available; drives the visible notice of the user flows (ADR-0062). */
    enum class UnavailableKind {
        WINDOWS,
        BWRAP_MISSING,
        BWRAP_PROBE_FAILED,
        MAC_EXEC_MISSING,
        MAC_PROBE_FAILED,
        OTHER_OS,
        CHECK_FAILED,
    }

    /** No sandbox on this system; the candidate must not be run. */
    data class Unavailable(
        val reason: String,
        val kind: UnavailableKind = UnavailableKind.CHECK_FAILED,
    ) : WorkerSandbox {
        override fun wrap(command: List<String>): List<String> = throw IllegalStateException(reason)
    }

    companion object {
        const val SANDBOX_EXEC: String = "/usr/bin/sandbox-exec"

        /**
         * No network, no writes except the session folder, the worker temp folder and /dev, no starting of programs
         * except the JDK (this also blocks `open`, `osascript`, `curl`, `security`), no LaunchServices (a browser
         * started by it would run outside the sandbox), no user preference writes (`cfprefsd` would write
         * `~/Library/Preferences` for it, outside the session folder), no creating of any process at all (`process-fork`:
         * macOS has no PID namespace, so a child of the candidate would outlive the worker and keep writing to the
         * session folder while the desktop works there; the worker is one process, so nothing legitimate needs it).
         */
        const val MAC_PROFILE: String =
            "(version 1)(allow default)(deny network*)(deny file-write*)" +
                "(allow file-write* (subpath (param \"SESSION\")) (subpath (param \"TMP\")) (subpath \"/dev\"))" +
                "(deny process-exec*)(allow process-exec* (subpath (param \"JAVA_HOME\")))" +
                "(deny mach-lookup (global-name \"com.apple.coreservices.launchservicesd\"))" +
                "(deny user-preference-write)" +
                "(deny process-fork)"

        private const val PROBE_TIMEOUT_MILLIS = 30_000L

        internal fun javaHomeOf(java: String): String {
            val p = Path.of(java)
            val real = runCatching { p.toRealPath() }.getOrDefault(p.toAbsolutePath().normalize())
            return (real.parent?.parent ?: real).toString()
        }

        /**
         * Finds out whether this system has a usable worker sandbox for [sessionDir] / [tempBase]. Runs a real probe
         * (one short process), so call it off the UI thread. Never throws; any doubt yields [Unavailable].
         */
        fun detect(
            sessionDir: Path,
            tempBase: Path,
            osName: String = System.getProperty("os.name").orEmpty(),
            probe: (List<String>) -> Int? = { WorkerEnvironment.probe(it, PROBE_TIMEOUT_MILLIS) },
            pathEnv: String? = System.getenv("PATH"),
            isExecutable: (Path) -> Boolean = Files::isExecutable,
            hideTmp: Boolean = true,
            java: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        ): WorkerSandbox =
            try {
                detectUnchecked(sessionDir, tempBase, osName, probe, pathEnv, isExecutable, hideTmp, java)
            } catch (e: Exception) {
                Unavailable("worker sandbox check failed (${e.javaClass.simpleName})", UnavailableKind.CHECK_FAILED)
            }

        private fun detectUnchecked(
            sessionDir: Path,
            tempBase: Path,
            osName: String,
            probe: (List<String>) -> Int?,
            pathEnv: String?,
            isExecutable: (Path) -> Boolean,
            hideTmp: Boolean,
            java: String,
        ): WorkerSandbox {
            val os = osName.lowercase()
            if (os.startsWith("windows")) return Unavailable("Windows: no worker sandbox", UnavailableKind.WINDOWS)
            val session = sessionDir.toRealPath()
            val tmp = tempBase.toRealPath()
            return when {
                os.startsWith("mac") -> {
                    if (!isExecutable(Path.of(SANDBOX_EXEC))) {
                        Unavailable("sandbox-exec not found", UnavailableKind.MAC_EXEC_MISSING)
                    } else {
                        val candidate = MacSandboxExec(MAC_PROFILE, session, tmp)
                        if (probe(candidate.wrap(listOf(java, "-version"))) == 0) {
                            candidate
                        } else {
                            Unavailable(
                                "sandbox-exec cannot apply the worker profile",
                                UnavailableKind.MAC_PROBE_FAILED,
                            )
                        }
                    }
                }
                os.startsWith("linux") -> {
                    val bwrap = findBwrap(pathEnv, isExecutable)
                    if (bwrap == null) {
                        Unavailable(
                            "Linux: bubblewrap (bwrap) not installed or not usable",
                            UnavailableKind.BWRAP_MISSING,
                        )
                    } else {
                        val candidate = Bubblewrap(bwrap, session, tmp, hideTmp)
                        if (probe(candidate.wrap(listOf("/bin/true"))) == 0) {
                            candidate
                        } else {
                            Unavailable(
                                "Linux: bubblewrap (bwrap) not installed or not usable",
                                UnavailableKind.BWRAP_PROBE_FAILED,
                            )
                        }
                    }
                }
                else -> Unavailable("$osName: no worker sandbox", UnavailableKind.OTHER_OS)
            }
        }

        private fun findBwrap(
            pathEnv: String?,
            isExecutable: (Path) -> Boolean,
        ): Path? {
            val preferred = Path.of("/usr/bin/bwrap")
            if (isExecutable(preferred)) return preferred
            return pathEnv
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() && Path.of(it).isAbsolute }
                .map { Path.of(it, "bwrap") }
                .firstOrNull(isExecutable)
        }
    }
}
