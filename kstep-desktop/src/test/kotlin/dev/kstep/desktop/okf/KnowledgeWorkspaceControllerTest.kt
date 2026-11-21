package dev.kstep.desktop.okf

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.edit.SaveFailure
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.watch.WatchSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class KnowledgeWorkspaceControllerTest :
    StringSpec({
        val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
        val noteText = "# Note\n\n```kstep\nshape(a)\n```\n\n```kstep\nshape(b)\n```\n"

        class Harness {
            val root: Path = TestSupport.newDir()
            val ran = CopyOnWriteArrayList<String>()
            val tasks = CopyOnWriteArrayList<() -> Unit>()
            val closedTimers = AtomicInteger(0)
            val requested = AtomicInteger(0)
            var reply: (Path, Long) -> RunResult = { _, gen ->
                RunResult.Success(DesktopModel(null, 0, gen, Instant.EPOCH, 1, emptyList()))
            }
            val session: Path = BlockSessionFile.createSessionDir()
            val controller =
                KnowledgeWorkspaceController(
                    workerRun = { file, gen, _ ->
                        ran += Files.readString(file)
                        reply(file, gen)
                    },
                    sessionDir = session,
                    scheduler = { _, task ->
                        tasks += task
                        AutoCloseable { closedTimers.incrementAndGet() }
                    },
                ).also { it.onRunRequested = { requested.incrementAndGet() } }

            fun note(
                name: String = "n.md",
                text: String = noteText,
            ): Path = root.resolve(name).also { Files.writeString(it, text) }

            fun open(
                vararg notes: Pair<String, String>,
                trust: Boolean = true,
            ) {
                if (notes.isEmpty()) note() else notes.forEach { note(it.first, it.second) }
                controller.openFolder(root)
                TestSupport.await(30_000) { controller.workspace.value is WorkspaceState.Open } shouldBe true
                if (trust) {
                    controller.trustFolder()
                    requested.set(0)
                }
            }

            fun cleanup() {
                controller.close()
                root.toFile().deleteRecursively()
            }
        }

        "an untrusted folder never runs a block, neither on request nor by caret move" {
            val h = Harness()
            try {
                h.open(trust = false)
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.controller.runBlock(md, 1) {}.shouldBeInstanceOf<RunResult.Skipped>()
                h.controller.onBuffer(noteText, noteText.indexOf("shape(b)"))
                h.tasks.size shouldBe 0
                h.ran.size shouldBe 0
                h.controller.trustFolder()
                h.controller.runBlock(md, 2) {}.shouldBeInstanceOf<RunResult.Success>()
                h.controller.closeWorkspace()
                h.controller.trusted shouldBe false
            } finally {
                h.cleanup()
            }
        }

        "opening a note runs block 1 from a private session file" {
            val h = Harness()
            try {
                h.open()
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.controller.runBlock(md, 1) {}.shouldBeInstanceOf<RunResult.Success>()
                h.ran.single() shouldBe "shape(a)"
                Files.exists(h.session.resolve("block-1.kstep.kts")) shouldBe false
            } finally {
                h.cleanup()
            }
        }

        "moving the caret into another block runs once after the debounce; quick moves restart the timer" {
            val h = Harness()
            try {
                h.open()
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.controller.runBlock(md, 1) {}
                val inSecond = noteText.indexOf("shape(b)")
                h.controller.onBuffer(noteText, inSecond)
                h.tasks.size shouldBe 1
                h.controller.header.value!!
                    .index shouldBe 1
                h.controller.onBuffer(noteText, inSecond + 2) // same block: nothing new
                h.tasks.size shouldBe 1
                h.controller.onBuffer(noteText, noteText.indexOf("shape(a)")) // back: restarts the timer
                h.tasks.size shouldBe 2
                h.closedTimers.get() shouldBe 1
                h.tasks[h.tasks.size - 1].invoke() // fires: same block as the last run -> no run
                h.requested.get() shouldBe 0
                h.controller.onBuffer(noteText, inSecond)
                h.tasks[h.tasks.size - 1].invoke() // block 2 differs from what was sent
                h.requested.get() shouldBe 1
            } finally {
                h.cleanup()
            }
        }

        "a caret outside any block keeps the shown block and flags the header" {
            val h = Harness()
            try {
                h.open()
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.controller.onBuffer(noteText, 2)
                val header = h.controller.header.value!!
                header.cursorOutside shouldBe true
                header.index shouldBe 0
                header.count shouldBe 2
                h.tasks.size shouldBe 0
            } finally {
                h.cleanup()
            }
        }

        "a change outside the shown block does not run; a change inside it, a missing file and another block do" {
            val h = Harness()
            try {
                h.open()
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.controller.runBlock(md, 1) {}
                Files.writeString(md, noteText.replace("# Note", "# Changed prose"))
                h.controller.runFilter(WatchSignal.CHANGED) shouldBe false
                Files.writeString(md, noteText.replace("shape(a)", "shape(a2)"))
                h.controller.runFilter(WatchSignal.CHANGED) shouldBe true
                h.controller.runFilter(WatchSignal.MISSING) shouldBe true
            } finally {
                h.cleanup()
            }
        }

        "an index above the number of blocks on disk waits for a save" {
            val h = Harness()
            try {
                h.open("n.md" to "```kstep\nshape(a)\n```\n")
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                val buffer = "```kstep\nshape(a)\n```\n\n```kstep\nshape(c)\n```\n" // unsaved second block
                h.controller.onBuffer(buffer, buffer.indexOf("shape(c)"))
                val result = h.controller.runBlock(md, 1) {}
                result.shouldBeInstanceOf<RunResult.Skipped>().hint shouldBe "Save to preview this block"
                h.controller.header.value!!
                    .hint shouldBe BlockHint.SaveToPreview
                h.ran.size shouldBe 0
            } finally {
                h.cleanup()
            }
        }

        "the header carries the block name and the name problems, and the preview still runs" {
            val h = Harness()
            try {
                val text =
                    "```kstep {name=bracket, type=x}\nshape(a)\n```\n\n" +
                        "```kstep {name=Bracket}\nshape(b)\n```\n\n" +
                        "```kstep {name=../x}\nshape(c)\n```\n\n" +
                        "```kstep {name=\n```\n"
                h.open("n.md" to text)
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.controller.onBuffer(text, text.indexOf("shape(a)"))
                h.controller.header.value!!
                    .name shouldBe "bracket"
                h.controller.header.value!!
                    .nameHint shouldBe BlockHint.UnknownAttribute("type")
                h.controller.onBuffer(text, text.indexOf("shape(b)"))
                h.controller.header.value!!
                    .name shouldBe null
                h.controller.header.value!!
                    .nameHint shouldBe BlockHint.DuplicateName("Bracket", 1)
                h.controller.runBlock(md, 1) {}.shouldBeInstanceOf<RunResult.Success>()
                h.controller.onBuffer(text, text.indexOf("shape(c)"))
                h.controller.header.value!!
                    .nameHint shouldBe BlockHint.InvalidName(9)
                h.controller.header.value!!
                    .name shouldBe null
            } finally {
                h.cleanup()
            }
        }

        "a run keeps the caret-outside flag of the header" {
            val h = Harness()
            try {
                h.open()
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.controller.onBuffer(noteText, 2) // in the prose above the first block
                h.controller.runBlock(md, 1) {}
                h.controller.header.value!!
                    .cursorOutside shouldBe true
            } finally {
                h.cleanup()
            }
        }

        "an unsaved block above the active one is not mistaken for the disk block of the same index" {
            val h = Harness()
            try {
                h.open("n.md" to "```kstep\nshape(a)\n```\n\n```kstep\nshape(b)\n```\n")
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.controller.runBlock(md, 1) {}
                val buffer = "```kstep\nshape(n)\n```\n\n```kstep\nshape(a)\n```\n\n```kstep\nshape(b)\n```\n"
                h.controller.onBuffer(buffer, buffer.indexOf("shape(a)")) // buffer block 2, disk block 2 is b
                h.controller.header.value!!
                    .hint shouldBe BlockHint.SaveToPreview
                h.controller.onBuffer(buffer, buffer.indexOf("shape(b)")) // buffer 3 vs disk 2 (b): still unsaved
                h.controller.header.value!!
                    .hint shouldBe BlockHint.SaveToPreview
            } finally {
                h.cleanup()
            }
        }

        "a note without blocks and an empty block do not run" {
            val h = Harness()
            try {
                h.open("a.md" to "just text\n", "b.md" to "```kstep\n```\n")
                h.controller.onFileOpened(h.root.resolve("a.md"))
                h.controller
                    .runBlock(h.root.resolve("a.md"), 1) {}
                    .shouldBeInstanceOf<RunResult.Skipped>()
                    .hint shouldBe "No ```kstep blocks in this note."
                h.controller.onFileOpened(h.root.resolve("b.md"))
                h.controller
                    .runBlock(h.root.resolve("b.md"), 2) {}
                    .shouldBeInstanceOf<RunResult.Skipped>()
                    .hint shouldBe "Empty block"
                h.ran.size shouldBe 0
            } finally {
                h.cleanup()
            }
        }

        "an unclosed block is KSTEP-D-028 with its note line; a too large block is KSTEP-D-027" {
            val h = Harness()
            try {
                h.open(
                    "u.md" to "a\nb\n```kstep\nshape(a)\n",
                    "big.md" to "```kstep\n${("x".repeat(100) + "\n").repeat(2_700)}```\n",
                )
                h.controller.onFileOpened(h.root.resolve("u.md"))
                val u = h.controller.runBlock(h.root.resolve("u.md"), 1) {}.shouldBeInstanceOf<RunResult.Failure>()
                u.errors.single().code shouldBe DesktopErrorCodes.BLOCK_NOT_CLOSED
                u.errors.single().line shouldBe 3
                h.controller.onFileOpened(h.root.resolve("big.md"))
                val big = h.controller.runBlock(h.root.resolve("big.md"), 2) {}.shouldBeInstanceOf<RunResult.Failure>()
                big.errors.single().code shouldBe DesktopErrorCodes.WORKSPACE_LIMIT
                h.ran.size shouldBe 0
            } finally {
                h.cleanup()
            }
        }

        "an error of line 3 in a block starting at note line 14 is shown on line 16 without the temp path" {
            val h = Harness()
            try {
                val lead = (1..12).joinToString("") { "line $it\n" } // lines 1-12
                h.open("n.md" to lead + "```kstep\nshape(a)\nshape(b)\nshape(c)\nshape(d)\n```\n")
                val md = h.root.resolve("n.md")
                h.controller.onFileOpened(md)
                h.reply = { file, _ ->
                    RunResult.Failure(
                        listOf(
                            DesktopError("KSTEP-S-001", 3, 5, "$file:3:5 unresolved reference"),
                            DesktopError("KSTEP-S-002", 99, null, "out of range ${file.fileName}"),
                        ),
                    )
                }
                val failure = h.controller.runBlock(md, 5) {}.shouldBeInstanceOf<RunResult.Failure>()
                failure.errors[0].line shouldBe 16
                failure.errors[0].column shouldBe 5
                failure.errors[0].message shouldBe "block 1:3:5 unresolved reference"
                failure.errors[1].line shouldBe null
                failure.errors[1].message shouldBe "out of range block 1"
            } finally {
                h.cleanup()
            }
        }

        "a path outside the workspace is KSTEP-D-026" {
            val h = Harness()
            val outside = TestSupport.newDir()
            try {
                h.open()
                val other = outside.resolve("o.md").also { Files.writeString(it, noteText) }
                val failure = h.controller.runBlock(other, 1) {}.shouldBeInstanceOf<RunResult.Failure>()
                failure.errors.single().code shouldBe DesktopErrorCodes.WORKSPACE_PATH_REJECTED
                h.controller.resolveForOpen(other).shouldBeInstanceOf<PathCheck.Rejected>()
                h.controller.error.value!!
                    .code shouldBe DesktopErrorCodes.WORKSPACE_PATH_REJECTED
                h.controller.isWorkspaceFile(other) shouldBe false
                h.controller.guardSave(other) shouldBe null // not a workspace file: not ours to judge
            } finally {
                h.cleanup()
                outside.toFile().deleteRecursively()
            }
        }

        "the save guard rejects a note that became a link and passes a normal one" {
            val h = Harness()
            val outside = TestSupport.newDir()
            try {
                h.open()
                val md = h.root.resolve("n.md")
                h.controller.guardSave(md) shouldBe null
                if (posix) {
                    val target = outside.resolve("t.md").also { Files.writeString(it, "x") }
                    val link = h.root.resolve("l.md")
                    Files.createSymbolicLink(link, target)
                    h.controller.guardSave(link) shouldBe SaveFailure.PATH_REJECTED
                    Files.readString(target) shouldBe "x"
                }
            } finally {
                h.cleanup()
                outside.toFile().deleteRecursively()
            }
        }

        "next and previous block land on the first content line" {
            val h = Harness()
            try {
                h.open()
                val c = h.controller
                c.neighbourCaret(noteText, 2, forward = true) shouldBe noteText.indexOf("shape(a)")
                c.neighbourCaret(noteText, noteText.indexOf("shape(a)"), forward = true) shouldBe
                    noteText.indexOf("shape(b)")
                c.neighbourCaret(noteText, noteText.indexOf("shape(b)"), forward = true) shouldBe null
                c.neighbourCaret(noteText, noteText.indexOf("shape(b)"), forward = false) shouldBe
                    noteText.indexOf("shape(a)")
                c.neighbourCaret(noteText, noteText.indexOf("shape(a)"), forward = false) shouldBe null
            } finally {
                h.cleanup()
            }
        }

        "closing the workspace clears the state, closing the controller removes the session directory" {
            val h = Harness()
            h.open()
            h.controller.closeWorkspace()
            h.controller.workspace.value shouldBe WorkspaceState.None
            h.controller.header.value shouldBe null
            h.controller.isWorkspaceFile(h.root.resolve("n.md")) shouldBe false
            h.controller.close()
            Files.exists(h.session) shouldBe false
            h.root.toFile().deleteRecursively()
        }

        "a folder without notes keeps the previous state and reports KSTEP-D-025".config(timeout = 60.seconds) {
            val h = Harness()
            val empty = TestSupport.newDir()
            try {
                h.controller.openFolder(empty)
                TestSupport.await(30_000) { h.controller.error.value != null } shouldBe true
                h.controller.error.value!!
                    .code shouldBe DesktopErrorCodes.WORKSPACE_UNAVAILABLE
                h.controller.workspace.value shouldBe WorkspaceState.None
            } finally {
                h.cleanup()
                empty.toFile().deleteRecursively()
            }
        }
    })
