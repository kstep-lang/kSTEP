package dev.kstep.desktop.settings

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LayoutPreferencesStoreTest :
    StringSpec({
        fun withDir(block: (Path) -> Unit) {
            val dir = TestSupport.newDir()
            try {
                block(Files.createDirectory(dir.resolve("config")))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        fun posix(p: Path) = "posix" in p.fileSystem.supportedFileAttributeViews()

        fun loadOf(
            config: Path,
            body: String,
        ): Double {
            Files.writeString(config.resolve(LayoutPreferencesStore.FILE_NAME), body)
            return LayoutPreferencesStore(config).load().editorFraction
        }

        val default = LayoutPreferencesStore.DEFAULT

        "a missing file gives the default" {
            withDir {
                LayoutPreferencesStore(
                    it,
                ).load().editorFraction shouldBe default
            }
        }

        "a round trip keeps three decimals" {
            withDir {
                val store = LayoutPreferencesStore(it)
                store.save(LayoutPreferences(0.455))
                store.load().editorFraction shouldBe 0.455
                store.save(LayoutPreferences(0.123456))
                store.load().editorFraction shouldBe 0.2 // clamped
                store.save(LayoutPreferences(0.4567))
                store.load().editorFraction shouldBe 0.457
            }
        }

        "values are clamped to 0.20..0.80" {
            withDir { c ->
                loadOf(c, """{"version":1,"editorFraction":-0.5}""") shouldBe 0.20
                loadOf(c, """{"version":1,"editorFraction":0.05}""") shouldBe 0.20
                loadOf(c, """{"version":1,"editorFraction":0.95}""") shouldBe 0.80
            }
        }

        "hostile content gives the default" {
            withDir { c ->
                val bodies =
                    listOf(
                        "",
                        "{ not json",
                        "[1]",
                        """{"version":1,"editorFraction":1e309}""",
                        """{"version":1,"editorFraction":NaN}""",
                        """{"version":1,"editorFraction":"0.5"}""",
                        """{"version":1,"editorFraction":true}""",
                        """{"version":1,"editorFraction":null}""",
                        """{"version":1,"editorFraction":{"a":1}}""",
                        """{"version":1}""",
                        """{"version":2,"editorFraction":0.5}""",
                        """{"version":"1","editorFraction":0.5}""",
                        """{"editorFraction":0.5}""",
                        "[".repeat(1024),
                    )
                for (b in bodies) loadOf(c, b) shouldBe default
            }
        }

        "unknown fields are ignored" {
            withDir { c -> loadOf(c, """{"version":1,"editorFraction":0.6,"extra":[1,2]}""") shouldBe 0.6 }
        }

        "the size limit is 1 KiB" {
            withDir { c ->
                val head = """{"version":1,"editorFraction":0.6,"pad":""""
                val ok = head + "x".repeat(1024 - head.length - 2) + "\"}"
                ok.length shouldBe 1024
                loadOf(c, ok) shouldBe 0.6
                loadOf(c, ok.dropLast(2) + "xx\"}") shouldBe default
            }
        }

        "binary garbage and invalid UTF-8 give the default" {
            withDir { c ->
                Files.write(c.resolve(LayoutPreferencesStore.FILE_NAME), ByteArray(300) { (it * 7).toByte() })
                LayoutPreferencesStore(c).load().editorFraction shouldBe default
                val bad =
                    """{"version":1,"editorFraction":0.6,"p":"x""".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) +
                        "\"}".toByteArray()
                Files.write(c.resolve(LayoutPreferencesStore.FILE_NAME), bad)
                LayoutPreferencesStore(c).load().editorFraction shouldBe default
            }
        }

        "a directory instead of the file gives the default" {
            withDir { c ->
                Files.createDirectory(c.resolve(LayoutPreferencesStore.FILE_NAME))
                LayoutPreferencesStore(c).load().editorFraction shouldBe default
            }
        }

        "a symbolic link is not followed" {
            withDir { c ->
                val target = c.resolveSibling("target.json")
                Files.writeString(target, """{"version":1,"editorFraction":0.7}""")
                try {
                    Files.createSymbolicLink(c.resolve(LayoutPreferencesStore.FILE_NAME), target)
                } catch (_: Exception) {
                    return@withDir // no symlink right on this system
                }
                LayoutPreferencesStore(c).load().editorFraction shouldBe default
            }
        }

        "the file and the folder are private, and no temporary file stays" {
            withDir { c ->
                val dir = c.resolve("fresh")
                val store = LayoutPreferencesStore(dir)
                store.save(LayoutPreferences(0.5))
                store.save(LayoutPreferences(0.6))
                store.load().editorFraction shouldBe 0.6
                if (posix(dir)) {
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
                    PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(dir.resolve(LayoutPreferencesStore.FILE_NAME)),
                    ) shouldBe "rw-------"
                }
                Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() } shouldBe listOf("layout.json")
            }
        }

        "soft wrap round trip, off by default" {
            withDir {
                val store = LayoutPreferencesStore(it)
                store.load().softWrap shouldBe false
                store.save(LayoutPreferences(0.5, softWrap = true))
                store.load() shouldBe LayoutPreferences(0.5, softWrap = true)
                store.save(LayoutPreferences(0.5, softWrap = false))
                store.load().softWrap shouldBe false
            }
        }

        "a file without the soft wrap key means off, the version stays 1" {
            withDir { c ->
                Files.writeString(c.resolve(LayoutPreferencesStore.FILE_NAME), """{"version":1,"editorFraction":0.6}""")
                LayoutPreferencesStore(c).load() shouldBe LayoutPreferences(0.6, softWrap = false)
                val store = LayoutPreferencesStore(c)
                store.save(LayoutPreferences(0.6, softWrap = true))
                Files.readString(c.resolve(LayoutPreferencesStore.FILE_NAME)) shouldBe
                    """{"version":1,"editorFraction":0.6,"softWrap":true}"""
            }
        }

        "a soft wrap value that is not a boolean makes the file untrusted" {
            withDir { c ->
                for (bad in listOf("\"true\"", "1", "null", "[]", "{}", "\"\"")) {
                    val body = """{"version":1,"editorFraction":0.6,"softWrap":$bad}"""
                    Files.writeString(c.resolve(LayoutPreferencesStore.FILE_NAME), body)
                    LayoutPreferencesStore(c).load() shouldBe LayoutPreferences()
                }
            }
        }

        "saving one setting carries the other one, the file stays small" {
            withDir { c ->
                val store = LayoutPreferencesStore(c)
                val first = LayoutPreferences(editorFraction = 0.33, softWrap = true)
                store.save(first)
                store.save(first.copy(softWrap = false))
                store.load() shouldBe LayoutPreferences(0.33, false)
                store.save(first.copy(editorFraction = 0.7))
                store.load() shouldBe LayoutPreferences(0.7, true)
                (Files.size(c.resolve(LayoutPreferencesStore.FILE_NAME)) < LayoutPreferencesStore.MAX_BYTES) shouldBe
                    true
            }
        }

        "a read-only folder does not throw" {
            withDir { c ->
                if (!posix(c) || System.getProperty("user.name") == "root") return@withDir
                c.toFile().setWritable(false)
                try {
                    LayoutPreferencesStore(c).save(LayoutPreferences(0.5))
                } finally {
                    c.toFile().setWritable(true)
                }
            }
        }
    })
