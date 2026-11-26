package dev.kstep.desktop.draft

import dev.kstep.desktop.ai.settings.defaultConfigDir
import dev.kstep.desktop.run.DesktopErrorCodes
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

private val logger = KotlinLogging.logger {}

/**
 * The crash drafts in `<config>/drafts/` (ADR-0084): one file per script, named by a hash of its path. Everything in the
 * folder is untrusted on load (it may have been planted): links are never followed, the owner and the permissions are
 * checked, sizes are limited before reading, and a draft is bound to its name twice (hash and header path). Nothing
 * here throws; a failure is a result. Logs carry codes and reasons, never a path, a name or content.
 *
 * [currentOwner] is injectable so that "a folder of somebody else" can be tested without `chown`.
 */
open class DraftStore(
    private val dir: Path = defaultConfigDir().resolve(DraftPolicy.DIR_NAME),
    private val clock: () -> Long = System::currentTimeMillis,
    private val currentOwner: (() -> UserPrincipal?)? = null,
) {
    sealed interface Read {
        data object None : Read

        class Found(
            val draft: Draft,
        ) : Read

        class Rejected(
            val reason: RejectReason,
        ) : Read

        data object Unavailable : Read
    }

    sealed interface Write {
        data object Written : Write

        data object TooLarge : Write

        data object Failed : Write

        data object Unavailable : Write
    }

    private class Prepared(
        val posix: Boolean,
        val owner: UserPrincipal?,
    )

    private val prepared: Prepared? by lazy { ensureDir() }

    /** The folder is there and trustworthy; prepared once. */
    val available: Boolean get() = prepared != null

    fun read(normalized: Path): Read {
        val ready = prepared ?: return Read.Unavailable
        return try {
            val file = dir.resolve(DraftKey.fileName(normalized))
            val attrs = inspect(file, ready) ?: return Read.None
            if (attrs is Verdict.Reject) return Read.Rejected(attrs.reason)
            val bytes =
                Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
                    Channels.newInputStream(it).readNBytes(DraftPolicy.MAX_FILE_BYTES + 1)
                }
            when (val parsed = DraftFormat.parse(bytes)) {
                is DraftParse.Rejected -> reject(file, parsed.reason)
                is DraftParse.Ok ->
                    if (parsed.draft.path != normalized ||
                        DraftKey.fileName(parsed.draft.path) != file.fileName.toString()
                    ) {
                        reject(file, RejectReason.NAME_MISMATCH)
                    } else {
                        Read.Found(parsed.draft)
                    }
            }
        } catch (_: Exception) {
            Read.Unavailable
        }
    }

    open fun write(draft: Draft): Write {
        val ready = prepared ?: return Write.Unavailable
        if (draft.body.length > DraftPolicy.MAX_BODY_BYTES ||
            draft.body.toByteArray(Charsets.UTF_8).size > DraftPolicy.MAX_BODY_BYTES
        ) {
            return Write.TooLarge
        }
        var tmp: Path? = null
        return try {
            val bytes = DraftFormat.serialize(draft)
            tmp =
                if (ready.posix) {
                    Files.createTempFile(
                        dir,
                        ".draft-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createTempFile(dir, ".draft-", ".tmp")
                }
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { ch ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) ch.write(buffer)
                ch.force(true)
            }
            Files.move(
                tmp,
                dir.resolve(DraftKey.fileName(draft.path)),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            tmp = null
            enforceCount()
            Write.Written
        } catch (_: IllegalArgumentException) {
            Write.TooLarge
        } catch (e: Exception) {
            logger.warn {
                "${DesktopErrorCodes.DRAFT_WRITE_FAILED}: the draft could not be written (${e::class.simpleName})"
            }
            Write.Failed
        } finally {
            try {
                tmp?.let { Files.deleteIfExists(it) }
            } catch (_: Exception) {
                // nothing to do
            }
        }
    }

    /** Deletes the draft of [normalized]; a link is removed, never its target. */
    fun delete(normalized: Path) {
        if (prepared == null) return
        try {
            val file = dir.resolve(DraftKey.fileName(normalized))
            val attrs = readAttributes(file, prepared!!.posix) ?: return
            if (attrs.isRegularFile || attrs.isSymbolicLink) Files.delete(file)
        } catch (_: Exception) {
            logger.debug { "a draft could not be deleted" }
        }
    }

    /** Removes drafts older than 30 days, the oldest above 32, broken ones and stale temp files. */
    fun housekeeping() {
        val ready = prepared ?: return
        try {
            val now = clock()
            val valid = ArrayList<Pair<Path, Draft>>()
            for ((file, isTemp) in entries()) {
                if (isTemp) {
                    val attrs = readAttributes(file, ready.posix) ?: continue
                    if ((attrs.isRegularFile || attrs.isSymbolicLink) &&
                        now - attrs.lastModifiedTime().toMillis() > DraftPolicy.MAX_TEMP_AGE_MS
                    ) {
                        Files.delete(file)
                    }
                    continue
                }
                val header = header(file, ready) ?: continue
                if (now - header.written > DraftPolicy.MAX_AGE_MS) {
                    Files.delete(file)
                } else {
                    valid += file to header
                }
            }
            if (valid.size > DraftPolicy.MAX_DRAFTS) {
                valid.sortedBy { it.second.written }.take(valid.size - DraftPolicy.MAX_DRAFTS).forEach {
                    Files.delete(it.first)
                }
            }
        } catch (_: Exception) {
            logger.debug { "draft housekeeping failed" }
        }
    }

    /** The header (no body) of the most recently written valid draft whose path passes [accept]; broken ones are skipped. */
    fun newestHeader(accept: (Path) -> Boolean = { true }): Draft? {
        val ready = prepared ?: return null
        return try {
            entries()
                .filter { !it.second }
                .mapNotNull { header(it.first, ready) }
                .filter { accept(it.path) }
                .maxByOrNull { it.written }
        } catch (_: Exception) {
            null
        }
    }

    private fun enforceCount() {
        try {
            if (entries().count { !it.second } > DraftPolicy.MAX_DRAFTS) housekeeping()
        } catch (_: Exception) {
            // best effort
        }
    }

    /** The direct entries of the folder with draft or temp names (bounded); foreign names are never touched. */
    private fun entries(): List<Pair<Path, Boolean>> {
        val result = ArrayList<Pair<Path, Boolean>>()
        Files.newDirectoryStream(dir).use { stream: DirectoryStream<Path> ->
            var seen = 0
            for (entry in stream) {
                if (++seen > DraftPolicy.MAX_DIR_ENTRIES_SCANNED) break
                val name = entry.fileName.toString()
                when {
                    DraftPolicy.NAME.matches(name) -> result += entry to false
                    DraftPolicy.TEMP_NAME.matches(name) -> result += entry to true
                }
            }
        }
        return result
    }

    /** The validated header of a draft file, or `null` (a broken file is deleted). */
    private fun header(
        file: Path,
        ready: Prepared,
    ): Draft? {
        val verdict = inspect(file, ready) ?: return null
        if (verdict is Verdict.Reject) return null
        val prefix =
            Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
                Channels.newInputStream(it).readNBytes(DraftPolicy.MAX_HEADER_BYTES + 2)
            }
        val parsed = DraftFormat.parseHeaderOnly(prefix)
        if (parsed !is DraftParse.Ok) {
            reject(file, (parsed as DraftParse.Rejected).reason)
            return null
        }
        if (DraftKey.fileName(parsed.draft.path) != file.fileName.toString()) {
            reject(file, RejectReason.NAME_MISMATCH)
            return null
        }
        return parsed.draft
    }

    private sealed interface Verdict {
        data object Ok : Verdict

        class Reject(
            val reason: RejectReason,
        ) : Verdict
    }

    /**
     * `null` if the file does not exist; [Verdict.Reject] (after deleting the offender, except for non-files) if it is
     * a link, of another owner, readable or writable by others, or too large.
     */
    private fun inspect(
        file: Path,
        ready: Prepared,
    ): Verdict? {
        val attrs = readAttributes(file, ready.posix) ?: return null
        if (attrs.isSymbolicLink) return rejectVerdict(file, RejectReason.LINK)
        if (!attrs.isRegularFile) {
            logger.warn { "${DesktopErrorCodes.DRAFT_REJECTED}: ${RejectReason.NOT_REGULAR}" }
            return Verdict.Reject(RejectReason.NOT_REGULAR)
        }
        if (attrs is PosixFileAttributes) {
            if (ready.owner != null && attrs.owner() != ready.owner) return rejectVerdict(file, RejectReason.OWNER)
            val loose =
                attrs.permissions().any {
                    it == PosixFilePermission.GROUP_READ ||
                        it == PosixFilePermission.GROUP_WRITE ||
                        it == PosixFilePermission.GROUP_EXECUTE ||
                        it == PosixFilePermission.OTHERS_READ ||
                        it == PosixFilePermission.OTHERS_WRITE ||
                        it == PosixFilePermission.OTHERS_EXECUTE
                }
            if (loose) return rejectVerdict(file, RejectReason.PERMISSIONS)
        }
        if (attrs.size() > DraftPolicy.MAX_FILE_BYTES) return rejectVerdict(file, RejectReason.TOO_LARGE)
        return Verdict.Ok
    }

    private fun readAttributes(
        file: Path,
        posix: Boolean,
    ): BasicFileAttributes? =
        try {
            if (posix) {
                Files.readAttributes(file, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } else {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
        } catch (_: NoSuchFileException) {
            null
        }

    private fun rejectVerdict(
        file: Path,
        reason: RejectReason,
    ): Verdict.Reject {
        reject(file, reason)
        return Verdict.Reject(reason)
    }

    /** Logs the reason (never the path), deletes the file or link itself and answers [Read.Rejected]. */
    private fun reject(
        file: Path,
        reason: RejectReason,
    ): Read.Rejected {
        logger.warn { "${DesktopErrorCodes.DRAFT_REJECTED}: a draft was refused ($reason)" }
        try {
            val name = file.fileName.toString()
            if (file.parent == dir && (DraftPolicy.NAME.matches(name) || DraftPolicy.TEMP_NAME.matches(name))) {
                val attrs = readAttributes(file, prepared?.posix == true)
                if (attrs != null && (attrs.isRegularFile || attrs.isSymbolicLink)) Files.delete(file)
            }
        } catch (_: Exception) {
            // nothing to do
        }
        return Read.Rejected(reason)
    }

    /** Creates or checks the folder; `null` (with one log line) if it cannot be trusted. */
    private fun ensureDir(): Prepared? =
        try {
            val parent = dir.parent
            if (parent != null && !Files.isDirectory(parent)) Files.createDirectories(parent)
            val posix = (parent ?: dir).let { Files.getFileStore(it).supportsFileAttributeView("posix") }
            val owner = if (posix) (currentOwner?.invoke() ?: probeOwner(parent ?: dir)) else null
            if (posix && owner == null) {
                unavailable()
            } else {
                var attrs = readAttributes(dir, posix)
                if (attrs == null) {
                    Files.createDirectory(
                        dir,
                        *(
                            if (posix) {
                                arrayOf(
                                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                                )
                            } else {
                                emptyArray()
                            }
                        ),
                    )
                    attrs = readAttributes(dir, posix)
                }
                when {
                    attrs == null || attrs.isSymbolicLink || !attrs.isDirectory -> unavailable()
                    attrs is PosixFileAttributes && attrs.owner() != owner -> unavailable()
                    else -> {
                        if (attrs is PosixFileAttributes &&
                            attrs.permissions() != PosixFilePermissions.fromString("rwx------")
                        ) {
                            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
                        }
                        Prepared(posix, owner)
                    }
                }
            }
        } catch (_: Exception) {
            unavailable()
        }

    private fun unavailable(): Prepared? {
        logger.warn { "${DesktopErrorCodes.DRAFT_STORE_UNAVAILABLE}: the draft folder is not usable" }
        return null
    }

    companion object {
        /** The owner of a fresh file in [folder]; `null` if it cannot be determined. No JDK-internal user API (jlink). */
        internal fun probeOwner(folder: Path): UserPrincipal? =
            try {
                val probe = Files.createTempFile(folder, ".probe-", ".tmp")
                try {
                    Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS)
                } finally {
                    Files.deleteIfExists(probe)
                }
            } catch (_: Exception) {
                null
            }
    }
}
