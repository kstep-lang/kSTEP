package dev.kstep.desktop.draft

import dev.kstep.desktop.edit.EditorText
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import java.security.MessageDigest

class DraftFormatTest :
    StringSpec({
        val path =
            Path
                .of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath()
                .normalize()
                .resolve("a.kstep.kts")

        fun draft(
            body: String,
            p: Path = path,
            hash: ByteArray? = ByteArray(32) { 7 },
        ) = Draft(p, 1_700_000_000_000, 42, hash, 10, 20, body)

        fun rejected(bytes: ByteArray): RejectReason =
            DraftFormat.parse(bytes).shouldBeInstanceOf<DraftParse.Rejected>().reason

        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        /** A draft with one header line replaced by [value] and the rest valid. */
        fun withLine(
            key: String,
            value: String,
            body: String = "x\n",
        ): ByteArray {
            val text = String(DraftFormat.serialize(draft(body)), Charsets.UTF_8)
            val lines = text.split('\n').toMutableList()
            val i = lines.indexOfFirst { it.startsWith("$key=") }
            lines[i] = "$key=$value"
            return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        }

        "the limits equal those of the editor buffer" {
            DraftPolicy.MAX_BODY_BYTES shouldBe EditorText.MAX_BYTES
            DraftPolicy.MAX_LINE_CHARS shouldBe EditorText.MAX_LINE_CHARS
        }

        "serialize and parse round-trip, also empty, unicode and exactly 1 MiB" {
            for (body in listOf("", "héllo ✓ 😀\nzwei\n", "a".repeat(DraftPolicy.MAX_BODY_BYTES))) {
                val body2 = if (body.length > 1000) body.chunked(1000).joinToString("\n") else body
                if (body2.toByteArray().size > DraftPolicy.MAX_BODY_BYTES) continue
                val parsed = DraftFormat.parse(DraftFormat.serialize(draft(body2))).shouldBeInstanceOf<DraftParse.Ok>()
                parsed.draft.body shouldBe body2
                parsed.draft.path shouldBe path
                parsed.draft.created shouldBe 10
                parsed.draft.written shouldBe 20
                parsed.draft.fileSha256!!.toHex() shouldBe ByteArray(32) { 7 }.toHex()
            }
            val exact = "b".repeat(999) + "\n"
            val body = exact.repeat(DraftPolicy.MAX_BODY_BYTES / exact.length)
            val full = body + "c".repeat(DraftPolicy.MAX_BODY_BYTES - body.length)
            full.toByteArray().size shouldBe DraftPolicy.MAX_BODY_BYTES
            DraftFormat.parse(DraftFormat.serialize(draft(full))).shouldBeInstanceOf<DraftParse.Ok>()
        }

        "a missing file hash is written as a dash" {
            val parsed =
                DraftFormat
                    .parse(
                        DraftFormat.serialize(draft("x", hash = null)),
                    ).shouldBeInstanceOf<DraftParse.Ok>()
            parsed.draft.fileSha256 shouldBe null
        }

        "serialize refuses an oversized body and a bad path" {
            val tooBig = "a\n".repeat(DraftPolicy.MAX_BODY_BYTES / 2 + 1)
            runCatching { DraftFormat.serialize(draft(tooBig)) }.isFailure shouldBe true
            runCatching { DraftFormat.serialize(draft("x", p = Path.of("relative.kstep.kts"))) }.isFailure shouldBe true
        }

        "a file above the size limit is refused" {
            rejected(ByteArray(DraftPolicy.MAX_FILE_BYTES + 1)) shouldBe RejectReason.TOO_LARGE
        }

        "header problems are refused" {
            rejected("KSTEP-DRAFT 1\npath=/a\n".toByteArray()) shouldBe RejectReason.HEADER
            rejected(("x".repeat(9_000) + "\n\nbody").toByteArray()) shouldBe RejectReason.HEADER
            val good = String(DraftFormat.serialize(draft("x\n")), Charsets.UTF_8)
            rejected(good.replaceFirst("KSTEP-DRAFT 1", "KSTEP-DRAFT 2").toByteArray()) shouldBe RejectReason.HEADER
            rejected(good.replaceFirst("KSTEP-DRAFT 1", "kstep-draft 1").toByteArray()) shouldBe RejectReason.HEADER
            rejected(good.replaceFirst("created=", "creatd=").toByteArray()) shouldBe RejectReason.KEY
            // duplicated and swapped keys
            val lines = good.split('\n')
            val swapped =
                lines.toMutableList().also {
                    it[2] = lines[3]
                    it[3] = lines[2]
                }
            rejected(swapped.joinToString("\n").toByteArray()) shouldBe RejectReason.KEY
            val doubled = lines.toMutableList().also { it[3] = lines[2] }
            rejected(doubled.joinToString("\n").toByteArray()) shouldBe RejectReason.KEY
        }

        "a bad path is refused" {
            val p = path.toString()
            rejected(withLine("path", "relative/a.kstep.kts")) shouldBe RejectReason.PATH
            rejected(withLine("path", "/a/../b")) shouldBe RejectReason.PATH
            rejected(withLine("path", "/a/./b")) shouldBe RejectReason.PATH
            rejected(withLine("path", "/a\u0001b")) shouldBe RejectReason.PATH
            rejected(withLine("path", "/a\u007Fb")) shouldBe RejectReason.PATH
            rejected(withLine("path", "/a\u0000b")) shouldBe RejectReason.PATH
            rejected(withLine("path", "")) shouldBe RejectReason.PATH
            rejected(withLine("path", "/" + "a".repeat(4_100))) shouldBe RejectReason.PATH
            // an embedded line break splits the header: the key order breaks
            val lf = String(withLine("path", "$p\nextra=1"), Charsets.UTF_8)
            DraftFormat.parse(lf.toByteArray()).shouldBeInstanceOf<DraftParse.Rejected>()
        }

        "bad numbers and hashes are refused" {
            rejected(withLine("created", "-1")) shouldBe RejectReason.NUMBER
            rejected(withLine("created", "30")) shouldBe RejectReason.NUMBER // created > written
            rejected(withLine("created", "1".repeat(20))) shouldBe RejectReason.NUMBER
            rejected(withLine("written", "+5")) shouldBe RejectReason.NUMBER
            rejected(withLine("file-mtime", "-2")) shouldBe RejectReason.NUMBER
            rejected(withLine("file-size", "abc")) shouldBe RejectReason.NUMBER
            DraftFormat.parse(withLine("file-mtime", "-1")).shouldBeInstanceOf<DraftParse.Ok>()
            rejected(withLine("file-sha256", "A".repeat(64))) shouldBe RejectReason.HASH_FORMAT
            rejected(withLine("file-sha256", "ab")) shouldBe RejectReason.HASH_FORMAT
            rejected(withLine("body-sha256", sha("x\n".toByteArray()).uppercase())) shouldBe RejectReason.HASH_FORMAT
        }

        "the body must match its length and hash" {
            rejected(withLine("body-length", "1")) shouldBe RejectReason.BODY_LENGTH
            rejected(withLine("body-length", "99")) shouldBe RejectReason.BODY_LENGTH
            rejected(withLine("body-length", "9999999999")) shouldBe RejectReason.BODY_LENGTH
            rejected(withLine("body-sha256", "0".repeat(64))) shouldBe RejectReason.BODY_HASH
        }

        "text problems are refused" {
            fun raw(body: ByteArray): ByteArray {
                val head =
                    String(DraftFormat.serialize(draft("")), Charsets.UTF_8)
                        .replaceFirst("body-sha256=" + sha(ByteArray(0)), "body-sha256=" + sha(body))
                        .replaceFirst("body-length=0", "body-length=${body.size}")
                return head.toByteArray() + body
            }
            rejected(raw(byteArrayOf(0x61, 0xFF.toByte(), 0x62))) shouldBe RejectReason.NOT_UTF8
            rejected(raw("a\rb".toByteArray())) shouldBe RejectReason.BODY_INVALID
            rejected(raw("a\u0000b".toByteArray())) shouldBe RejectReason.BODY_INVALID
            rejected(raw((Char(0xFEFF) + "abc").toByteArray())) shouldBe RejectReason.BODY_INVALID
            rejected(raw("a".repeat(20_001).toByteArray())) shouldBe RejectReason.BODY_INVALID
            DraftFormat.parse(raw("a".repeat(20_000).toByteArray())).shouldBeInstanceOf<DraftParse.Ok>()
            // broken UTF-8 in the header
            val good = DraftFormat.serialize(draft("x"))
            val bad = good.copyOf().also { it[20] = 0xFF.toByte() }
            DraftFormat.parse(bad).shouldBeInstanceOf<DraftParse.Rejected>()
        }
    })
