package dev.kstep.cli

import dev.kstep.cli.okf.DisplayName
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Error codes of `kstep desktop` (ADR-0083); they continue the desktop range (KSTEP-D-0xx). */
object DesktopLaunchCodes {
    /** No usable kSTEP.app, or not macOS. */
    const val APP_NOT_FOUND = "KSTEP-D-039"

    /** The path argument fails the CLI checks. */
    const val PATH_REJECTED = "KSTEP-D-040"

    /** `/usr/bin/open` could not be run, exited non-zero, or timed out. */
    const val START_FAILED = "KSTEP-D-041"
}

/** A copy of the desktop's `DropEvaluator.ONLY_SUPPORTED` (the CLI cannot depend on kstep-desktop); a desktop test keeps both equal. */
object DesktopLaunchText {
    const val ONLY_SUPPORTED = "Only .kstep.kts scripts, .md notes or folders can be opened."
}

internal data class DesktopLaunchEnv(
    val osName: String,
    val env: (String) -> String?,
    val candidates: List<Path>,
    val openTool: Path = Path.of("/usr/bin/open"),
) {
    companion object {
        fun production(): DesktopLaunchEnv {
            val home = System.getProperty("user.home").orEmpty()
            val candidates =
                buildList {
                    add(Path.of("/Applications/kSTEP.app"))
                    if (home.isNotEmpty()) add(Path.of(home, "Applications", "kSTEP.app"))
                }
            return DesktopLaunchEnv(System.getProperty("os.name").orEmpty(), System::getenv, candidates)
        }
    }
}

internal sealed interface DesktopLaunchPlan {
    data class Start(
        val argv: List<String>,
        val app: Path,
    ) : DesktopLaunchPlan

    data class Refused(
        val code: String,
        val message: String,
    ) : DesktopLaunchPlan
}

internal sealed interface StartOutcome {
    data object Ok : StartOutcome

    data class Failed(
        val reason: String,
    ) : StartOutcome
}

/**
 * Plans the start of the packaged macOS app (ADR-0083). Only argv lists are built, never a shell command. The checks
 * of the path are a convenience for the user; the desktop app treats its own argument as untrusted again.
 */
internal object DesktopLauncher {
    const val APP_ENV = "KSTEP_DESKTOP_APP"
    const val BUNDLE_ID = "dev.kstep.desktop"
    const val MAX_PLIST_BYTES = 64L * 1024
    const val OPEN_TIMEOUT_SECONDS = 30L
    private const val MAX_PATH_CHARS = 4096
    private const val STDERR_MAX_BYTES = 4096
    private const val SCRIPT_SUFFIX = ".kstep.kts"
    private const val NOTE_SUFFIX = ".md"

    private val BUNDLE_ID_PATTERN =
        Regex("""<key>CFBundleIdentifier</key>\s*<string>dev\.kstep\.desktop</string>""")

    sealed interface Result {
        data class Ok(
            val path: Path,
        ) : Result

        data class Refused(
            val message: String,
        ) : Result
    }

    fun plan(
        path: String?,
        env: DesktopLaunchEnv,
    ): DesktopLaunchPlan {
        val file =
            if (path == null) {
                null
            } else {
                when (val checked = checkPath(path)) {
                    is Result.Ok -> checked.path
                    is Result.Refused ->
                        return DesktopLaunchPlan.Refused(DesktopLaunchCodes.PATH_REJECTED, checked.message)
                }
            }
        return when (val located = locateApp(env)) {
            is Result.Ok -> DesktopLaunchPlan.Start(argv(env.openTool, located.path, file), located.path)
            is Result.Refused -> DesktopLaunchPlan.Refused(DesktopLaunchCodes.APP_NOT_FOUND, located.message)
        }
    }

    fun argv(
        openTool: Path,
        app: Path,
        file: Path?,
    ): List<String> =
        buildList {
            add(openTool.toString())
            add("-n")
            add("-a")
            add(app.toString())
            if (file != null) {
                add("--args")
                add(file.toString())
            }
        }

