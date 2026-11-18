package dev.kstep.desktop.okf

import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class WorkspaceScannerTest :
    StringSpec({
        val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

        fun dir(): Path = Files.createTempDirectory("okf-scan").toRealPath()

        fun note(
            root: Path,
            rel: String,
            text: String = "# n\n```kstep\nx\n```\n",
        ): Path {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
            return p
        }

        fun ok(r: ScanResult): ScanResult.Ok = r.shouldBeInstanceOf<ScanResult.Ok>()

        "notes are listed sorted ignoring case; hidden folders and node_modules are skipped" {
            val root = dir()
            note(root, "b.md")
            note(root, "A.md", "---\ntitle: Alpha\n---\n```kstep\nx\n```\n```kstep\ny\n```\n")
            note(root, "sub/c.MD")
            note(root, ".git/x.md")
            note(root, ".obsidian/y.md")
            note(root, "node_modules/z.md")
            note(root, "readme.txt", "no")
            val r = ok(WorkspaceScanner().scan(root) { false })
            r.entries.map { it.relative } shouldBe listOf("A.md", "b.md", "sub/c.MD")
            r.entries[0].title shouldBe "Alpha"
            r.entries[0].blockCount shouldBe 2
            r.partial shouldBe emptySet()
        }

        "a deep tree reports DEPTH" {
            val root = dir()
            note(root, "a/b/c/d/e/f/g/h/i/j/k/deep.md")
            note(root, "top.md")
            val r = ok(WorkspaceScanner().scan(root) { false })
            r.partial shouldContain PartialReason.DEPTH
            r.entries.map { it.relative } shouldBe listOf("top.md")
        }

        "5000 files stop at exactly 2000 with FILES".config(timeout = 60.seconds) {
            val root = dir()
            repeat(5_000) { Files.writeString(root.resolve("n$it.md"), "x") }
            val r = ok(WorkspaceScanner().scan(root) { false })
            r.entries.size shouldBe OkfLimits.MAX_MD_FILES
            r.partial shouldContain PartialReason.FILES
        }

        "the byte budget, the entry budget and the time budget end the scan" {
            val root = dir()
            repeat(20) { Files.writeString(root.resolve("n$it.md"), "x".repeat(100)) }
            ok(WorkspaceScanner(limits = ScanLimits(maxBytes = 450)).scan(root) { false }).partial shouldContain
                PartialReason.BYTES
            ok(WorkspaceScanner(limits = ScanLimits(maxEntries = 5)).scan(root) { false }).partial shouldContain
                PartialReason.ENTRIES
            var now = 0L
            val clock = { now.also { now += 10 } }
            ok(
                WorkspaceScanner(clock = clock, limits = ScanLimits(maxMillis = 50)).scan(root) { false },
            ).partial shouldContain
                PartialReason.TIME
        }

        "cancelling fails the scan" {
            val root = dir()
            note(root, "a.md")
            val r = WorkspaceScanner().scan(root) { true }
            r.shouldBeInstanceOf<ScanResult.Failed>()
        }

        "symbolic links are counted, never listed or followed" {
            if (posix) {
                val root = dir()
                val outside = dir()
                note(outside, "secret.md")
                note(root, "real.md")
                Files.createSymbolicLink(root.resolve("link.md"), outside.resolve("secret.md"))
                Files.createSymbolicLink(root.resolve("linkdir"), outside)
                Files.createSymbolicLink(root.resolve("inner.md"), root.resolve("real.md"))
                val r = ok(WorkspaceScanner().scan(root) { false })
                r.entries.map { it.relative } shouldBe listOf("real.md")
                r.linksSkipped shouldBe 3
            }
        }

        "names with bidi and control characters are listed as they are (the UI escapes them)" {
            if (posix) {
                val root = dir()
                val name = "evil" + 0x202E.toChar() + "dm.md"
                note(root, name)
                note(root, "ctl\u001Bx.md")
                val r = ok(WorkspaceScanner().scan(root) { false })
                r.entries.size shouldBe 2
                DisplayName.escaped(r.entries.first { it.relative.startsWith("evil") }.relative) shouldBe
                    "evil‹U+202E›dm.md"
            }
        }

        "an empty folder and a file instead of a folder fail with KSTEP-D-025" {
            val root = dir()
            val empty = WorkspaceScanner().scan(root) { false }.shouldBeInstanceOf<ScanResult.Failed>()
            empty.error.code shouldBe DesktopErrorCodes.WORKSPACE_UNAVAILABLE
            empty.error.message.contains("No Markdown files") shouldBe true
            val file = note(root, "a.txt", "x")
            WorkspaceScanner()
                .scan(file) { false }
                .shouldBeInstanceOf<ScanResult.Failed>()
                .error.code shouldBe
                "KSTEP-D-025"
        }

        "a note above the limit is listed as too large and not read" {
            val root = dir()
            note(root, "big.md", "x".repeat(2_000))
            val r = ok(WorkspaceScanner(limits = ScanLimits(maxFileBytes = 1_000)).scan(root) { false })
            r.entries.single().tooLarge shouldBe true
            r.entries.single().blockCount shouldBe 0
        }

        "a symlinked root is allowed (the user chose it)" {
            if (posix) {
                val real = dir()
                note(real, "a.md")
                val link = dir().resolve("rootlink")
                Files.createSymbolicLink(link, real)
                val r = ok(WorkspaceScanner().scan(link) { false })
                r.root shouldBe real
                r.entries.size shouldBe 1
            }
        }
    })
