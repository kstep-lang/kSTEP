package dev.kstep.script

import dev.kstep.script.input.InputPathRules
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Limits of the input-file helpers (ADR-0080). */
object ScriptInputLimits {
    /** Most distinct input files one script evaluation may name. */
    const val MAX_INPUT_FILES = 64

    /** Longest accepted relative path, in characters. */
    const val MAX_PATH_CHARS = 1024

    /** Default upper bound for [readInput], in bytes. */
    const val MAX_READ_BYTES = 16L * 1024 * 1024
}

/** Why an input-file helper refused or failed. [MISSING] and [TOO_LARGE] map to KSTEP-S-017, the rest to KSTEP-S-016. */
enum class ScriptInputErrorKind {
    INVALID,
    ABSOLUTE,
    ESCAPE,
    LINK,
    NOT_A_FILE,
    NO_SCRIPT_FOLDER,
    LIMIT,
    MISSING,
    TOO_LARGE,
}

/** Thrown by [inputFile] and [readInput]; the script host maps it to a structured outcome (never a stack trace). */
class ScriptInputException(
    val kind: ScriptInputErrorKind,
    message: String,
) : RuntimeException(message)

/**
 * Script DSL. Resolves [relativePath] against the folder of the running script, records it as a dependency and
 * returns the absolute path (the file need not exist yet; a missing file is still watched).
 *
 * Only files read through these helpers are watched by the desktop live preview. Files read through raw
 * java.io/java.nio are not observed. Resolution is relative to the script's folder, unlike `java.io.File("x")`,
 * which resolves against the working directory.
 *
 * Refused: empty or over-long paths, control characters, `\`, `..`, absolute paths, symbolic links below the
 * script folder, and anything that is not a regular file. Works only in script files (not inline source).
 */
fun inputFile(relativePath: String): Path = resolveAndRecordInput(relativePath)

/**
 * Script DSL. Same rules as [inputFile]: the path is recorded first, then the file is read as UTF-8 text without
 * following a link, bounded by [maxBytes].
 *
 * Only files read through these helpers are watched by the desktop live preview. Files read through raw
 * java.io/java.nio are not observed. Resolution is relative to the script's folder, unlike `java.io.File("x")`,
 * which resolves against the working directory.
 */
fun readInput(
    relativePath: String,
    maxBytes: Long = ScriptInputLimits.MAX_READ_BYTES,
): String {
    val path = resolveAndRecordInput(relativePath)
    val limit = maxBytes.coerceIn(0L, ScriptInputLimits.MAX_READ_BYTES)
    val shown = shortened(relativePath)
    try {
        Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes = Channels.newInputStream(channel).readNBytes((limit + 1).toInt())
            if (bytes.size > limit) {
                throw ScriptInputException(
                    ScriptInputErrorKind.TOO_LARGE,
                    "input file '$shown' is larger than $limit bytes",
                )
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    } catch (_: NoSuchFileException) {
        throw ScriptInputException(
            ScriptInputErrorKind.MISSING,
            "input file '$shown' not found next to the script",
        )
    } catch (e: java.io.IOException) {
        throw ScriptInputException(
            ScriptInputErrorKind.MISSING,
            "input file '$shown' cannot be read (${e::class.simpleName})",
        )
    }
}

private const val SHOWN_PATH_CHARS = 120

private fun shortened(text: String): String =
    text
        .map { if (it.code < 0x20 || it.code == 0x7F) '?' else it }
        .joinToString("")
        .let { if (it.length > SHOWN_PATH_CHARS) it.take(SHOWN_PATH_CHARS) + "..." else it }

private fun resolveAndRecordInput(relativePath: String): Path {
    val recorder =
        KStepScriptHost.currentRecorder()
            ?: throw ScriptInputException(
                ScriptInputErrorKind.NO_SCRIPT_FOLDER,
                "input files need a script file: inputFile/readInput work only in a *.kstep.kts file " +
                    "(not inline source) and must be called on the script's own thread",
            )
    return when (val resolution = InputPathRules.resolve(recorder.baseReal, relativePath)) {
        is InputPathRules.Resolution.Refused ->
            throw ScriptInputException(
                resolution.kind,
                "input file '${shortened(relativePath)}' refused: ${resolution.reason}",
            )
        is InputPathRules.Resolution.Ok -> {
            if (!recorder.record(resolution.relative)) {
                throw ScriptInputException(
                    ScriptInputErrorKind.LIMIT,
                    "a script may name at most ${ScriptInputLimits.MAX_INPUT_FILES} different input files",
                )
            }
            resolution.absolute
        }
    }
}
