package dev.kstep.script.input

import dev.kstep.script.ScriptInputErrorKind
import dev.kstep.script.ScriptInputLimits
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The pure path rules for files a script reads next to itself (ADR-0080). Only `java.nio` is used, so the
 * desktop can apply the very same rules to a list reported by its worker process (it never trusts the worker).
 *
 * The refusals are about honesty and portability, not a security boundary: a script can read anything through
 * raw `java.io` (ADR-0062 D1). The security boundary is on the desktop side, which re-checks every reported
 * path and only ever asks for file attributes.
 */
object InputPathRules {
    /** Result of [resolve]. */
    sealed interface Resolution {
        /** [absolute] lies below the script folder; [relative] is normalised and `/`-separated. */
        data class Ok(
            val absolute: Path,
            val relative: String,
        ) : Resolution

        /** The path is refused; [reason] is a short, log-safe sentence. */
        data class Refused(
            val kind: ScriptInputErrorKind,
            val reason: String,
        ) : Resolution
    }

    private val driveLetter = Regex("^[A-Za-z]:")

    /**
     * Resolves [relative] against [baseReal], the real path (`toRealPath()`) of the script folder.
     * The base itself may be reached through links; every element below it must not be a link.
     */
    fun resolve(
        baseReal: Path,
        relative: String,
    ): Resolution {
        refuseInvalid(relative)?.let { return it }
        val rawElements = relative.split('/')
        if (rawElements.any { it == ".." }) {
            return refused(ScriptInputErrorKind.ESCAPE, "'..' is not allowed")
        }
        if (relative.startsWith("/") || driveLetter.containsMatchIn(relative)) {
            return refused(ScriptInputErrorKind.ABSOLUTE, "absolute paths are not allowed")
        }
        val elements = rawElements.filter { it.isNotEmpty() && it != "." }
        if (elements.isEmpty()) {
            return refused(ScriptInputErrorKind.INVALID, "the path names no file")
        }
        val parsed =
            try {
                elements.map { Path.of(it) }
            } catch (_: InvalidPathException) {
                return refused(ScriptInputErrorKind.INVALID, "the path is not valid on this system")
            }
        if (parsed.any { it.isAbsolute || it.root != null || it.nameCount != 1 }) {
            return refused(ScriptInputErrorKind.ABSOLUTE, "absolute paths are not allowed")
        }
        var target = baseReal
        parsed.forEach { target = target.resolve(it) }
        target = target.normalize()
        if (!target.startsWith(baseReal)) {
            return refused(ScriptInputErrorKind.ESCAPE, "the path leaves the script folder")
        }
        refuseByFileSystem(baseReal, parsed)?.let { return it }
        return Resolution.Ok(target, elements.joinToString("/"))
    }

    private fun refuseInvalid(relative: String): Resolution.Refused? =
        when {
            relative.isEmpty() -> Resolution.Refused(ScriptInputErrorKind.INVALID, "the path is empty")
            relative.length > ScriptInputLimits.MAX_PATH_CHARS ->
                Resolution.Refused(
                    ScriptInputErrorKind.INVALID,
                    "the path is longer than ${ScriptInputLimits.MAX_PATH_CHARS} characters",
                )
            relative.any { it.code < 0x20 || it.code == 0x7F } ->
                Resolution.Refused(ScriptInputErrorKind.INVALID, "the path contains a control character")
            relative.contains('\\') ->
                Resolution.Refused(ScriptInputErrorKind.INVALID, "use '/' as the separator, not '\\'")
            else -> null
        }

    private fun refused(
        kind: ScriptInputErrorKind,
        reason: String,
    ): Resolution.Refused = Resolution.Refused(kind, reason)

    // Walks from the base to the target without following links. The first element that does not exist ends
    // the walk (nothing below it can be a link). An existing target must be a regular file.
    private fun refuseByFileSystem(
        baseReal: Path,
        elements: List<Path>,
    ): Resolution.Refused? {
        var current = baseReal
        for ((index, element) in elements.withIndex()) {
            current = current.resolve(element)
            val attributes =
                try {
                    Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    return null
                }
            if (attributes.isSymbolicLink) {
                return refused(ScriptInputErrorKind.LINK, "symbolic links are not followed")
            }
            if (index == elements.lastIndex && !attributes.isRegularFile) {
                return refused(ScriptInputErrorKind.NOT_A_FILE, "the path is not a regular file")
            }
        }
        return null
    }
}
