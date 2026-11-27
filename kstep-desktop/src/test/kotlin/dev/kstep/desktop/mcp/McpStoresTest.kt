package dev.kstep.desktop.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private fun posix(p: Path) = "posix" in p.fileSystem.supportedFileAttributeViews()

class McpTokenStoreTest :
    StringSpec({
        "there is no file before the first switch-on; getOrCreate makes one of 43 base64url characters" {
            withDir { dir ->
                val store = McpTokenStore(dir)
                store.current() shouldBe null
                Files.exists(dir.resolve(McpTokenStore.FILE_NAME)) shouldBe false
                val t = store.getOrCreate()
                Regex("^[A-Za-z0-9_-]{43}$").matches(t.value) shouldBe true
                store.current()!!.value shouldBe t.value
                store.getOrCreate().value shouldBe t.value
            }
        }

        "the file is rw------- and a new folder is rwx------" {
            val base =
                dev.kstep.desktop.TestSupport
                    .newDir()
            try {
                val dir = base.resolve("fresh")
                McpTokenStore(dir).getOrCreate()
                if (posix(dir)) {
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
                    PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(dir.resolve(McpTokenStore.FILE_NAME)),
                    ) shouldBe "rw-------"
                }
            } finally {
                base.toFile().deleteRecursively()
            }
        }

        "toString never shows the value and equality is by identity" {
            withDir { dir ->
                val t = McpTokenStore(dir).getOrCreate()
                t.toString() shouldBe "McpToken(***)"
                (t == McpTokenStore(dir).current()) shouldBe false
            }
        }

        "a symbolic link, a long file and a malformed file are ignored" {
            withDir { dir ->
                val store = McpTokenStore(dir)
                val file = dir.resolve(McpTokenStore.FILE_NAME)
                val target = dir.resolve("real")
                Files.writeString(target, "A".repeat(43))
                if (posix(dir)) Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
                try {
                    Files.createSymbolicLink(file, target)
                    store.current() shouldBe null
                } catch (_: UnsupportedOperationException) {
                    // no symbolic links here
                }
                Files.deleteIfExists(file)
                Files.writeString(file, "A".repeat(500))
                store.current() shouldBe null
                Files.writeString(file, "short")
                store.current() shouldBe null
                Files.writeString(file, "A".repeat(42) + "!")
                store.current() shouldBe null
            }
        }

        "a file with wider permissions is not used and the next getOrCreate makes a new token" {
            withDir { dir ->
                if (posix(dir)) {
                    val store = McpTokenStore(dir)
                    val file = dir.resolve(McpTokenStore.FILE_NAME)
                    Files.writeString(file, "A".repeat(43))
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
                    store.current() shouldBe null
                    val fresh = store.getOrCreate()
                    (fresh.value == "A".repeat(43)) shouldBe false
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
                }
            }
        }

        "regenerate gives a different token" {
            withDir { dir ->
                val store = McpTokenStore(dir)
                val a = store.getOrCreate().value
                val b = store.regenerate().value
                (a == b) shouldBe false
                store.current()!!.value shouldBe b
            }
        }

        "a folder that cannot be written gives McpTokenWriteException" {
            withDir { dir ->
                if (posix(dir) && System.getProperty("user.name") != "root") {
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
                    try {
                        shouldThrow<McpTokenWriteException> { McpTokenStore(dir).getOrCreate() }
                    } finally {
                        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
                    }
                }
            }
        }

        "matches compares equal, different and different-length values" {
            withDir { dir ->
                val t = McpTokenStore(dir).getOrCreate()
                t.matches(t.value) shouldBe true
                t.matches("A".repeat(43)) shouldBe false
                t.matches(t.value.dropLast(1)) shouldBe false
                t.matches("") shouldBe false
            }
        }
    })

