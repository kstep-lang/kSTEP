package dev.kstep.desktop.recent

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** What the prompt history reads is untrusted, what it writes is best effort (ADR-0069). */
class PromptHistoryStoreTest :
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

        fun write(
            config: Path,
            body: String,
        ) = Files.writeString(config.resolve(PromptHistoryStore.FILE_NAME), body)

        "save and load round trip" {
            withDir { _, config ->
                val s = PromptHistoryStore(config)
                s.save(PromptHistory.empty().record("first\nline two").record("second"))
                s.load().entries shouldBe listOf("second", "first\nline two")
            }
        }

        "missing, corrupt, wrong version, version as string, wrong shape: empty" {
            withDir { _, config ->
                val s = PromptHistoryStore(config)
                s.load().entries shouldBe emptyList()
                write(config, "{not json")
                s.load().entries shouldBe emptyList()
                write(config, """{"version":2,"prompts":["a"]}""")
                s.load().entries shouldBe emptyList()
                write(config, """{"version":"1","prompts":["a"]}""")
                s.load().entries shouldBe emptyList()
                write(config, """{"version":1,"prompts":"a"}""")
                s.load().entries shouldBe emptyList()
                write(config, "[]")
                s.load().entries shouldBe emptyList()
            }
        }

        "a file over 256 KiB is ignored although its content is valid" {
            withDir { _, config ->
                val big = (1..40).joinToString(",") { "\"" + "x".repeat(8_000) + it + "\"" }
                write(config, """{"version":1,"prompts":[$big]}""")
                (Files.size(config.resolve(PromptHistoryStore.FILE_NAME)) > PromptHistoryStore.MAX_BYTES) shouldBe true
                PromptHistoryStore(config).load().entries shouldBe emptyList()
            }
        }

        "non-string entries are skipped, the rest is sanitized, de-duplicated and clamped" {
            withDir { _, config ->
                val many = (1..120).joinToString(",") { "\"p$it\"" }
                write(config, """{"version":1,"prompts":[1,null,"a","a‮","",{"x":1},$many]}""")
                val h = PromptHistoryStore(config).load()
                h.entries.first() shouldBe "a"
                h.entries.size shouldBe PromptHistory.MAX_ENTRIES
                (h.entries.distinct().size == h.entries.size) shouldBe true
            }
        }

        "a symlink and a relative directory give an empty history" {
            withDir { dir, config ->
                val real = dir.resolve("real.json")
                Files.writeString(real, """{"version":1,"prompts":["a"]}""")
                try {
                    Files.createSymbolicLink(config.resolve(PromptHistoryStore.FILE_NAME), real)
                    PromptHistoryStore(config).load().entries shouldBe emptyList()
                } catch (_: UnsupportedOperationException) {
                    // no symlinks here
                } catch (_: java.io.IOException) {
                    // no symlink right here
                }
                PromptHistoryStore(Path.of("relative-dir")).load().entries shouldBe emptyList()
            }
        }

        "saving into a directory that is a file never throws and leaves no temp file" {
            withDir { dir, _ ->
                val file = Files.writeString(dir.resolve("blocked"), "x")
                PromptHistoryStore(file).save(PromptHistory.empty().record("a"))
                PromptHistoryStore(file).clear()
                Files.readString(file) shouldBe "x"
            }
        }

        "no temp files remain and the file is private where supported" {
            withDir { _, config ->
                PromptHistoryStore(config).save(PromptHistory.empty().record("secret idea"))
                Files.list(config).use { it.map { p -> p.fileName.toString() }.toList() } shouldBe
                    listOf(PromptHistoryStore.FILE_NAME)
                if (posix) {
                    Files.getPosixFilePermissions(config.resolve(PromptHistoryStore.FILE_NAME)) shouldBe
                        PosixFilePermissions.fromString("rw-------")
                }
            }
        }

        "a saved file always fits the load limit: the oldest entries are dropped" {
            withDir { _, config ->
                val s = PromptHistoryStore(config)
                // 50 entries of 4000 chars are 200 KB; add multi-byte text to exceed 256 KiB
                var h = PromptHistory.empty()
                repeat(50) { h = h.record("ä".repeat(3_900) + it) }
                s.save(h)
                (Files.size(config.resolve(PromptHistoryStore.FILE_NAME)) <= PromptHistoryStore.MAX_BYTES) shouldBe true
                val loaded = s.load()
                loaded.entries.isNotEmpty() shouldBe true
                loaded.entries.first() shouldBe h.entries.first()
            }
        }

        "clear removes the file and tolerates a missing one" {
            withDir { _, config ->
                val s = PromptHistoryStore(config)
                s.clear()
                s.save(PromptHistory.empty().record("a"))
                s.clear()
                Files.exists(config.resolve(PromptHistoryStore.FILE_NAME)) shouldBe false
            }
        }
    })
