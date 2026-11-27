package dev.kstep.desktop.mcp

import dev.kstep.desktop.ai.settings.defaultConfigDir
import dev.kstep.desktop.settings.PrivateConfigFile
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val logger = KotlinLogging.logger {}

/** The token could not be stored (`KSTEP-D-051`). Carries neither a message nor a stack trace. */
class McpTokenWriteException : RuntimeException(null, null, false, false)

/**
 * The bearer token of the MCP server. It never prints itself: `toString` is masked and equality is by identity, so it
 * cannot leak through a log line or a data-class dump.
 */
class McpToken internal constructor(
    internal val value: String,
) {
    /** Constant-time comparison of the bytes (a different length is answered early; the length is public). */
    fun matches(presented: String): Boolean =
        MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))

    override fun toString(): String = "McpToken(***)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Keeps the token in `mcp-token` in the desktop config folder (ADR-0095): 32 random bytes, base64url without padding
 * (43 characters), created lazily on the first switch-on. Loaded as untrusted input: a regular file (no symbolic link)
 * of at most 128 bytes with exactly that shape and, where POSIX permissions exist, exactly `rw-------`. A file that is
 * wider than that is not repaired silently: it counts as invalid and the next switch-on creates a new token.
 */
class McpTokenStore(
    private val dir: Path = defaultConfigDir(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val file: Path get() = dir.resolve(FILE_NAME)

    /** The stored token, or `null` when there is none or the file is not valid. Never throws. */
    @Synchronized
    fun current(): McpToken? {
        val text = PrivateConfigFile.readStrictUtf8(file, MAX_BYTES) ?: return null
        val candidate = text.trim()
        if (!TOKEN_SHAPE.matches(candidate)) return null
        if (!permissionsAreTight()) {
            logger.debug { "mcp token file ignored: permissions are wider than owner-only" }
            return null
        }
        return McpToken(candidate)
    }

    /** The stored token or a new one. @throws McpTokenWriteException when a new one cannot be stored. */
    @Synchronized
    fun getOrCreate(): McpToken = current() ?: regenerate()

    /** Always a new token, replacing the old one atomically. @throws McpTokenWriteException when it cannot be stored. */
    @Synchronized
    fun regenerate(): McpToken {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        if (!PrivateConfigFile.writeAtomic(dir, FILE_NAME, "mcp-token", value + "\n")) throw McpTokenWriteException()
        return McpToken(value)
    }

    private fun permissionsAreTight(): Boolean =
        try {
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) ==
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }

    companion object {
        const val FILE_NAME = "mcp-token"
        const val TOKEN_CHARS = 43
        const val MAX_BYTES = 128L
        private const val TOKEN_BYTES = 32
        private val TOKEN_SHAPE = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
