package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** The worker-side validation of an unsaved buffer (ADR-0086). */
class UnsavedBufferInputTest :
    StringSpec({
        fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        class Fixture {
            val bufferDir: Path = Files.createTempDirectory("unsaved-buf").toRealPath()
            val scriptDir: Path = Files.createTempDirectory("unsaved-script").toRealPath()

            fun buffer(
                bytes: ByteArray,
                name: String = UUID.randomUUID().toString() + ".kstep.kts",
            ): Path = bufferDir.resolve(name).also { Files.write(it, bytes) }

            fun close() {
                bufferDir.toFile().deleteRecursively()
                scriptDir.toFile().deleteRecursively()
            }
        }

        fun refusal(r: UnsavedBufferInput.Result): UnsavedBufferInput.Refusal =
            r.shouldBeInstanceOf<UnsavedBufferInput.Result.Refused>().reason

        fun withFixture(body: (Fixture) -> Unit) {
            val f = Fixture()
            try {
                body(f)
            } finally {
                f.close()
            }
        }

        "a valid buffer loads with its text, folder and display name" {
            withFixture { f ->
                val bytes = "val x = 1\n".toByteArray()
                val p = f.buffer(bytes)
                val ok =
                    UnsavedBufferInput
                        .load(p.toString(), f.scriptDir.toString(), "part.kstep.kts", sha(bytes))
                        .shouldBeInstanceOf<UnsavedBufferInput.Result.Ok>()
                ok.source.code shouldBe "val x = 1\n"
                ok.source.displayName shouldBe "part.kstep.kts"
                ok.source.scriptDir.toPath() shouldBe f.scriptDir
            }
        }

        "a link instead of the file is refused" {
            withFixture { f ->
                val bytes = "a".toByteArray()
                val real = f.buffer(bytes, "real.txt")
                val link = f.bufferDir.resolve(UUID.randomUUID().toString() + ".kstep.kts")
                val linked =
                    try {
                        Files.createSymbolicLink(link, real)
                        true
                    } catch (_: Exception) {
                        false
                    }
                if (linked) {
                    refusal(
                        UnsavedBufferInput.load(link.toString(), f.scriptDir.toString(), "p.kstep.kts", sha(bytes)),
                    ) shouldBe
                        UnsavedBufferInput.Refusal.BUFFER_LINK
                }
            }
        }

        "a buffer of one byte more than the limit is refused" {
            withFixture { f ->
                val bytes = ByteArray(UnsavedBufferInput.MAX_BYTES + 1) { 'a'.code.toByte() }
                val p = f.buffer(bytes)
                refusal(
                    UnsavedBufferInput.load(p.toString(), f.scriptDir.toString(), "p.kstep.kts", sha(bytes)),
                ) shouldBe
                    UnsavedBufferInput.Refusal.BUFFER_SIZE
            }
        }

        "a buffer of exactly the limit is accepted" {
            withFixture { f ->
                val bytes = ByteArray(UnsavedBufferInput.MAX_BYTES) { 'a'.code.toByte() }
                val p = f.buffer(bytes)
                UnsavedBufferInput
                    .load(p.toString(), f.scriptDir.toString(), "p.kstep.kts", sha(bytes))
                    .shouldBeInstanceOf<UnsavedBufferInput.Result.Ok>()
            }
        }

        "invalid UTF-8 is refused" {
            withFixture { f ->
                val bytes = byteArrayOf(0x76, 0xC3.toByte(), 0x28)
                val p = f.buffer(bytes)
                refusal(
                    UnsavedBufferInput.load(p.toString(), f.scriptDir.toString(), "p.kstep.kts", sha(bytes)),
                ) shouldBe
                    UnsavedBufferInput.Refusal.BUFFER_ENCODING
            }
        }

        "a hash that does not match is refused" {
            withFixture { f ->
                val p = f.buffer("a".toByteArray())
                val other = sha("b".toByteArray())
                refusal(UnsavedBufferInput.load(p.toString(), f.scriptDir.toString(), "p.kstep.kts", other)) shouldBe
                    UnsavedBufferInput.Refusal.BUFFER_HASH
                refusal(UnsavedBufferInput.load(p.toString(), f.scriptDir.toString(), "p.kstep.kts", "ABC")) shouldBe
                    UnsavedBufferInput.Refusal.BUFFER_HASH
            }
        }

        "a file name that is not a UUID is refused, so is a relative or unnormalised path" {
            withFixture { f ->
                val bytes = "a".toByteArray()
                val named = f.buffer(bytes, "evil.kstep.kts")
                val h = sha(bytes)
                refusal(UnsavedBufferInput.load(named.toString(), f.scriptDir.toString(), "p.kstep.kts", h)) shouldBe
                    UnsavedBufferInput.Refusal.BUFFER_PATH
                val good = f.buffer(bytes)
                val unnormal =
                    f.bufferDir
                        .resolve("x")
                        .resolve("..")
                        .resolve(good.fileName)
                        .toString()
                refusal(UnsavedBufferInput.load(unnormal, f.scriptDir.toString(), "p.kstep.kts", h)) shouldBe
                    UnsavedBufferInput.Refusal.BUFFER_PATH
                refusal(
                    UnsavedBufferInput.load(good.fileName.toString(), f.scriptDir.toString(), "p.kstep.kts", h),
                ) shouldBe
                    UnsavedBufferInput.Refusal.BUFFER_PATH
            }
        }

        "a script folder that is relative, not normalised or missing is refused" {
            withFixture { f ->
                val bytes = "a".toByteArray()
                val p = f.buffer(bytes)
                val h = sha(bytes)
                val dirs =
                    listOf(
                        "relative/dir",
                        f.scriptDir
                            .resolve("a")
                            .resolve("..")
                            .toString(),
                        f.scriptDir.resolve("nope").toString(),
                    )
                for (d in dirs) {
                    refusal(UnsavedBufferInput.load(p.toString(), d, "p.kstep.kts", h)) shouldBe
                        UnsavedBufferInput.Refusal.SCRIPT_DIR
                }
            }
        }

        "display names with separators, parent segments, control or bidi characters or a wrong length are refused" {
            val bad =
                listOf(
                    "a/b.kstep.kts",
                    "a\\b.kstep.kts",
                    "..kstep.kts",
                    "a..b.kstep.kts",
                    "a\nb.kstep.kts",
                    "a‮b.kstep.kts",
                    "a⁦b.kstep.kts",
                    "a\u0000b.kstep.kts",
                    "x".repeat(250) + ".kstep.kts",
                    "plain.txt",
                    "",
                )
            for (name in bad) UnsavedBufferInput.validDisplayName(name) shouldBe false
            UnsavedBufferInput.validDisplayName("bracket v2.kstep.kts") shouldBe true
            UnsavedBufferInput.validDisplayName("x".repeat(255 - ".kstep.kts".length) + ".kstep.kts") shouldBe true
            withFixture { f ->
                val bytes = "a".toByteArray()
                refusal(
                    UnsavedBufferInput.load(
                        f.buffer(bytes).toString(),
                        f.scriptDir.toString(),
                        "a/b.kstep.kts",
                        sha(bytes),
                    ),
                ) shouldBe UnsavedBufferInput.Refusal.DISPLAY_NAME
            }
        }
    })
