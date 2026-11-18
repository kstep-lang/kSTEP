package dev.kstep.desktop.edit

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

class EditorTextTest :
    StringSpec({
        fun ok(bytes: ByteArray) = EditorText.decode(bytes).shouldBeInstanceOf<EditorText.Loaded.Ok>()

        fun refused(bytes: ByteArray) = EditorText.decode(bytes).shouldBeInstanceOf<EditorText.Loaded.Refused>()

        "exactly 1 MiB loads, one byte more is refused without reading the file" {
            val dir = TestSupport.newDir()
            val exact =
                dir
                    .resolve(
                        "a.kstep.kts",
                    ).also { Files.write(it, ByteArray(EditorText.MAX_BYTES) { 'a'.code.toByte() }) }
            // lines of 20,000 characters are the other limit: use newlines
            val text = ("a".repeat(100) + "\n").repeat(EditorText.MAX_BYTES / 101)
            val padded = text + "b".repeat(EditorText.MAX_BYTES - text.length)
            Files.write(exact, padded.toByteArray())
            EditorText.load(exact).shouldBeInstanceOf<EditorText.Loaded.Ok>()
            val big =
                dir
                    .resolve(
                        "b.kstep.kts",
                    ).also { Files.write(it, ByteArray(EditorText.MAX_BYTES + 1) { 'a'.code.toByte() }) }
            EditorText.load(big).shouldBeInstanceOf<EditorText.Loaded.Refused>().code shouldBe
                DesktopErrorCodes.EDITOR_TOO_LARGE
        }

        "a missing file is refused with the file-missing code" {
            EditorText
                .load(
                    TestSupport.newDir().resolve("nope.kstep.kts"),
                ).shouldBeInstanceOf<EditorText.Loaded.Refused>()
                .code shouldBe
                DesktopErrorCodes.FILE_MISSING
        }

        "NUL bytes and invalid UTF-8 are refused as not text" {
            refused("a\u0000b".toByteArray()).code shouldBe DesktopErrorCodes.EDITOR_NOT_TEXT
            refused(byteArrayOf(0x61, 0xC3.toByte(), 0x28)).code shouldBe DesktopErrorCodes.EDITOR_NOT_TEXT
        }

        "a line of 20000 characters passes, 20001 is refused" {
            ok("a".repeat(20_000).toByteArray())
            refused("a".repeat(20_001).toByteArray()).code shouldBe DesktopErrorCodes.EDITOR_NOT_TEXT
        }

        "the BOM is stripped and written back" {
            val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi\n".toByteArray()
            val loaded = ok(bytes)
            loaded.hadBom shouldBe true
            loaded.text shouldBe "hi\n"
            EditorText.encodeForSave(loaded.text, loaded.lineEnding, loaded.hadBom).toList() shouldBe bytes.toList()
        }

        "line endings are detected, normalised and written back" {
            val crlf = ok("a\r\nb\r\n".toByteArray())
            crlf.lineEnding shouldBe LineEnding.CRLF
            crlf.text shouldBe "a\nb\n"
            crlf.mixedLineEndings shouldBe false
            EditorText.encodeForSave(crlf.text, crlf.lineEnding, false).toList() shouldBe
                "a\r\nb\r\n".toByteArray().toList()
            val mixed = ok("a\r\nb\nc\rd".toByteArray())
            mixed.mixedLineEndings shouldBe true
            mixed.lineEnding shouldBe LineEnding.CRLF
            mixed.text shouldBe "a\nb\nc\nd"
            ok("a\nb".toByteArray()).lineEnding shouldBe LineEnding.LF
        }

        "an empty file is an empty buffer" { ok(ByteArray(0)).text shouldBe "" }

        "the hash is over the raw bytes, not the normalised text" {
            val raw = "a\r\n".toByteArray()
            ok(raw).sha256.toList() shouldBe EditorText.sha256(raw).toList()
            (ok(raw).sha256.toList() == EditorText.sha256("a\n".toByteArray()).toList()) shouldBe false
        }
    })