    fun checkPath(raw: String): Result {
        if (raw.isEmpty() || raw.length > MAX_PATH_CHARS || raw.any { it.code < 0x20 || it.code == 0x7F }) {
            return Result.Refused("The path is empty, too long or contains control characters.")
        }
        val abs =
            try {
                Path.of(raw).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return Result.Refused("The path is not valid.")
            }
        val shown = DisplayName.escaped(abs.toString(), 120)
        val attrs =
            try {
                Files.readAttributes(abs, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return Result.Refused("$shown does not exist.")
            } catch (_: SecurityException) {
                return Result.Refused("$shown does not exist.")
            }
        val lower =
            abs.fileName
                ?.toString()
                .orEmpty()
                .lowercase(Locale.ROOT)
        return when {
            attrs.isSymbolicLink -> Result.Refused("$shown is a link and is not opened.")
            attrs.isDirectory -> Result.Ok(abs)
            attrs.isRegularFile && (lower.endsWith(SCRIPT_SUFFIX) || lower.endsWith(NOTE_SUFFIX)) -> Result.Ok(abs)
            else -> Result.Refused(DesktopLaunchText.ONLY_SUPPORTED)
        }
    }

    fun locateApp(env: DesktopLaunchEnv): Result {
        if (!env.osName.lowercase(Locale.ROOT).contains("mac")) {
            return Result.Refused(
                "kstep desktop starts the packaged macOS app; Linux and Windows have no package yet (ADR-0082). " +
                    "Start from source: ./gradlew :kstep-desktop:run --args=\"<path>\"",
            )
        }
        val fromEnv = env.env(APP_ENV)?.takeIf { it.isNotEmpty() }
        if (fromEnv != null) {
            val app =
                try {
                    validApp(Path.of(fromEnv))
                } catch (_: InvalidPathException) {
                    null
                }
            return if (app != null) {
                Result.Ok(app)
            } else {
                Result.Refused("$APP_ENV does not point to a valid kSTEP Desktop app (bundle $BUNDLE_ID).")
            }
        }
        for (candidate in env.candidates) {
            validApp(candidate)?.let { return Result.Ok(it) }
        }
        return Result.Refused(
            "kSTEP Desktop was not found in /Applications or ~/Applications. Build it with " +
                "./gradlew clean :kstep-desktop:packageDmg -Pkstep.native.exclude=true and copy kSTEP.app there, " +
                "or set $APP_ENV to the app.",
        )
    }

    private fun validApp(candidate: Path): Path? =
        try {
            val real = candidate.toRealPath()
            val plist = real.resolve("Contents").resolve("Info.plist")
            val launcher = real.resolve("Contents").resolve("MacOS").resolve("kSTEP")
            val plistOk =
                Files.isRegularFile(plist, LinkOption.NOFOLLOW_LINKS) &&
                    Files.size(plist) <= MAX_PLIST_BYTES &&
                    BUNDLE_ID_PATTERN.containsMatchIn(Files.readString(plist))
            if (Files.isDirectory(real) &&
                real.fileName.toString().endsWith(".app") &&
                plistOk &&
                Files.isRegularFile(launcher) &&
                Files.isExecutable(launcher)
            ) {
                real
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }

    /** Starts [argv] without a shell and without the environment's secrets leaving the process; waits for the hand-over only. */
    fun startDetached(
        argv: List<String>,
        timeoutSeconds: Long = OPEN_TIMEOUT_SECONDS,
    ): StartOutcome {
        var errFile: File? = null
        var process: Process? = null
        return try {
            errFile = File.createTempFile("kstep-open", ".err")
            process =
                ProcessBuilder(argv)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(errFile)
                    .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                StartOutcome.Failed("the start did not finish within $timeoutSeconds seconds")
            } else if (process.exitValue() != 0) {
                val text =
                    errFile.inputStream().use { String(it.readNBytes(STDERR_MAX_BYTES), Charsets.UTF_8) }.trim()
                val detail = if (text.isEmpty()) "" else ": " + DisplayName.escaped(text, 200)
                StartOutcome.Failed("open exited with code ${process.exitValue()}$detail")
            } else {
                StartOutcome.Ok
            }
        } catch (e: IOException) {
            StartOutcome.Failed("open could not be run (${e::class.simpleName})")
        } catch (e: SecurityException) {
            StartOutcome.Failed("open could not be run (${e::class.simpleName})")
        } catch (_: InterruptedException) {
            process?.destroy()
            Thread.currentThread().interrupt()
            StartOutcome.Failed("interrupted")
        } finally {
            try {
                errFile?.delete()
            } catch (_: SecurityException) {
                // nothing to do
            }
        }
    }
}

/** Runs `kstep desktop`; returns the exit code and never calls `exitProcess`. [start] is injectable for tests. */
internal fun runDesktop(
    command: CliCommand.Desktop,
    env: DesktopLaunchEnv = DesktopLaunchEnv.production(),
    start: (List<String>) -> StartOutcome = { DesktopLauncher.startDetached(it) },
    err: PrintStream = System.err,
    out: PrintStream = System.out,
): Int {
    when (val plan = DesktopLauncher.plan(command.path, env)) {
        is DesktopLaunchPlan.Refused -> {
            err.println("kstep desktop: error[${plan.code}]: ${plan.message}")
            return 1
        }
        is DesktopLaunchPlan.Start ->
            when (val outcome = start(plan.argv)) {
                StartOutcome.Ok -> {
                    val name =
                        command.path?.let {
                            Path
                                .of(it)
                                .fileName
                                ?.toString()
                                ?.let { n -> DisplayName.escaped(n, 80) }
                        }
                    out.println(if (name == null) "Started kSTEP Desktop." else "Started kSTEP Desktop with $name.")
                    return 0
                }
                is StartOutcome.Failed -> {
                    err.println(
                        "kstep desktop: error[${DesktopLaunchCodes.START_FAILED}]: " +
                            "kSTEP Desktop could not be started: ${outcome.reason}",
                    )
                    return 1
                }
            }
    }
}
