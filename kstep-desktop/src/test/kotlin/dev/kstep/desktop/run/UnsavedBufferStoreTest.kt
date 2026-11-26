package dev.kstep.desktop.run

import dev.kstep.cli.UnsavedBufferInput
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest

/** The private folder of "Run Unsaved" (ADR-0086): owner, permissions, links, names and limits. */
class UnsavedBufferStoreTest :
    StringSpec({
        fun sha(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun withBase(body: (Path) -> Unit) {
            val base = Files.createTempDirectory("unsaved-store").toRealPath()
            try {
                body(base)
            } finally {
                base.toFile().deleteRecursively()
            }
        }

        fun perms(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

        "a buffer is a 0600 file with a random UUID name in a 0700 folder, with the hash of its bytes" {
            withBase { base ->
                val store = UnsavedBufferStore(base)
                val ok = store.write("val x = 1\n").shouldBeInstanceOf<UnsavedBufferStore.Written.Ok>()
                perms(ok.path) shouldBe "rw-------"
                perms(ok.path.parent) shouldBe "rwx------"
                UnsavedBufferInput.FILE_NAME.matches(ok.path.fileName.toString()) shouldBe true
                ok.path.parent.parent shouldBe base
                Files.readString(ok.path) shouldBe "val x = 1\n"
                ok.sha256Hex shouldBe sha("val x = 1\n".toByteArray())
                val second = store.write("a").shouldBeInstanceOf<UnsavedBufferStore.Written.Ok>()
                second.path.parent shouldBe ok.path.parent
                (second.path != ok.path) shouldBe true
                store.close()
            }
        }

        "the folder is replaced by a link: nothing is written, it is unsafe" {
            withBase { base ->
                val store = UnsavedBufferStore(base)
                val first = store.write("a").shouldBeInstanceOf<UnsavedBufferStore.Written.Ok>()
                val dir = first.path.parent
                val moved = base.resolve("moved")
                Files.move(dir, moved)
                Files.createSymbolicLink(dir, moved)
                store.write("b").shouldBeInstanceOf<UnsavedBufferStore.Written.Unsafe>()
                Files.list(moved).use { it.count() } shouldBe 1L
            }
        }

        "wider permissions are unsafe and are not repaired" {
            withBase { base ->
                val store = UnsavedBufferStore(base)
                val first = store.write("a").shouldBeInstanceOf<UnsavedBufferStore.Written.Ok>()
                val dir = first.path.parent
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
                store.write("b").shouldBeInstanceOf<UnsavedBufferStore.Written.Unsafe>()
                perms(dir) shouldBe "rwxr-xr-x"
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
            }
        }

        "a folder of another owner is unsafe" {
            withBase { base ->
                val stranger =
                    object : UserPrincipal {
                        override fun getName(): String = "somebody-else"
                    }
                val store = UnsavedBufferStore(base, currentOwner = { stranger })
                store.write("a").shouldBeInstanceOf<UnsavedBufferStore.Written.Unsafe>()
            }
        }

        "a pre-planted link or file with the next name is never followed or overwritten" {
            withBase { base ->
                var next = "first.kstep.kts"
                val store = UnsavedBufferStore(base, newName = { next })
                val first = store.write("a").shouldBeInstanceOf<UnsavedBufferStore.Written.Ok>()
                val dir = first.path.parent
                val target = base.resolve("target.txt")
                Files.writeString(target, "precious")
                Files.createSymbolicLink(dir.resolve("planted.kstep.kts"), target)
                next = "planted.kstep.kts"
                store.write("evil").shouldBeInstanceOf<UnsavedBufferStore.Written.Failed>()
                Files.readString(target) shouldBe "precious"
                next = "first.kstep.kts" // the same name again: CREATE_NEW refuses
                store.write("again").shouldBeInstanceOf<UnsavedBufferStore.Written.Failed>()
                Files.readString(first.path) shouldBe "a"
            }
        }

        "one MiB is accepted, one byte more is too large and creates nothing" {
            withBase { base ->
                val store = UnsavedBufferStore(base)
                store.write("a".repeat(UnsavedRunPolicy.MAX_BYTES)).shouldBeInstanceOf<UnsavedBufferStore.Written.Ok>()
                val other = UnsavedBufferStore(base)
                other.write("a".repeat(UnsavedRunPolicy.MAX_BYTES + 1)) shouldBe UnsavedBufferStore.Written.TooLarge
                Files.list(base).use { it.count() } shouldBe 1L // only the first store's folder
                store.close()
            }
        }

        "the limits of the desktop and of the worker are the same" {
            UnsavedRunPolicy.MAX_BYTES shouldBe UnsavedBufferInput.MAX_BYTES
        }

        "delete removes a file and close removes the folder" {
            withBase { base ->
                val store = UnsavedBufferStore(base)
                val ok = store.write("a").shouldBeInstanceOf<UnsavedBufferStore.Written.Ok>()
                store.delete(ok.path)
                Files.exists(ok.path) shouldBe false
                store.delete(ok.path) // already gone: no failure
                store.close()
                Files.exists(ok.path.parent) shouldBe false
                store.write("b").shouldBeInstanceOf<UnsavedBufferStore.Written.Failed>()
            }
        }

        "after each ended run only the folder remains: at most one buffer file lives at a time" {
            withBase { base ->
                val store = UnsavedBufferStore(base)
                var maxFiles = 0
                repeat(5) { i ->
                    val ok = store.write("run $i").shouldBeInstanceOf<UnsavedBufferStore.Written.Ok>()
                    val count = Files.list(ok.path.parent).use { it.count() }
                    if (count > maxFiles) maxFiles = count.toInt()
                    store.delete(ok.path)
                    Files.list(ok.path.parent).use { it.count() } shouldBe 0L
                }
                maxFiles shouldBe 1
                store.close()
            }
        }
    })
