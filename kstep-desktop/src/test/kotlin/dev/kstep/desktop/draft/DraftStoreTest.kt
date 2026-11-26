package dev.kstep.desktop.draft

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

class DraftStoreTest :
    StringSpec({
        val posix =
            "posix" in
                java.nio.file.FileSystems
                    .getDefault()
                    .supportedFileAttributeViews()
        val day = 24L * 60 * 60 * 1000
        val start = 1_700_000_000_000L

        class Env {
            val root: Path = TestSupport.newDir()
            val dir: Path = root.resolve("config").resolve("drafts")
            var now = start
            var owner: (() -> UserPrincipal?)? = null
            val store by lazy { DraftStore(dir, { now }, owner) }
            val script: Path = root.resolve("work").resolve("a.kstep.kts")

            fun draft(
                path: Path = script,
                body: String = "body\n",
                written: Long = now,
            ) = Draft(DraftKey.normalize(path), 1, 2, ByteArray(32) { 3 }, written, written, body)

            fun fileOf(path: Path = script): Path = dir.resolve(DraftKey.fileName(DraftKey.normalize(path)))

            fun names(): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().sorted() }

            fun done() = root.toFile().deleteRecursively()
        }

        fun withEnv(block: (Env) -> Unit) {
            val env = Env()
            try {
                block(env)
            } finally {
                env.done()
            }
        }

        "a draft is written and read back; nothing is read for another script" {
            withEnv { e ->
                e.store.write(e.draft()) shouldBe DraftStore.Write.Written
                val found = e.store.read(DraftKey.normalize(e.script)).shouldBeInstanceOf<DraftStore.Read.Found>()
                found.draft.body shouldBe "body\n"
                e.store.read(DraftKey.normalize(e.root.resolve("other.kstep.kts"))) shouldBe DraftStore.Read.None
            }
        }

        "write replaces the old draft and leaves no temp file" {
            withEnv { e ->
                e.store.write(e.draft(body = "one"))
                e.store.write(e.draft(body = "two"))
                e.names().size shouldBe 1
                e.store
                    .read(
                        DraftKey.normalize(e.script),
                    ).shouldBeInstanceOf<DraftStore.Read.Found>()
                    .draft.body shouldBe
                    "two"
            }
        }

        "delete removes the draft, a second delete is harmless" {
            withEnv { e ->
                e.store.write(e.draft())
                e.store.delete(DraftKey.normalize(e.script))
                e.store.delete(DraftKey.normalize(e.script))
                e.store.read(DraftKey.normalize(e.script)) shouldBe DraftStore.Read.None
            }
        }

        "a body above 1 MiB is not written and the old draft stays" {
            withEnv { e ->
                e.store.write(e.draft(body = "old"))
                val big = "a\n".repeat(DraftPolicy.MAX_BODY_BYTES / 2 + 1)
                e.store.write(e.draft(body = big)) shouldBe DraftStore.Write.TooLarge
                e.store
                    .read(
                        DraftKey.normalize(e.script),
                    ).shouldBeInstanceOf<DraftStore.Read.Found>()
                    .draft.body shouldBe
                    "old"
            }
        }

        "the folder is 0700 and the files 0600" {
            withEnv { e ->
                if (!posix) return@withEnv
                e.store.write(e.draft())
                PosixFilePermissions.toString(Files.getPosixFilePermissions(e.dir)) shouldBe "rwx------"
                PosixFilePermissions.toString(Files.getPosixFilePermissions(e.fileOf())) shouldBe "rw-------"
            }
        }

        "an own folder with loose rights is tightened" {
            withEnv { e ->
                if (!posix) return@withEnv
                Files.createDirectories(e.dir)
                Files.setPosixFilePermissions(e.dir, PosixFilePermissions.fromString("rwxr-xr-x"))
                e.store.available shouldBe true
                PosixFilePermissions.toString(Files.getPosixFilePermissions(e.dir)) shouldBe "rwx------"
            }
        }

        "a draft that is a link is removed and its target is untouched" {
            withEnv { e ->
                if (!posix) return@withEnv
                e.store.write(e.draft())
                val target = e.root.resolve("precious.txt").also { Files.writeString(it, "keep") }
                Files.delete(e.fileOf())
                Files.createSymbolicLink(e.fileOf(), target)
                e.store
                    .read(
                        DraftKey.normalize(e.script),
                    ).shouldBeInstanceOf<DraftStore.Read.Rejected>()
                    .reason shouldBe
                    RejectReason.LINK
                Files.exists(e.fileOf(), LinkOption.NOFOLLOW_LINKS) shouldBe false
                Files.readString(target) shouldBe "keep"
            }
        }

        "a folder that is a link is not used and its target is untouched" {
            withEnv { e ->
                if (!posix) return@withEnv
                val target = Files.createDirectories(e.root.resolve("elsewhere"))
                Files.createDirectories(e.dir.parent)
                Files.createSymbolicLink(e.dir, target)
                e.store.available shouldBe false
                e.store.write(e.draft()) shouldBe DraftStore.Write.Unavailable
                e.store.read(DraftKey.normalize(e.script)) shouldBe DraftStore.Read.Unavailable
                Files.list(target).use { it.count() } shouldBe 0
            }
        }

        "a folder that is a plain file is not used" {
            withEnv { e ->
                Files.createDirectories(e.dir.parent)
                Files.writeString(e.dir, "x")
                e.store.available shouldBe false
            }
        }

        "a folder of somebody else is not used" {
            withEnv { e ->
                if (!posix) return@withEnv
                Files.createDirectories(e.dir)
                val stranger =
                    object : UserPrincipal {
                        override fun getName() = "someone-else"
                    }
                e.owner = { stranger }
                e.store.available shouldBe false
                e.store.write(e.draft()) shouldBe DraftStore.Write.Unavailable
            }
        }

        "loose permissions are refused and the draft is deleted" {
            withEnv { e ->
                if (!posix) return@withEnv
                for (mode in listOf("rw-r--r--", "rw-rw-rw-", "rw-r-----", "rw----r--")) {
                    e.store.write(e.draft())
                    Files.setPosixFilePermissions(e.fileOf(), PosixFilePermissions.fromString(mode))
                    e.store
                        .read(
                            DraftKey.normalize(e.script),
                        ).shouldBeInstanceOf<DraftStore.Read.Rejected>()
                        .reason shouldBe
                        RejectReason.PERMISSIONS
                    Files.exists(e.fileOf()) shouldBe false
                }
            }
        }

        "an oversized file is refused without being read" {
            withEnv { e ->
                e.store.write(e.draft())
                java.io
                    .RandomAccessFile(
                        e.fileOf().toFile(),
                        "rw",
                    ).use { it.setLength(DraftPolicy.MAX_FILE_BYTES + 1L) }
                e.store
                    .read(
                        DraftKey.normalize(e.script),
                    ).shouldBeInstanceOf<DraftStore.Read.Rejected>()
                    .reason shouldBe
                    RejectReason.TOO_LARGE
                Files.exists(e.fileOf()) shouldBe false
            }
        }

        "a draft of script A under the name of script B is refused and deleted" {
            withEnv { e ->
                val b = e.root.resolve("work").resolve("b.kstep.kts")
                e.store.write(e.draft(e.script))
                Files.copy(e.fileOf(e.script), e.fileOf(b))
                e.store
                    .read(DraftKey.normalize(b))
                    .shouldBeInstanceOf<DraftStore.Read.Rejected>()
                    .reason shouldBe
                    RejectReason.NAME_MISMATCH
                Files.exists(e.fileOf(b)) shouldBe false
                Files.exists(e.fileOf(e.script)) shouldBe true
            }
        }

        "a broken header is refused and deleted" {
            withEnv { e ->
                e.store.write(e.draft())
                Files.writeString(e.fileOf(), "KSTEP-DRAFT 1\npath=/a/../b\n\nx")
                if (posix) Files.setPosixFilePermissions(e.fileOf(), PosixFilePermissions.fromString("rw-------"))
                e.store.read(DraftKey.normalize(e.script)).shouldBeInstanceOf<DraftStore.Read.Rejected>()
                Files.exists(e.fileOf()) shouldBe false
            }
        }

        "a directory with a draft name is neither read as a draft nor deleted" {
            withEnv { e ->
                e.store.available shouldBe true
                Files.createDirectory(e.fileOf())
                e.store
                    .read(
                        DraftKey.normalize(e.script),
                    ).shouldBeInstanceOf<DraftStore.Read.Rejected>()
                    .reason shouldBe
                    RejectReason.NOT_REGULAR
                e.store.delete(DraftKey.normalize(e.script))
                e.store.housekeeping()
                Files.isDirectory(e.fileOf()) shouldBe true
            }
        }

        "foreign names are never touched by housekeeping" {
            withEnv { e ->
                e.store.available shouldBe true
                val notes = Files.writeString(e.dir.resolve("notes.txt"), "mine")
                val upper = Files.writeString(e.dir.resolve("A".repeat(64) + ".draft"), "x")
                val short = Files.writeString(e.dir.resolve("abc.draft"), "x")
                e.now += 100 * day
                e.store.housekeeping()
                Files.exists(notes) shouldBe true
                Files.exists(upper) shouldBe true
                Files.exists(short) shouldBe true
            }
        }

        "more than 32 drafts: the oldest by written time go" {
            withEnv { e ->
                val paths = (0 until 34).map { e.root.resolve("work").resolve("s$it.kstep.kts") }
                paths.forEachIndexed { i, p -> e.store.write(e.draft(p, written = start + i * 1000L)) }
                e.names().size shouldBe DraftPolicy.MAX_DRAFTS
                Files.exists(e.fileOf(paths[0])) shouldBe false
                Files.exists(e.fileOf(paths[1])) shouldBe false
                Files.exists(e.fileOf(paths[2])) shouldBe true
                Files.exists(e.fileOf(paths[33])) shouldBe true
            }
        }

        "a draft older than 30 days is deleted by housekeeping" {
            withEnv { e ->
                val old = e.root.resolve("work").resolve("old.kstep.kts")
                e.store.write(e.draft(old, written = start - 31 * day))
                e.store.write(e.draft(e.script, written = start - 29 * day))
                e.store.housekeeping()
                Files.exists(e.fileOf(old)) shouldBe false
                Files.exists(e.fileOf(e.script)) shouldBe true
            }
        }

        "an old temp file is deleted, a fresh one stays" {
            withEnv { e ->
                e.store.available shouldBe true
                val old = Files.writeString(e.dir.resolve(".draft-123.tmp"), "x")
                val fresh = Files.writeString(e.dir.resolve(".draft-456.tmp"), "x")
                Files.setLastModifiedTime(old, FileTime.fromMillis(start - 2 * 60 * 60 * 1000))
                Files.setLastModifiedTime(fresh, FileTime.fromMillis(start))
                e.store.housekeeping()
                Files.exists(old) shouldBe false
                Files.exists(fresh) shouldBe true
            }
        }

        "a folder that cannot be written gives Failed without an exception" {
            withEnv { e ->
                if (!posix) return@withEnv
                e.store.available shouldBe true
                Files.setPosixFilePermissions(
                    e.dir,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
                )
                try {
                    val result = e.store.write(e.draft())
                    // running as root ignores the mode: then the write simply works
                    (result == DraftStore.Write.Failed || result == DraftStore.Write.Written) shouldBe true
                } finally {
                    Files.setPosixFilePermissions(e.dir, PosixFilePermissions.fromString("rwx------"))
                }
            }
        }

        "newestHeader returns the most recent valid draft and skips broken ones" {
            withEnv { e ->
                val a = e.root.resolve("work").resolve("a.kstep.kts")
                val b = e.root.resolve("work").resolve("b.kstep.kts")
                val broken = e.root.resolve("work").resolve("c.kstep.kts")
                e.store.write(e.draft(a, written = start))
                e.store.write(e.draft(b, written = start + 5000))
                e.store.write(e.draft(broken, written = start + 9000))
                Files.writeString(e.fileOf(broken), "junk")
                if (posix) Files.setPosixFilePermissions(e.fileOf(broken), PosixFilePermissions.fromString("rw-------"))
                val newest = e.store.newestHeader()!!
                newest.path shouldBe DraftKey.normalize(b)
                newest.body shouldBe ""
                e.store.newestHeader { it.fileName.toString() == "a.kstep.kts" }!!.path shouldBe DraftKey.normalize(a)
                e.store.newestHeader { false } shouldBe null
            }
        }
    })
