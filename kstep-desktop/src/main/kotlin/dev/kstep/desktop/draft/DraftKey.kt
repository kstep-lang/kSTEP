package dev.kstep.desktop.draft

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/** Which draft file belongs to which script or note (ADR-0084): the name is a hash, never a path fragment. */
object DraftKey {
    /**
     * The absolute, normalized path with the parent folder resolved through links (so `/tmp` and `/private/tmp` agree,
     * also after the file itself was deleted). The file itself is not resolved: a link is its own key.
     */
    fun normalize(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val parent = absolute.parent ?: return absolute
        val name = absolute.fileName ?: return absolute
        return try {
            parent.toRealPath().resolve(name)
        } catch (_: Exception) {
            absolute
        }
    }

    /** Lowercase hex SHA-256 over the UTF-8 of the normalized path, plus `.draft`. A new digest per call. */
    fun fileName(normalized: Path): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(normalized.toString().toByteArray(StandardCharsets.UTF_8))
        return digest.toHex() + DraftPolicy.SUFFIX
    }
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xF]).append("0123456789abcdef"[b.toInt() and 0xF])
    }
    return sb.toString()
}
