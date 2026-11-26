package dev.kstep.desktop.recent

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** Recent entries for single notes: format, load rules and list behaviour (ADR-0083). */
class RecentNoteStoreTest :
    StringSpec({
        fun withDir(block: (Path, Path) -> Unit) {
            val dir = TestSupport.newDir()
            try {
                block(dir, Files.createDirectory(dir.resolve("config")))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        fun store(config: Path) = RecentFilesStore(config, caseInsensitive = false)

        fun write(
            config: Path,
            body: String,
        ) = Files.writeString(config.resolve(RecentFilesStore.FILE_NAME), body)

        fun esc(path: String) = path.replace("\\", "\\\\")

        fun note(
            path: String,
            root: String?,
            kind: String = "note",
        ) = "{\"kind\":\"$kind\",\"path\":\"${esc(path)}\"" + (root?.let { ",\"root\":\"${esc(it)}\"" } ?: "") + "}"

        fun doc(vararg entries: String) = "{\"version\":1,\"entries\":[${entries.joinToString(",")}]}"

        "a note with its root survives a round trip" {
            withDir { dir, config ->
                val entry = RecentEntry(dir.resolve("a/b/n.md"), RecentKind.NOTE, dir.resolve("a"))
                val list =
                    RecentList
                        .empty(
                            false,
                        ).touch(RecentEntry(dir.resolve("s.kstep.kts"), RecentKind.SCRIPT))
                        .touch(entry)
                store(config).save(list)
                store(config).load().entries shouldBe list.entries
            }
        }

        "an old file with scripts and folders only loads unchanged" {
            withDir { dir, config ->
                write(
                    config,
                    doc(
                        note(dir.resolve("s.kstep.kts").toString(), null, "script"),
                        note(dir.toString(), null, "folder"),
                    ),
                )
                store(config).load().entries shouldBe
                    listOf(
                        RecentEntry(dir.resolve("s.kstep.kts"), RecentKind.SCRIPT),
                        RecentEntry(dir, RecentKind.FOLDER),
                    )
            }
        }

        "a bad note entry is dropped alone, the others stay" {
            withDir { dir, config ->
                val good = dir.resolve("g/n.md")
                val bad =
                    listOf(
                        note(dir.resolve("a/n.md").toString(), null), // no root
                        note(dir.resolve("a/n.md").toString(), "relative/root"),
                        note(dir.resolve("a/n.md").toString(), dir.resolve("a/../a").toString()), // not normalized
                        note(dir.resolve("a/n.md").toString(), dir.resolve("other").toString()), // not a prefix
                        note(dir.resolve("a").toString() + "/n.md", dir.resolve("a/n.md").toString()), // path == root
                        note(dir.resolve("a/n.txt").toString(), dir.resolve("a").toString()), // wrong ending
                        note(dir.resolve("a/n.md").toString(), dir.resolve("a").toString() + "\u0001"), // control char
                    )
                write(config, doc(*bad.toTypedArray(), note(good.toString(), dir.resolve("g").toString())))
                store(config).load().entries shouldBe listOf(RecentEntry(good, RecentKind.NOTE, dir.resolve("g")))
            }
        }

        "a root on a script entry is ignored" {
            withDir { dir, config ->
                val s = dir.resolve("s.kstep.kts")
                write(config, doc(note(s.toString(), "not/absolute", "script")))
                store(config).load().entries shouldBe listOf(RecentEntry(s, RecentKind.SCRIPT))
            }
        }

        "broken files, version 2 and oversized files give an empty list" {
            withDir { dir, config ->
                val n = note(dir.resolve("n.md").toString(), dir.toString())
                for (body in listOf(doc(n).dropLast(5), doc(n).replace("\"version\":1", "\"version\":2"))) {
                    write(config, body)
                    store(config).load().entries shouldBe emptyList()
                }
                write(config, doc(n) + " ".repeat(RecentFilesStore.MAX_BYTES.toInt()))
                store(config).load().entries shouldBe emptyList()
            }
        }

        "twelve entries of mixed kinds keep ten" {
            val dir = TestSupport.newDir()
            var list = RecentList.empty(false)
            (1..12).forEach {
                list =
                    list.touch(
                        if (it % 2 == 0) {
                            RecentEntry(dir.resolve("n$it.md"), RecentKind.NOTE, dir)
                        } else {
                            RecentEntry(dir.resolve("s$it.kstep.kts"), RecentKind.SCRIPT)
                        },
                    )
            }
            list.entries.size shouldBe 10
        }

        "the same path as note and script is one entry, the newest wins; a note touched twice goes to the top" {
            val dir = TestSupport.newDir()
            val p = dir.resolve("x.md")
            val other = RecentEntry(dir.resolve("o.md"), RecentKind.NOTE, dir)
            val one =
                RecentList
                    .empty(
                        false,
                    ).touch(RecentEntry(p, RecentKind.SCRIPT))
                    .touch(RecentEntry(p, RecentKind.NOTE, dir))
            one.entries shouldBe listOf(RecentEntry(p, RecentKind.NOTE, dir))
            val two = one.touch(other).touch(RecentEntry(p, RecentKind.NOTE, dir))
            two.entries shouldBe listOf(RecentEntry(p, RecentKind.NOTE, dir), other)
        }

        "the check of a note looks at its root first and finds links above the note" {
            val dir = TestSupport.newDir()
            val root = Files.createDirectory(dir.resolve("ws"))
            val sub = Files.createDirectory(root.resolve("sub"))
            val n = Files.writeString(sub.resolve("n.md"), "# n")
            val entry = RecentEntry(n, RecentKind.NOTE, root)
            RecentOpenCheck.check(entry) shouldBe RecentCheck.Ok(n)
            RecentOpenCheck.check(entry.copy(root = null)) shouldBe RecentCheck.Missing
            RecentOpenCheck.check(entry.copy(root = dir.resolve("gone"))) shouldBe RecentCheck.Missing
            RecentOpenCheck.check(entry.copy(root = n)) shouldBe RecentCheck.WrongType
            RecentOpenCheck.check(RecentEntry(sub.resolve("gone.md"), RecentKind.NOTE, root)) shouldBe
                RecentCheck.Missing
            RecentOpenCheck.check(RecentEntry(sub, RecentKind.NOTE, root)) shouldBe RecentCheck.WrongType
            try {
                val rootLink = Files.createSymbolicLink(dir.resolve("wslink"), root)
                RecentOpenCheck.check(RecentEntry(rootLink.resolve("sub/n.md"), RecentKind.NOTE, rootLink)) shouldBe
                    RecentCheck.Link
                val inner = Files.createSymbolicLink(root.resolve("inner"), sub)
                RecentOpenCheck.check(RecentEntry(inner.resolve("n.md"), RecentKind.NOTE, root)) shouldBe
                    RecentCheck.Link
            } catch (_: UnsupportedOperationException) {
                // no symbolic links here
            }
        }

        "the wrong-type text and the label of a note" {
            val dir = TestSupport.newDir()
            val entry = RecentEntry(dir.resolve("n.md"), RecentKind.NOTE, dir)
            RecentMessages.wrongType(entry).message.contains("no longer a note") shouldBe true
            RecentMessages.label(entry).name shouldBe "n.md"
        }
    })
