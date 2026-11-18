package dev.kstep.desktop.edit

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors

class AtomicSaverTest :
    StringSpec({
        val posix =
            "posix" in
                java.nio.file.FileSystems
                    .getDefault()
                    .supportedFileAttributeViews()

        fun bytes(s: String) = s.toByteArray()

        fun hash(s: String) = EditorText.sha256(bytes(s))

        fun leftovers(dir: Path) =
            Files.list(dir).use { it.filter { f -> f.fileName.toString().endsWith(".tmp") }.toList() }

        "a save replaces the content and returns the hash of what was written" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "old")
            val result =
                AtomicSaver()
                    .save(
                        file,
                        bytes("new"),
                        hash("old"),
                    ).shouldBeInstanceOf<AtomicSaver.Result.Saved>()
            Files.readString(file) shouldBe "new"
            result.newHash.toList() shouldBe hash("new").toList()
            leftovers(dir) shouldBe emptyList()
        }

        "POSIX permissions of the original survive" {
            if (posix) {
                val dir = TestSupport.newDir()
                val file = TestSupport.script(dir, "a", "old")
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---"))
                AtomicSaver().save(file, bytes("new"), hash("old")).shouldBeInstanceOf<AtomicSaver.Result.Saved>()
                PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rwxr-x---"
            }
        }

        "a read-only target is left untouched and reported" {
            if (posix) {
                val dir = TestSupport.newDir()
                val file = TestSupport.script(dir, "a", "old")
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))
                try {
                    // root ignores permission bits; then the check below would not apply
                    if (!Files.isWritable(file)) {
                        AtomicSaver()
                            .save(
                                file,
                                bytes("new"),
                                hash("old"),
                            ).shouldBeInstanceOf<AtomicSaver.Result.Failed>()
                            .reason shouldBe
                            SaveFailure.READ_ONLY
                        Files.readString(file) shouldBe "old"
                        leftovers(dir) shouldBe emptyList()
                    }
                } finally {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
                }
            }
        }

        "a read-only folder is reported and leaves nothing behind" {
            if (posix) {
                val dir = TestSupport.newDir()
                val file = TestSupport.script(dir, "a", "old")
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
                try {
                    if (!Files.isWritable(dir)) {
                        AtomicSaver()
                            .save(
                                file,
                                bytes("new"),
                                hash("old"),
                            ).shouldBeInstanceOf<AtomicSaver.Result.Failed>()
                            .reason shouldBe
                            SaveFailure.READ_ONLY
                        Files.readString(file) shouldBe "old"
                    }
                } finally {
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
                }
            }
        }

        "a hash mismatch is a conflict and nothing is written" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "changed elsewhere")
            val result =
                AtomicSaver()
                    .save(
                        file,
                        bytes("mine"),
                        hash("old"),
                    ).shouldBeInstanceOf<AtomicSaver.Result.Conflict>()
            result.diskHash!!.toList() shouldBe hash("changed elsewhere").toList()
            Files.readString(file) shouldBe "changed elsewhere"
            leftovers(dir) shouldBe emptyList()
        }

        "force overwrites despite a mismatch" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "changed elsewhere")
            AtomicSaver()
                .save(
                    file,
                    bytes("mine"),
                    hash("old"),
                    force = true,
                ).shouldBeInstanceOf<AtomicSaver.Result.Saved>()
            Files.readString(file) shouldBe "mine"
        }

        "a deleted file is recreated when the editor knows it is gone" {
            val dir = TestSupport.newDir()
            val file = dir.resolve("gone.kstep.kts")
            AtomicSaver().save(file, bytes("again"), null).shouldBeInstanceOf<AtomicSaver.Result.Saved>()
            Files.readString(file) shouldBe "again"
        }

        "a deleted file is a conflict when the editor still expected it" {
            val dir = TestSupport.newDir()
            val file = dir.resolve("gone.kstep.kts")
            AtomicSaver()
                .save(
                    file,
                    bytes("x"),
                    hash("old"),
                ).shouldBeInstanceOf<AtomicSaver.Result.Conflict>()
                .diskHash shouldBe
                null
            Files.exists(file) shouldBe false
        }

        "a file that appeared while the editor expected none is a conflict" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "there")
            AtomicSaver().save(file, bytes("x"), null).shouldBeInstanceOf<AtomicSaver.Result.Conflict>()
        }

        "a symbolic link is followed and survives" {
            if (posix) {
                val dir = TestSupport.newDir()
                val target = TestSupport.script(dir, "real", "old")
                val link = dir.resolve("link.kstep.kts")
                Files.createSymbolicLink(link, target.fileName)
                AtomicSaver().save(link, bytes("new"), hash("old")).shouldBeInstanceOf<AtomicSaver.Result.Saved>()
                Files.isSymbolicLink(link) shouldBe true
                Files.readString(target) shouldBe "new"
            }
        }

        "a full disk is classified and the temp file is removed" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "old")
            val saver = AtomicSaver { _, _ -> throw IOException("No space left on device") }
            saver.save(file, bytes("new"), hash("old")).shouldBeInstanceOf<AtomicSaver.Result.Failed>().reason shouldBe
                SaveFailure.DISK_FULL
            Files.readString(file) shouldBe "old"
            leftovers(dir) shouldBe emptyList()
        }

        "another I/O failure is IO and the temp file is removed" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "old")
            val saver = AtomicSaver { _, _ -> throw IOException("boom") }
            saver.save(file, bytes("new"), hash("old")).shouldBeInstanceOf<AtomicSaver.Result.Failed>().reason shouldBe
                SaveFailure.IO
            leftovers(dir) shouldBe emptyList()
        }

        "large content is written completely" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "old")
            val big = "x".repeat(900_000)
            AtomicSaver().save(file, bytes(big), hash("old")).shouldBeInstanceOf<AtomicSaver.Result.Saved>()
            Files.size(file) shouldBe 900_000L
        }

        "concurrent saves are serialised: exactly one wins, the other sees a conflict" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "old")
            val saver = AtomicSaver()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results =
                    (1..2)
                        .map { n ->
                            pool.submit<AtomicSaver.Result> { saver.save(file, bytes("v$n"), hash("old")) }
                        }.map { it.get() }
                results.count { it is AtomicSaver.Result.Saved } shouldBe 1
                results.count { it is AtomicSaver.Result.Conflict } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
    })
