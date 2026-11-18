package dev.kstep.desktop.export

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

sealed interface ValidationResult {
    data class Ok(
        val bytes: Long,
    ) : ValidationResult

    data class Invalid(
        val reason: String,
    ) : ValidationResult
}

/**
 * Checks the worker's output before it is moved to the user's target (ADR-0057): a regular file (no link), not empty,
 * not larger than [DEFAULT_MAX_BYTES], and the magic bytes of the format. The worker evaluated an arbitrary script, so
 * its output is checked, not trusted.
 */
object ExportOutputValidator {
    const val DEFAULT_MAX_BYTES: Long = 512L shl 20
    private const val HEAD_BYTES = 84
    private const val STL_HEADER = 80
    private const val STL_TRIANGLE = 50L
    private val step = "ISO-10303-21;".toByteArray(Charsets.US_ASCII)
    private val glb = "glTF".toByteArray(Charsets.US_ASCII)
    private val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun validate(
        file: Path,
        format: ExportFormat,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): ValidationResult {
        val attrs =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return ValidationResult.Invalid("the worker wrote no file")
            }
        if (!attrs.isRegularFile) return ValidationResult.Invalid("the worker output is not a regular file")
        val size = attrs.size()
        if (size <= 0) return ValidationResult.Invalid("the worker output is empty")
        if (size > maxBytes) return ValidationResult.Invalid("the file is larger than ${maxBytes shr 20} MiB")
        val head =
            try {
                readHead(file)
            } catch (_: IOException) {
                return ValidationResult.Invalid("the worker output cannot be read")
            }
        val ok =
            when (format) {
                ExportFormat.STEP -> isStep(head)
                ExportFormat.GLB -> head.startsWith(glb)
                ExportFormat.THREE_MF -> head.startsWith(zip)
                ExportFormat.STL -> isBinaryStl(head, size) || isAsciiStl(head)
            }
        return if (ok) {
            ValidationResult.Ok(
                size,
            )
        } else {
            ValidationResult.Invalid("the file is not a valid ${format.label} file")
        }
    }

    private fun readHead(file: Path): ByteArray =
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(HEAD_BYTES)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill up to HEAD_BYTES
            }
            buffer.array().copyOf(buffer.position())
        }

    private fun ByteArray.startsWith(
        prefix: ByteArray,
        from: Int = 0,
    ): Boolean = size >= from + prefix.size && prefix.indices.all { this[from + it] == prefix[it] }

    private fun isStep(head: ByteArray): Boolean {
        var i = if (head.startsWith(bom)) bom.size else 0
        while (i < head.size && head[i].toInt().toChar().isWhitespace()) i++
        return head.startsWith(step, i)
    }

    private fun isBinaryStl(
        head: ByteArray,
        size: Long,
    ): Boolean {
        if (head.size < HEAD_BYTES) return false
        val count =
            ByteBuffer
                .wrap(head, STL_HEADER, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong() and 0xFFFFFFFFL
        return HEAD_BYTES + STL_TRIANGLE * count == size
    }

    private fun isAsciiStl(head: ByteArray): Boolean {
        var i = 0
        while (i < head.size && head[i].toInt().toChar().isWhitespace()) i++
        return head.startsWith("solid".toByteArray(Charsets.US_ASCII), i)
    }
}