class McpPreferencesStoreTest :
    StringSpec({
        fun loadOf(
            dir: Path,
            body: String,
        ): McpSettings {
            Files.writeString(dir.resolve(McpPreferencesStore.FILE_NAME), body)
            return McpPreferencesStore(dir).load()
        }

        val default = McpSettings()

        "the default is off on port 7391" {
            withDir { McpPreferencesStore(it).load() shouldBe default }
            default.enabled shouldBe false
            default.port shouldBe 7391
        }

        "a round trip keeps enabled and port" {
            withDir { dir ->
                val store = McpPreferencesStore(dir)
                store.save(McpSettings(true, 7400)) shouldBe true
                store.load() shouldBe McpSettings(true, 7400)
                if (posix(dir)) {
                    PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(dir.resolve(McpPreferencesStore.FILE_NAME)),
                    ) shouldBe "rw-------"
                }
            }
        }

        "values that are not exactly right give the defaults (off)" {
            withDir { dir ->
                loadOf(dir, """{"version":1,"enabled":true,"port":8000}""") shouldBe McpSettings(true, 8000)
                loadOf(dir, """{"version":1,"enabled":true}""") shouldBe McpSettings(true, 7391)
                listOf(
                    """{"version":1,"enabled":"true"}""",
                    """{"version":1,"enabled":true,"port":"7391"}""",
                    """{"version":1,"enabled":true,"port":80}""",
                    """{"version":1,"enabled":true,"port":70000}""",
                    """{"version":1,"enabled":true,"port":7391.5}""",
                    """{"version":2,"enabled":true}""",
                    """{"version":"1","enabled":true}""",
                    """{"enabled":true}""",
                    """{"version":1,"enabled":null}""",
                    """[]""",
                    """not json""",
                ).forEach { loadOf(dir, it) shouldBe default }
            }
        }

        "allowActions is off when the field is missing and survives a round trip" {
            withDir { dir ->
                loadOf(dir, """{"version":1,"enabled":true,"port":8000}""").allowActions shouldBe false
                loadOf(dir, """{"version":1,"enabled":true,"allowActions":true}""") shouldBe
                    McpSettings(true, 7391, true)
                val store = McpPreferencesStore(dir)
                store.save(McpSettings(true, 7400, true)) shouldBe true
                store.load() shouldBe McpSettings(true, 7400, true)
                Files.readString(dir.resolve(McpPreferencesStore.FILE_NAME)) shouldContain "\"allowActions\":true"
                store.save(McpSettings(true, 7400)) shouldBe true
                Files.readString(dir.resolve(McpPreferencesStore.FILE_NAME)) shouldContain "\"allowActions\":false"
            }
        }

        "a wrong type of allowActions gives all defaults: server off and no changes" {
            withDir { dir ->
                listOf(
                    """{"version":1,"enabled":true,"allowActions":"true"}""",
                    """{"version":1,"enabled":true,"allowActions":1}""",
                    """{"version":1,"enabled":true,"allowActions":null}""",
                    """{"version":1,"enabled":true,"allowActions":{}}""",
                    """{"version":1,"enabled":true,"allowActions":[]}""",
                ).forEach { loadOf(dir, it) shouldBe default }
            }
        }

        "a file with the new field stays within the 1 KiB limit" {
            withDir { dir ->
                McpPreferencesStore(dir).save(McpSettings(true, 65535, true)) shouldBe true
                (Files.size(dir.resolve(McpPreferencesStore.FILE_NAME)) < McpPreferencesStore.MAX_BYTES) shouldBe true
                McpPreferencesStore(dir).load() shouldBe McpSettings(true, 65535, true)
            }
        }

        "a long file and invalid UTF-8 give the defaults" {
            withDir { dir ->
                loadOf(dir, """{"version":1,"enabled":true,"x":"${"a".repeat(2000)}"}""") shouldBe default
                Files.write(dir.resolve(McpPreferencesStore.FILE_NAME), byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D))
                McpPreferencesStore(dir).load() shouldBe default
            }
        }

        "a symbolic link gives the defaults" {
            withDir { dir ->
                val real = dir.resolve("real.json")
                Files.writeString(real, """{"version":1,"enabled":true}""")
                try {
                    Files.createSymbolicLink(dir.resolve(McpPreferencesStore.FILE_NAME), real)
                    McpPreferencesStore(dir).load() shouldBe default
                } catch (_: UnsupportedOperationException) {
                    // no symbolic links here
                }
            }
        }

        "save reports false when the folder cannot be written" {
            withDir { dir ->
                if (posix(dir) && System.getProperty("user.name") != "root") {
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
                    try {
                        McpPreferencesStore(dir).save(McpSettings(true, 7391)) shouldBe false
                    } finally {
                        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
                    }
                }
            }
        }
    })
