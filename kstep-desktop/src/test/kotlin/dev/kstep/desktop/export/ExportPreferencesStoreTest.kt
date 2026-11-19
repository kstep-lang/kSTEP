package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** What the export dialog remembers is untrusted on load and best effort on save (ADR-0063). */
class ExportPreferencesStoreTest :
    StringSpec({
        fun withDir(block: (java.nio.file.Path, java.nio.file.Path) -> Unit) {
            val dir = TestSupport.newDir()
            try {
                block(dir, Files.createDirectory(dir.resolve("config")))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a missing file gives defaults" {
            withDir { _, config -> ExportPreferencesStore(config).load() shouldBe ExportPreferences() }
        }

        "a round trip keeps the format and the folder" {
            withDir { dir, config ->
                val folder = Files.createDirectory(dir.resolve("exports"))
                val store = ExportPreferencesStore(config)
                store.save(ExportPreferences(ExportFormat.THREE_MF, folder))
                store.load() shouldBe ExportPreferences(ExportFormat.THREE_MF, folder)
            }
        }

        "the file is private where the platform supports it" {
            withDir { dir, config ->
                ExportPreferencesStore(config).save(ExportPreferences(ExportFormat.STL, dir))
                val file = config.resolve(ExportPreferencesStore.FILE_NAME)
                if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
                }
            }
        }

        "a broken file gives defaults" {
            withDir { _, config ->
                Files.writeString(config.resolve(ExportPreferencesStore.FILE_NAME), "{ not json")
                ExportPreferencesStore(config).load() shouldBe ExportPreferences()
                Files.writeString(config.resolve(ExportPreferencesStore.FILE_NAME), "[1,2]")
                ExportPreferencesStore(config).load() shouldBe ExportPreferences()
            }
        }

        "a file over 4 KiB is ignored" {
            withDir { dir, config ->
                val body = "{\"lastFormat\":\"glb\",\"lastDir\":\"$dir\",\"pad\":\"" + "x".repeat(5000) + "\"}"
                Files.writeString(config.resolve(ExportPreferencesStore.FILE_NAME), body)
                ExportPreferencesStore(config).load() shouldBe ExportPreferences()
            }
        }

        "an unknown format, a relative folder and a missing folder are dropped one by one" {
            withDir { dir, config ->
                val file = config.resolve(ExportPreferencesStore.FILE_NAME)
                Files.writeString(file, "{\"lastFormat\":\"obj\",\"lastDir\":\"relative/dir\"}")
                ExportPreferencesStore(config).load() shouldBe ExportPreferences()
                Files.writeString(file, "{\"lastFormat\":\"stl\",\"lastDir\":\"${dir.resolve("gone")}\"}")
                ExportPreferencesStore(config).load() shouldBe ExportPreferences(ExportFormat.STL, null)
                Files.writeString(file, "{\"lastFormat\":5,\"lastDir\":[\"x\"]}")
                ExportPreferencesStore(config).load() shouldBe ExportPreferences()
            }
        }

        "a folder reached through a symbolic link is dropped" {
            withDir { dir, config ->
                val real = Files.createDirectory(dir.resolve("real"))
                val link =
                    try {
                        Files.createSymbolicLink(dir.resolve("link"), real)
                    } catch (_: Exception) {
                        return@withDir // no symlinks here (Windows without Developer Mode)
                    }
                Files.writeString(
                    config.resolve(ExportPreferencesStore.FILE_NAME),
                    "{\"lastFormat\":\"glb\",\"lastDir\":\"${link.toString().replace("\\", "\\\\")}\"}",
                )
                ExportPreferencesStore(config).load() shouldBe ExportPreferences(ExportFormat.GLB, null)
            }
        }

        "an unwritable config folder never throws" {
            withDir { dir, _ ->
                val blocker = Files.writeString(dir.resolve("file"), "x")
                ExportPreferencesStore(blocker.resolve("sub")).save(ExportPreferences(ExportFormat.GLB, dir))
            }
        }
    })
