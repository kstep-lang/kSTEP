package dev.kstep.desktop.drop

import dev.kstep.desktop.run.DesktopError
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** What the first command-line argument of the desktop app asks for (ADR-0083). */
sealed interface StartRequest {
    data object None : StartRequest

    /** [ignoredArgs] counts the arguments after the first one. */
    data class Open(
        val path: Path,
        val ignoredArgs: Int,
    ) : StartRequest

    data class Rejected(
        val error: DesktopError,
    ) : StartRequest
}

/**
 * The start argument is untrusted input like a dropped path: it is only parsed here, never touched on the file system.
 * [DropEvaluator] then judges type, ending and links exactly as for a drop.
 */
object StartArgument {
    private const val MAX_CHARS = 4096

    fun parse(args: Array<String>): StartRequest {
        val raw = args.firstOrNull() ?: return StartRequest.None
        if (raw.isEmpty() || raw.length > MAX_CHARS || raw.any { it.code < 0x20 || it.code == 0x7F }) {
            return StartRequest.Rejected(
                DropEvaluator.reject("The start path is empty, too long or contains control characters."),
            )
        }
        val path =
            try {
                Path.of(raw).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return StartRequest.Rejected(DropEvaluator.reject("The start path is not valid."))
            }
        return StartRequest.Open(path, args.size - 1)
    }
}
