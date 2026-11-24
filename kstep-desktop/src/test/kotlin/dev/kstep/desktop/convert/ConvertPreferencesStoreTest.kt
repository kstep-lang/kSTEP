package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertTarget
import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ConvertPreferencesStoreTest :
    StringSpec({
        fun cfg(): Path = TestSupport.newDir()

        fun write(
            dir: Path,
            text: String,
        ) = Files.writeString(dir.resolve(ConvertPreferencesStore.FILE_NAME), text)

        "a round trip" {
            val dir = cfg()
            val out = TestSupport.newDir()
            val store = ConvertPreferencesStore(dir)
            store.remember(out, ConvertTarget.OKF)
            store.load() shouldBe ConvertPreferences(out, ConvertTarget.OKF)
        }

        "a missing file gives the defaults" {
            ConvertPreferencesStore(cfg()).load() shouldBe ConvertPreferences()
        }

        "remember keeps a value it is not given" {
            val dir = cfg()
            val out = TestSupport.newDir()
            val store = ConvertPreferencesStore(dir)
            store.remember(out, ConvertTarget.KTS)
            store.remember(null, ConvertTarget.OKF)
            store.load() shouldBe ConvertPreferences(out, ConvertTarget.OKF)
        }

        "a file over 64 KiB is ignored" {
            val dir = cfg()
            write(dir, "{\"version\":1,\"lastTarget\":\"okf\"," + " ".repeat(70_000) + "}")
            ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
        }

        "a symbolic-link file is ignored" {
            val dir = cfg()
            val real = TestSupport.newDir().resolve("real.json")
            Files.writeString(real, "{\"version\":1,\"lastTarget\":\"okf\"}")
            try {
                Files.createSymbolicLink(dir.resolve(ConvertPreferencesStore.FILE_NAME), real)
                ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
            } catch (_: UnsupportedOperationException) {
                // no symbolic links here
            } catch (_: java.io.IOException) {
                // no symbolic links here
            }
        }

        "invalid json, unknown version and a bad target give safe values" {
            val dir = cfg()
            write(dir, "{nope")
            ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
            write(dir, "{\"version\":2,\"lastTarget\":\"okf\"}")
            ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
            write(dir, "{\"version\":1,\"lastTarget\":\"xyz\"}")
            ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
            write(dir, "[[[[[".repeat(20_000))
            ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
        }

        "a relative, missing, NUL or symlinked folder is dropped" {
            val dir = cfg()
            write(dir, "{\"version\":1,\"lastOutDir\":\"relative/dir\"}")
            ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
            write(dir, "{\"version\":1,\"lastOutDir\":\"/definitely/not/here\"}")
            ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
            write(dir, "{\"version\":1,\"lastOutDir\":\"/tmp\\u0000x\"}")
            ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
            val real = TestSupport.newDir()
            val link = TestSupport.newDir().resolve("link")
            try {
                Files.createSymbolicLink(link, real)
                write(dir, "{\"version\":1,\"lastOutDir\":\"${link.toString().replace("\\", "\\\\")}\"}")
                ConvertPreferencesStore(dir).load() shouldBe ConvertPreferences()
            } catch (_: UnsupportedOperationException) {
                // no symbolic links here
            } catch (_: java.io.IOException) {
                // no symbolic links here
            }
        }

        "the saved file has mode 0600 where POSIX is supported" {
            val dir = cfg()
            ConvertPreferencesStore(dir).save(ConvertPreferences(null, ConvertTarget.OKF))
            val file = dir.resolve(ConvertPreferencesStore.FILE_NAME)
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
            }
        }

        "a failed save throws nothing" {
            val dir = cfg()
            val blocker = dir.resolve("file")
            Files.writeString(blocker, "x")
            // the config dir is a regular file: creating the folder and the temp file both fail
            ConvertPreferencesStore(blocker).save(ConvertPreferences(null, ConvertTarget.OKF))
            ConvertPreferencesStore(blocker.resolve("sub")).save(ConvertPreferences(null, ConvertTarget.OKF))
        }

        "export.json in the same folder stays untouched" {
            val dir = cfg()
            val export = dir.resolve("export.json")
            Files.writeString(export, "{\"version\":2}")
            ConvertPreferencesStore(dir).remember(null, ConvertTarget.OKF)
            Files.readString(export) shouldBe "{\"version\":2}"
        }
    })
