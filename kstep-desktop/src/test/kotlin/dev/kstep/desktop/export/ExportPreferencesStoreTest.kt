package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
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

        "a file over 64 KiB is ignored" {
            withDir { dir, config ->
                val body = "{\"lastFormat\":\"glb\",\"lastDir\":\"$dir\",\"pad\":\"" + "x".repeat(70_000) + "\"}"
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

        fun key(n: Int) = "%064x".format(n)

        "a file without a version is read as the old format" {
            withDir { dir, config ->
                Files.writeString(
                    config.resolve(ExportPreferencesStore.FILE_NAME),
                    "{\"lastFormat\":\"stl\",\"lastDir\":\"$dir\",\"scripts\":{\"${key(1)}\":{\"format\":\"glb\"}}}",
                )
                ExportPreferencesStore(config).load() shouldBe ExportPreferences(ExportFormat.STL, dir)
            }
        }

        "an unknown version gives defaults" {
            withDir { dir, config ->
                Files.writeString(
                    config.resolve(ExportPreferencesStore.FILE_NAME),
                    "{\"version\":3,\"lastFormat\":\"stl\",\"lastDir\":\"$dir\"}",
                )
                ExportPreferencesStore(config).load() shouldBe ExportPreferences()
            }
        }

        "a per-script entry round-trips" {
            withDir { dir, config ->
                val store = ExportPreferencesStore(config)
                val entry = ScriptExportPreference(ExportFormat.STL, dir, "part.stl", 1_000)
                store.remember(ExportFormat.STL, dir, key(1), entry)
                val loaded = store.load()
                loaded.lastFormat shouldBe ExportFormat.STL
                loaded.scripts shouldBe mapOf(key(1) to entry)
            }
        }

        "a script without a path leaves the entries alone" {
            withDir { dir, config ->
                val store = ExportPreferencesStore(config)
                val entry = ScriptExportPreference(ExportFormat.STL, dir, null, 1_000)
                store.remember(ExportFormat.STL, dir, key(1), entry)
                store.remember(ExportFormat.GLB, dir)
                store.load().scripts shouldBe mapOf(key(1) to entry)
                store.load().lastFormat shouldBe ExportFormat.GLB
            }
        }

        "33 entries are cut to the 32 most recent, ties broken by key" {
            withDir { dir, config ->
                val store = ExportPreferencesStore(config)
                var scripts = emptyMap<String, ScriptExportPreference>()
                for (i in 1..33) {
                    scripts =
                        scripts + (key(i) to ScriptExportPreference(ExportFormat.STL, dir, null, (i / 2).toLong()))
                }
                store.save(ExportPreferences(scripts = scripts))
                val loaded = store.load().scripts
                loaded.size shouldBe 32
                // usedAt 0 only for key 1; it is the oldest and drops
                loaded.containsKey(key(1)) shouldBe false
                loaded.containsKey(key(33)) shouldBe true
            }
        }

        "the written file stays below the 64 KiB limit with 32 entries" {
            withDir { dir, config ->
                val store = ExportPreferencesStore(config)
                val long = "n".repeat(50) + ".stl"
                val scripts =
                    (1..32).associate {
                        key(
                            it,
                        ) to ScriptExportPreference(ExportFormat.STL, dir, long, 1_000)
                    }
                store.save(ExportPreferences(ExportFormat.STL, dir, scripts))
                (
                    Files.size(
                        config.resolve(ExportPreferencesStore.FILE_NAME),
                    ) < ExportPreferencesStore.MAX_BYTES
                ) shouldBe
                    true
                store.load().scripts.size shouldBe 32
            }
        }

        "bad keys, directories, targets and times are dropped field by field" {
            withDir { dir, config ->
                val real = Files.createDirectory(dir.resolve("real"))
                val body =
                    """
                    {"version":2,"scripts":{
                      "${key(1)}":{"format":"stl","dir":"relative","target":"a.stl","usedAt":-5},
                      "${key(2)}":{"format":"stl","dir":"$real","target":"a/b.stl","usedAt":99999999999999},
                      "${key(3)}":{"dir":"$real","target":"x.stl"},
                      "${key(4)}":{"format":"glb","dir":"${"d".repeat(1100)}"},
                      "${key(0xabcdef).uppercase()}":{"format":"stl"},
                      "short":{"format":"stl"},
                      "${key(6)}":{"usedAt":"x"}
                    }}
                    """.trimIndent()
                Files.writeString(config.resolve(ExportPreferencesStore.FILE_NAME), body)
                val loaded = ExportPreferencesStore(config) { 1_000 }.load().scripts
                loaded[key(1)] shouldBe ScriptExportPreference(ExportFormat.STL, null, "a.stl", 0)
                loaded[key(2)] shouldBe ScriptExportPreference(ExportFormat.STL, real, null, 1_000 + 86_400)
                loaded[key(3)] shouldBe ScriptExportPreference(null, real, null, 0)
                loaded[key(4)] shouldBe ScriptExportPreference(ExportFormat.GLB, null, null, 0)
                loaded.containsKey(key(0xabcdef).uppercase()) shouldBe false
                loaded.containsKey("short") shouldBe false
                loaded.containsKey(key(6)) shouldBe false
            }
        }

        "an entry with a control character in the target loses only the target" {
            withDir { dir, config ->
                val body = "{\"version\":2,\"scripts\":{\"${key(
                    1,
                )}\":{\"format\":\"stl\",\"target\":\"a\\u0000.stl\"}}}"
                Files.writeString(config.resolve(ExportPreferencesStore.FILE_NAME), body)
                ExportPreferencesStore(config).load().scripts[key(1)] shouldBe
                    ScriptExportPreference(ExportFormat.STL, null, null, 0)
            }
        }

        "two stores do not delete each other's entries" {
            withDir { dir, config ->
                val a = ExportPreferencesStore(config)
                val b = ExportPreferencesStore(config)
                a.load()
                b.load()
                a.remember(ExportFormat.STL, dir, key(1), ScriptExportPreference(ExportFormat.STL, dir, null, 1))
                b.remember(ExportFormat.GLB, dir, key(2), ScriptExportPreference(ExportFormat.GLB, dir, null, 2))
                ExportPreferencesStore(config).load().scripts.keys shouldBe setOf(key(1), key(2))
            }
        }

        "a hostile file with hundreds of entries keeps 32 and deep nesting gives defaults" {
            withDir { dir, config ->
                val many = (1..550).joinToString(",") { "\"${key(it)}\":{\"format\":\"stl\",\"usedAt\":$it}" }
                Files.writeString(
                    config.resolve(ExportPreferencesStore.FILE_NAME),
                    "{\"version\":2,\"scripts\":{$many}}",
                )
                ExportPreferencesStore(config).load().scripts.size shouldBe 32
                Files.writeString(config.resolve(ExportPreferencesStore.FILE_NAME), "[".repeat(60_000))
                ExportPreferencesStore(config).load() shouldBe ExportPreferences()
            }
        }

        "the script key is 64 hex characters and stable for relative, absolute and unnormalized paths" {
            val abs = Path.of("/tmp/x/a.kstep.kts")
            val k = ExportPreferencesStore.scriptKey(abs)
            Regex("^[0-9a-f]{64}$").matches(k) shouldBe true
            ExportPreferencesStore.scriptKey(Path.of("/tmp/x/../x/./a.kstep.kts")) shouldBe k
            ExportPreferencesStore.scriptKey(Path.of("a.kstep.kts")) shouldBe
                ExportPreferencesStore.scriptKey(Path.of("a.kstep.kts").toAbsolutePath())
            (ExportPreferencesStore.scriptKey(Path.of("/tmp/x/\u00e4.kstep.kts")) == k) shouldBe false
        }

        "saving into an unwritable place never throws" {
            withDir { dir, _ ->
                val blocker = Files.writeString(dir.resolve("file"), "x")
                ExportPreferencesStore(blocker.resolve("sub")).remember(ExportFormat.STL, dir, key(1), null)
            }
        }
    })
