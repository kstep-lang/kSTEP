package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ExportOutputValidatorTest :
    StringSpec({
        fun check(
            format: ExportFormat,
            bytes: ByteArray,
            max: Long = ExportOutputValidator.DEFAULT_MAX_BYTES,
        ): ValidationResult {
            val dir = TestSupport.newDir()
            try {
                val file = Files.write(dir.resolve("out"), bytes)
                return ExportOutputValidator.validate(file, format, max)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        fun binaryStl(triangles: Int): ByteArray {
            val body = ByteArray(84 + 50 * triangles)
            ByteBuffer.wrap(body, 80, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(triangles)
            return body
        }

        "STEP needs the Part 21 magic, a byte order mark and leading blanks are allowed" {
            check(ExportFormat.STEP, "ISO-10303-21;\nHEADER;".toByteArray()).shouldBeInstanceOf<ValidationResult.Ok>()
            check(
                ExportFormat.STEP,
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "  ISO-10303-21;".toByteArray(),
            ).shouldBeInstanceOf<ValidationResult.Ok>()
            check(ExportFormat.STEP, "hello world".toByteArray()).shouldBeInstanceOf<ValidationResult.Invalid>()
        }

        "GLB needs the glTF magic" {
            check(ExportFormat.GLB, "glTF....".toByteArray()).shouldBeInstanceOf<ValidationResult.Ok>()
            check(ExportFormat.GLB, "PK\u0003\u0004".toByteArray()).shouldBeInstanceOf<ValidationResult.Invalid>()
        }

        "3MF needs the zip magic" {
            check(ExportFormat.THREE_MF, byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0))
                .shouldBeInstanceOf<ValidationResult.Ok>()
            check(ExportFormat.THREE_MF, "glTF".toByteArray()).shouldBeInstanceOf<ValidationResult.Invalid>()
        }

        "binary STL needs a matching triangle count, ASCII STL starts with solid" {
            check(ExportFormat.STL, binaryStl(3)).shouldBeInstanceOf<ValidationResult.Ok>()
            val wrong = binaryStl(3).also { ByteBuffer.wrap(it, 80, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(99) }
            check(ExportFormat.STL, wrong).shouldBeInstanceOf<ValidationResult.Invalid>()
            check(
                ExportFormat.STL,
                "  solid cube\nendsolid cube\n".toByteArray(),
            ).shouldBeInstanceOf<ValidationResult.Ok>()
            check(ExportFormat.STL, "garbage".toByteArray()).shouldBeInstanceOf<ValidationResult.Invalid>()
        }

        "an empty file and a file over the cap are refused" {
            check(ExportFormat.STEP, ByteArray(0)).shouldBeInstanceOf<ValidationResult.Invalid>()
            check(ExportFormat.STEP, "ISO-10303-21;".toByteArray() + ByteArray(100), max = 50)
                .shouldBeInstanceOf<ValidationResult.Invalid>()
        }

        "a missing file and a symbolic link are refused" {
            val dir = TestSupport.newDir()
            try {
                ExportOutputValidator
                    .validate(dir.resolve("none"), ExportFormat.STEP)
                    .shouldBeInstanceOf<ValidationResult.Invalid>()
                val real = Files.write(dir.resolve("real"), "ISO-10303-21;".toByteArray())
                try {
                    val link = Files.createSymbolicLink(dir.resolve("link"), real)
                    ExportOutputValidator
                        .validate(
                            link,
                            ExportFormat.STEP,
                        ).shouldBeInstanceOf<ValidationResult.Invalid>()
                } catch (_: UnsupportedOperationException) {
                    // no links on this platform
                } catch (_: IOException) {
                    // no permission for links
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "the size of a valid file is reported" {
            (check(ExportFormat.GLB, "glTF".toByteArray() + ByteArray(10)) as ValidationResult.Ok).bytes shouldBe 14L
        }
    })
