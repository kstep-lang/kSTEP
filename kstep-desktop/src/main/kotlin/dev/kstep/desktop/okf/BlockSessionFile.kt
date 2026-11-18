package dev.kstep.desktop.okf

import dev.kstep.desktop.run.LiveRunController
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The temporary script a block is run from (ADR-0058, D8). Written by THIS code into the private session directory
 * (never next to the note), exclusively created (`CREATE_NEW`, so nothing is overwritten), mode 0600 where supported,
 * removed again on every path.
 */
object BlockSessionFile {
    fun createSessionDir(): Path = LiveRunController.createSessionDir()

    fun fileName(generation: Long): String = "block-$generation.kstep.kts"

    fun <T> withBlockFile(
        dir: Path,
        generation: Long,
        content: String,
        use: (Path) -> T,
    ): T {
        val file = dir.resolve(fileName(generation))
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        var created = false
        try {
            val channel =
                if ("posix" in dir.fileSystem.supportedFileAttributeViews()) {
                    FileChannel.open(
                        file,
                        options,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    FileChannel.open(file, options)
                }
            created = true
            channel.use {
                val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            return use(file)
        } finally {
            // only a file this call created is removed: a pre-existing one is never touched
            try {
                if (created) Files.deleteIfExists(file)
            } catch (_: Exception) {
                // the session directory is removed at the end anyway
            }
        }
    }
}
