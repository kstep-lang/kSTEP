package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopErrorCodes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** What is already at the chosen target. */
data class ExistingFile(
    val bytes: Long,
)

/** Result of looking at a chosen target path before anything is run (ADR-0057). */
sealed interface TargetCheck {
    /** [file] is the target inside the real (symlink-resolved) directory [dir]; [existing] is set if it already exists. */
    data class Ok(
        val dir: Path,
        val file: Path,
        val existing: ExistingFile?,
    ) : TargetCheck

    data class Invalid(
        val code: String,
        val message: String,
    ) : TargetCheck
}

/** Checks a target path. Pure `java.nio.file`, no Compose. Messages carry the file name only, never a full path. */
object ExportTargets {
    private const val PROBE_PREFIX = ".kstep-probe-"

    fun check(chosen: Path): TargetCheck {
        val fileName = chosen.fileName?.toString()
        if (fileName.isNullOrEmpty() || fileName == "." || fileName == "..") {
            return invalid("the target has no file name")
        }
        if (fileName.any { it == '/' || it == '\\' || it == '\u0000' }) return invalid("the file name is not valid")
        if (fileName.toByteArray(Charsets.UTF_8).size > ExportNaming.MAX_FILE_NAME_BYTES) {
            return invalid("the file name is too long")
        }
        val parent = chosen.toAbsolutePath().normalize().parent ?: return invalid("the target has no folder")
        val dir =
            try {
                parent.toRealPath()
            } catch (_: IOException) {
                return invalid("the folder does not exist")
            }
        if (!Files.isDirectory(dir)) return invalid("the target folder is not a folder")
        val file = dir.resolve(fileName)
        val attrs =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                null
            } catch (_: IOException) {
                return invalid("the target cannot be read")
            }
        if (attrs != null) {
            if (attrs.isSymbolicLink) return invalid("$fileName is a link; choose a regular file name")
            if (!attrs.isRegularFile) return invalid("$fileName is not a regular file")
        }
        if (!canCreateIn(dir)) {
            return TargetCheck.Invalid(
                DesktopErrorCodes.EXPORT_TARGET_NOT_WRITABLE,
                "the folder ${dir.fileName ?: dir} is not writable",
            )
        }
        return TargetCheck.Ok(dir, file, attrs?.let { ExistingFile(it.size()) })
    }

    private fun invalid(message: String) = TargetCheck.Invalid(DesktopErrorCodes.EXPORT_TARGET_INVALID, message)

    /** Creates and removes a probe file: `isWritable` alone is wrong on read-only mounts and with ACLs. */
    private fun canCreateIn(dir: Path): Boolean =
        try {
            Files.deleteIfExists(Files.createTempFile(dir, PROBE_PREFIX, ".tmp"))
            true
        } catch (_: IOException) {
            false
        }
}
