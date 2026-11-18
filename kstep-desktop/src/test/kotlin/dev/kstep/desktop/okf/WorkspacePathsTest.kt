package dev.kstep.desktop.okf

import dev.kstep.desktop.edit.AtomicSaver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class WorkspacePathsTest :
    StringSpec({
        val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

        fun dir(): Path = Files.createTempDirectory("okf-paths").toRealPath()

        "a regular note inside the root is accepted" {
            val root = dir()
            val f = root.resolve("a.md").also { Files.writeString(it, "x") }
            WorkspacePaths.resolve(root, f).shouldBeInstanceOf<PathCheck.Ok>()
            WorkspacePaths.resolve(root, Path.of("a.md")).shouldBeInstanceOf<PathCheck.Ok>()
        }

        "parent references and outside paths are rejected" {
            val root = dir()
            val outside = dir().resolve("o.md").also { Files.writeString(it, "x") }
            WorkspacePaths.resolve(root, Path.of("../x.md")).shouldBeInstanceOf<PathCheck.Rejected>()
            WorkspacePaths.resolve(root, outside).shouldBeInstanceOf<PathCheck.Rejected>()
            WorkspacePaths.resolve(root, root.resolve("sub/../../o.md")).shouldBeInstanceOf<PathCheck.Rejected>()
        }

        "links, folders and a deleted file are rejected" {
            val root = dir()
            val real = root.resolve("real.md").also { Files.writeString(it, "x") }
            Files.createDirectory(root.resolve("folder.md"))
            WorkspacePaths.resolve(root, root.resolve("folder.md")).shouldBeInstanceOf<PathCheck.Rejected>()
            WorkspacePaths.resolve(root, root.resolve("gone.md")).shouldBeInstanceOf<PathCheck.Rejected>()
            if (posix) {
                Files.createSymbolicLink(root.resolve("l.md"), real)
                WorkspacePaths.resolve(root, root.resolve("l.md")).shouldBeInstanceOf<PathCheck.Rejected>()
            }
        }

        "a parent folder that became a link to the outside is rejected" {
            if (posix) {
                val root = dir()
                val outside = dir()
                Files.writeString(outside.resolve("s.md"), "x")
                Files.createSymbolicLink(root.resolve("sub"), outside)
                WorkspacePaths.resolve(root, root.resolve("sub/s.md")).shouldBeInstanceOf<PathCheck.Rejected>()
            }
        }

        "a note stays valid after our own atomic save replaced its inode" {
            val root = dir()
            val f = root.resolve("a.md").also { Files.writeString(it, "one") }
            val saver = AtomicSaver()
            val hash =
                dev.kstep.desktop.edit.EditorText
                    .sha256("one".toByteArray())
            saver.save(f, "two".toByteArray(), hash).shouldBeInstanceOf<AtomicSaver.Result.Saved>()
            WorkspacePaths.resolve(root, f).shouldBeInstanceOf<PathCheck.Ok>()
            Files.readString(f) shouldBe "two"
        }
    })
