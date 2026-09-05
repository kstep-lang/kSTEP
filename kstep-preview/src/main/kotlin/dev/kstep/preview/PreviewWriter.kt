package dev.kstep.preview

import java.io.File
import java.io.IOException

/**
 * Thrown when the actual file write fails -- identical contract and message shape to the
 * `RenderIoException` this replaces (`failed to write '<path>': <detail>`), so
 * `CliRenderIntegrationTest`'s R20/R20b/R26 io_error assertions hold unchanged.
 */
class PreviewIoException(
    outPath: String,
    cause: IOException,
) : RuntimeException("failed to write '$outPath': ${cause.message ?: cause::class.simpleName}", cause)

/**
 * Writes a [PreviewOutcome.Rendered]'s bytes to disk -- the one piece of file I/O
 * [PreviewRenderer] itself deliberately does not perform (see that object's KDoc).
 */
object PreviewWriter {
    /**
     * `mkdirs()` on the parent, then an explicit `isDirectory` guard, then the write.
     *
     * The `isDirectory` guard is now load-bearing in a way it was not before the extraction: the
     * pre-extraction PNG branch relied on `ImageIO.write(RenderedImage, String, File)`'s internal
     * `output.delete()` being blocked by it. This writer never calls that overload at all --
     * `writeBytes` on a directory throws `FileNotFoundException`, i.e. a normal `IOException` --
     * so the guard is now belt AND braces rather than the only line of defence. R20b must stay
     * green either way.
     */
    fun write(
        outPath: String,
        bytes: ByteArray,
    ) {
        val target = File(outPath)
        try {
            target.parentFile?.mkdirs()
            if (target.isDirectory) {
                throw PreviewIoException(outPath, IOException("$outPath is a directory"))
            }
            target.writeBytes(bytes)
        } catch (e: IOException) {
            throw PreviewIoException(outPath, e)
        }
    }
}
