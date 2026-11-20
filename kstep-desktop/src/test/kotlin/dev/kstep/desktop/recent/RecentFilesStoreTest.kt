package dev.kstep.desktop.recent

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** What Recent remembers is untrusted on load and best effort on save (ADR-0066). */
class RecentFilesStoreTest :
    StringSpec({
        val posix =
            "posix" in
                java.nio.file.FileSystems
                    .getDefault()
                    .supportedFileAttributeViews()

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

        fun abs(name: String): String =
            TestSupport
                .newDir()
                .resolve(name)
                .toString()
                .replace("\\", "\\\\")

        fun json(
            kind: String,
            path: String,
        ) = "{\"kind\":\"$kind\",\"path\":\"$path\"}"

        fun doc(vararg entries: String) = "{\"version\":1,\"entries\":[${entries.joinToString(",")}]}"

        "a missing file gives an empty list" {
            withDir { _, config -> store(config).load().entries shouldBe emptyList() }
        }

        "a round trip keeps order and kind, and does not need the files to exist" {
            withDir { dir, config ->
                val list =
                    RecentList
                        .empty(false)
                        .touch(RecentEntry(dir.resolve("gone.kstep.kts"), RecentKind.SCRIPT))
                        .touch(RecentEntry(dir.resolve("folder"), RecentKind.FOLDER))
                store(config).save(list)
                store(config).load().entries shouldBe list.entries
            }
        }

        "unicode and direction characters in a name survive the round trip" {
            withDir { dir, config ->
                val entry = RecentEntry(dir.resolve("bäu‮m.kstep.kts"), RecentKind.SCRIPT)
                // U+202E is not a control character below U+0020: it is kept and escaped only when displayed
                store(config).save(RecentList.empty(false).touch(entry))
                store(config).load().entries shouldBe listOf(entry)
            }
        }

        "the file is private where the platform supports it" {
            withDir { dir, config ->
                store(config).save(RecentList.empty(false).touch(RecentEntry(dir, RecentKind.FOLDER)))
                val file = config.resolve(RecentFilesStore.FILE_NAME)
                if (posix) PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
            }
        }

        "a broken file, a non-object and a wrong or missing version give an empty list" {
            withDir { _, config ->
                val good = json("folder", abs("f"))
                listOf(
                    "{ not json",
                    "[1,2]",
                    "{\"version\":2,\"entries\":[$good]}",
                    "{\"version\":\"1\",\"entries\":[$good]}",
                    "{\"entries\":[$good]}",
                    "{\"version\":1}",
                    "{\"version\":1,\"entries\":{}}",
                ).forEach {
                    write(config, it)
                    store(config).load().entries shouldBe emptyList()
                }
            }
        }

        "a file of exactly 16 KiB is read, one byte more is ignored" {
            withDir { _, config ->
                val head = doc(json("folder", abs("f"))).removeSuffix("}")

                fun padded(size: Int): String {
                    val base = "$head,\"pad\":\"\"}"
                    return base.replace("\"pad\":\"\"", "\"pad\":\"" + "x".repeat(size - base.length + 0) + "\"")
                }
                val exact = padded(RecentFilesStore.MAX_BYTES.toInt())
                Files.size(Files.writeString(config.resolve(RecentFilesStore.FILE_NAME), exact)) shouldBe
                    RecentFilesStore.MAX_BYTES
                store(config).load().entries.size shouldBe 1
                write(config, exact + " ")
                store(config).load().entries shouldBe emptyList()
            }
        }

        "invalid entries are dropped one by one" {
            withDir { _, config ->
                val good = json("folder", abs("f"))
                write(
                    config,
                    doc(
                        json("script", "relative/a.kstep.kts"),
                        json("folder", abs("x") + "/../y"),
                        json("folder", abs("nul") + "\\u0000"),
                        json("folder", abs("ctl") + "\\n"),
                        json("folder", abs("del") + "\\u007f"),
                        json("other", abs("k")),
                        json("script", abs("noext.txt")),
                        "{\"kind\":\"folder\",\"path\":5}",
                        "\"text\"",
                        good,
                    ),
                )
                store(config).load().entries.size shouldBe 1
            }
        }

        "a script name is checked case-insensitively for its suffix" {
            withDir { _, config ->
                write(config, doc(json("script", abs("A.KSTEP.KTS"))))
                store(config).load().entries.size shouldBe 1
            }
        }

        "only the first ten entries are taken" {
            withDir { _, config ->
                write(config, doc(*(1..14).map { json("folder", abs("f$it")) }.toTypedArray()))
                store(config).load().entries.size shouldBe 10
            }
        }

        "a path of more than 4096 characters is dropped" {
            withDir { _, config ->
                write(config, doc(json("folder", abs("f") + "/" + "d".repeat(4100))))
                store(config).load().entries shouldBe emptyList()
            }
        }

        "a recent.json that is a symbolic link is ignored" {
            if (posix) {
                withDir { dir, config ->
                    val real = dir.resolve("real.json")
                    Files.writeString(real, doc(json("folder", abs("f"))))
                    Files.createSymbolicLink(config.resolve(RecentFilesStore.FILE_NAME), real)
                    store(config).load().entries shouldBe emptyList()
                }
            }
        }

        "a folder that cannot be created is swallowed" {
            withDir { dir, _ ->
                val blocker = Files.writeString(dir.resolve("blocker"), "x")
                RecentFilesStore(
                    blocker.resolve("sub"),
                    false,
                ).save(RecentList.empty(false).touch(RecentEntry(dir, RecentKind.FOLDER)))
                RecentFilesStore(blocker.resolve("sub"), false).load().entries shouldBe emptyList()
            }
        }
    })
