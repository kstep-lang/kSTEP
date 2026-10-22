package dev.kstep.shape.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

private const val MINIMAL_HEADER =
    "ISO-10303-21;\nHEADER;\nFILE_DESCRIPTION((),'2;1');\n" +
        "FILE_NAME('part.step','2026-01-01T00:00:00',('me'),('org'),'pre','CAD Tool','');\n" +
        "FILE_SCHEMA(('AUTOMOTIVE_DESIGN { 1 0 10303 214 1 1 1 1 }'));\nENDSEC;\nDATA;\n"
private const val FOOTER = "ENDSEC;\nEND-ISO-10303-21;\n"

private fun step(data: String): ByteArray = (MINIMAL_HEADER + data + FOOTER).toByteArray(Charsets.ISO_8859_1)

/** OCCT-free coverage of the import input hardening (ADR-0030). */
class StepInputValidationTest :
    StringSpec({
        val dir = File("build/step-input-validation").apply { mkdirs() }

        fun kindOf(block: () -> Unit): StepImportErrorKind = shouldThrow<StepInputException> { block() }.kind

        "a missing file is file_not_found" {
            kindOf { StepInputValidation.readValidated(File(dir, "nope.step").path) } shouldBe
                StepImportErrorKind.FILE_NOT_FOUND
        }

        "a directory is not a regular file" {
            kindOf { StepInputValidation.readValidated(dir.path) } shouldBe StepImportErrorKind.NOT_A_REGULAR_FILE
        }

        "a symbolic link to a valid file is rejected" {
            val target = File(dir, "link-target.step").apply { writeBytes(step("#1=PRODUCT('a','b',\$,());\n")) }
            val link = File(dir, "link.step")
            Files.deleteIfExists(link.toPath())
            val created =
                try {
                    Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath())
                    true
                } catch (e: Exception) {
                    false
                }
            if (created) {
                kindOf { StepInputValidation.readValidated(link.path) } shouldBe StepImportErrorKind.SYMLINK_REJECTED
            }
        }

        "a FIFO is not a regular file" {
            val fifo = File(dir, "fifo.step")
            fifo.delete()
            val ok =
                try {
                    ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0
                } catch (e: Exception) {
                    false
                }
            if (ok) {
                kindOf { StepInputValidation.readValidated(fifo.path) } shouldBe StepImportErrorKind.NOT_A_REGULAR_FILE
                fifo.delete()
            }
        }

        "an empty file is rejected" {
            val file = File(dir, "empty.step").apply { writeBytes(ByteArray(0)) }
            kindOf { StepInputValidation.readValidated(file.path) } shouldBe StepImportErrorKind.EMPTY_FILE
        }

        "a file above the limit is rejected, at the limit it is accepted" {
            val file = File(dir, "limit.step").apply { writeBytes(ByteArray(100) { 'a'.code.toByte() }) }
            kindOf { StepInputValidation.readValidated(file.path, maxBytes = 99) } shouldBe
                StepImportErrorKind.FILE_TOO_LARGE
            StepInputValidation.readValidated(file.path, maxBytes = 100).size shouldBe 100
        }

        "a sparse file above the default limit is rejected without reading it" {
            val file = File(dir, "sparse.step")
            file.delete()
            RandomAccessFile(file, "rw").use { it.setLength(dev.kstep.geometry.StepImportLimits.MAX_FILE_BYTES + 1) }
            try {
                kindOf { StepInputValidation.readValidated(file.path) } shouldBe StepImportErrorKind.FILE_TOO_LARGE
            } finally {
                file.delete()
            }
        }

        "a NUL byte in the path is an invalid path" {
            kindOf { StepInputValidation.readValidated("a\u0000b.step") } shouldBe StepImportErrorKind.INVALID_PATH
        }

        "error messages name only the given path" {
            val e = shouldThrow<StepInputException> { StepInputValidation.readValidated("does/not/exist.step") }
            e.message shouldBe "file not found: does/not/exist.step"
        }

        "non-STEP content is not_step, with hints for STEP-XML and ZIP" {
            kindOf { StepInputValidation.prescan("hello world".toByteArray()) } shouldBe StepImportErrorKind.NOT_STEP
            kindOf {
                StepInputValidation.prescan(
                    byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
                )
            } shouldBe
                StepImportErrorKind.NOT_STEP
            shouldThrow<StepInputException> { StepInputValidation.prescan("<?xml version=\"1.0\"?><x/>".toByteArray()) }
                .message!! shouldContain "STEP-XML"
            shouldThrow<StepInputException> {
                StepInputValidation.prescan(
                    byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4),
                )
            }.message!! shouldContain "stpZ"
        }

        "a UTF-8 BOM and leading white space before the header are accepted" {
            val bytes =
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '\n'.code.toByte(), ' '.code.toByte()) +
                    step("")
            StepInputValidation.prescan(bytes).schemas.size shouldBe 1
        }

        "DOCUMENT_FILE as an entity is rejected as an external reference" {
            val e =
                shouldThrow<StepInputException> {
                    StepInputValidation.prescan(
                        step("#1=DOCUMENT_FILE('x','y',\$,\$,\$,\$);\n"),
                    )
                }
            e.kind shouldBe StepImportErrorKind.EXTERNAL_REFERENCE_REJECTED
        }

        "the other external-reference entities are rejected too, in any letter case" {
            val names =
                listOf(
                    "APPLIED_DOCUMENT_REFERENCE",
                    "product_definition_with_associated_documents",
                    "PDWAD",
                    "apdcrf",
                    "DCMFL",
                    "DCMNT",
                )
            for (name in names) {
                kindOf { StepInputValidation.prescan(step("#1=$name('x');\n")) } shouldBe
                    StepImportErrorKind.EXTERNAL_REFERENCE_REJECTED
            }
        }

        "DOCUMENT_FILE inside a string literal or a comment is not an external reference" {
            val data = "#1=PRODUCT('DOCUMENT_FILE','it''s DOCUMENT_FILE',\$,());\n/* DOCUMENT_FILE */\n"
            StepInputValidation.prescan(step(data)).schemas.size shouldBe 1
        }

        "ignored categories are counted" {
            val data =
                "#1=STYLED_ITEM('',(#2),#3);\n#4=STYLED_ITEM('',(#2),#3);\n" +
                    "#5=PRESENTATION_LAYER_ASSIGNMENT('a','b',(#2));\n" +
                    "#6=GEOMETRIC_TOLERANCE('t','',#7,#8);\n#9=DATUM('d','',#7,.F.,'A');\n"
            StepInputValidation.prescan(step(data)).ignoredCategories shouldBe
                mapOf("colours" to 2, "layers" to 1, "pmi" to 2)
        }

        "header info is extracted" {
            val scan = StepInputValidation.prescan(step(""))
            scan.schemas shouldBe listOf("AUTOMOTIVE_DESIGN { 1 0 10303 214 1 1 1 1 }")
            scan.headerFileName shouldBe "part.step"
            scan.originatingSystem shouldBe "CAD Tool"
        }

        "header strings are truncated and stripped of control characters" {
            val longName = "n".repeat(1000)
            val text =
                MINIMAL_HEADER.replace("'part.step'", "'$longName\u0007x'") + FOOTER
            val scan = StepInputValidation.prescan(text.toByteArray(Charsets.ISO_8859_1))
            scan.headerFileName!!.length shouldBe 256
            scan.headerFileName!! shouldNotContain "\u0007"
        }
    })
