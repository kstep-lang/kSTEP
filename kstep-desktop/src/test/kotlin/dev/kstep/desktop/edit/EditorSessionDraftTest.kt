package dev.kstep.desktop.edit

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.draft.DraftKey
import dev.kstep.desktop.draft.DraftStore
import dev.kstep.desktop.draft.OfferKind
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.watch.WatchSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/** The crash draft in the editor session (ADR-0084): real store in a temp folder, fake clock, no real timer. */
class EditorSessionDraftTest :
    StringSpec({
        class Harness(
            saver: ((Path, ByteArray, ByteArray?, Boolean) -> AtomicSaver.Result)? = null,
        ) {
            val root: Path = TestSupport.newDir()
            val dir: Path = root.resolve("config").resolve("drafts")
            var now = 1_700_000_000_000L
            val store = DraftStore(dir, { now })
            val timers = mutableListOf<Pair<Long, Runnable>>()
            val atomic = AtomicSaver()
            val session =
                EditorSession(
                    saver = saver ?: { p, b, h, f -> atomic.save(p, b, h, f) },
                    clock = { now },
                    drafts = store,
                    draftTimer =
                        DraftTimer { delay, task ->
                            timers += delay to task
                            AutoCloseable { }
                        },
                )

            fun script(text: String = "val a = 1\n"): Path =
                TestSupport.script(Files.createDirectories(root.resolve("work")), "s", text)

            fun key(file: Path) = DraftKey.normalize(file)

            fun draftFile(file: Path): Path = dir.resolve(DraftKey.fileName(key(file)))

            fun draftExists(file: Path) = Files.exists(draftFile(file))

            fun open(file: Path) {
                session.open(file)
                session.awaitIdle()
            }

            fun editing() =
                session.view.value.state
                    .shouldBeInstanceOf<EditorState.Editing>()

            fun type(text: String) = session.onTextChanged(text, text.length)

            fun close() {
                session.close()
                root.toFile().deleteRecursively()
            }
        }

        fun withHarness(
            saver: ((Path, ByteArray, ByteArray?, Boolean) -> AtomicSaver.Result)? = null,
            block: (Harness) -> Unit,
        ) {
            val h = Harness(saver)
            try {
                block(h)
            } finally {
                h.close()
            }
        }

        /** Writes a draft of [file] with [body] as an earlier session would have left it. */
        fun leaveDraft(
            h: Harness,
            file: Path,
            body: String,
        ) {
            h.open(file)
            h.type(body)
            h.session.writeDraftNow()
            h.draftExists(file) shouldBe true
        }

        /** A second session on the same folders: "the next start after the crash". */
        fun restart(h: Harness): EditorSession =
            EditorSession(
                clock = { h.now },
                drafts = DraftStore(h.dir, { h.now }),
                draftTimer = DraftTimer { _, _ -> AutoCloseable { } },
            )

        "typing schedules a draft write, which lands as a file" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.timers.size shouldBe 1
                h.timers[0].first shouldBe 2_000 // 2 s after the change on the fake clock
                h.draftExists(file) shouldBe false
                h.session.writeDraftNow()
                h.draftExists(file) shouldBe true
                Files.readString(file) shouldBe "val a = 1\n" // the file itself is untouched
            }
        }

        "a clean buffer gets no draft" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.session.writeDraftNow()
                h.draftExists(file) shouldBe false
            }
        }

        "after a crash an unchanged file offers the draft, and Restore is one undo step" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    val offer = next.view.value.draftOffer!!
                    offer.kind shouldBe OfferKind.UNCHANGED
                    next.view.value.dirty shouldBe false
                    next.restoreDraft()
                    next.view.value.draftOffer shouldBe null
                    next.view.value.text shouldBe "val a = 2\n"
                    next.view.value.dirty shouldBe true
                    next.undo()
                    next.view.value.text shouldBe "val a = 1\n"
                    next.redo()
                    next.view.value.text shouldBe "val a = 2\n"
                } finally {
                    next.close()
                }
            }
        }

        "an open offer blocks Save, and typing writes no draft" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                val before = Files.readAllBytes(h.draftFile(file))
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    val pulse = next.view.value.pulse
                    next.onTextChanged("val a = 3\n", 10)
                    next.save()
                    next.view.value.pulse shouldBe pulse + 1
                    next.saveAndWait(5_000) shouldBe false
                    Files.readString(file) shouldBe "val a = 1\n"
                    next.writeDraftNow()
                    Files.readAllBytes(h.draftFile(file)).contentEquals(before) shouldBe true
                } finally {
                    next.close()
                }
            }
        }

        "Discard deletes the draft at once and keeps the buffer" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    next.discardDraft()
                    next.awaitIdle()
                    next.view.value.draftOffer shouldBe null
                    next.view.value.text shouldBe "val a = 1\n"
                    h.draftExists(file) shouldBe false
                } finally {
                    next.close()
                }
            }
        }

        "closing with an unanswered offer keeps the draft byte for byte" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                val before = Files.readAllBytes(h.draftFile(file))
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    next.finishForQuit()
                    Files.readAllBytes(h.draftFile(file)).contentEquals(before) shouldBe true
                } finally {
                    next.close()
                }
                val third = restart(h)
                try {
                    third.open(file)
                    third.awaitIdle()
                    third.clear()
                    third.awaitIdle()
                    Files.readAllBytes(h.draftFile(file)).contentEquals(before) shouldBe true
                } finally {
                    third.close()
                }
            }
        }

        "a changed file says CHANGED" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                Files.writeString(file, "val a = 99\n")
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    next.view.value.draftOffer!!
                        .kind shouldBe OfferKind.CHANGED
                } finally {
                    next.close()
                }
            }
        }

        "a draft equal to the file is deleted without a question" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                Files.writeString(file, "val a = 2\n")
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    next.view.value.draftOffer shouldBe null
                    h.draftExists(file) shouldBe false
                } finally {
                    next.close()
                }
            }
        }

        "a deleted file offers DELETED; Restore makes a buffer that Save writes anew and the draft goes" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                Files.delete(file)
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    next.view.value.draftOffer!!
                        .kind shouldBe OfferKind.DELETED
                    next.view.value.state
                        .shouldBeInstanceOf<EditorState.Unavailable>()
                    next.restoreDraft()
                    val state =
                        next.view.value.state
                            .shouldBeInstanceOf<EditorState.Editing>()
                    state.deletedOnDisk shouldBe true
                    next.view.value.text shouldBe "val a = 2\n"
                    next.save()
                    TestSupport.await(30_000) { Files.exists(file) && !next.view.value.dirty } shouldBe true
                    Files.readString(file) shouldBe "val a = 2\n"
                    next.awaitIdle()
                    h.draftExists(file) shouldBe false
                } finally {
                    next.close()
                }
            }
        }

        "a file that cannot be edited any more: Restore does nothing, Discard works" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                Files.write(file, ByteArray(10) { 0 })
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    next.view.value.draftOffer!!
                        .kind shouldBe OfferKind.NOT_EDITABLE
                    next.restoreDraft()
                    next.view.value.draftOffer!!
                        .kind shouldBe OfferKind.NOT_EDITABLE
                    next.view.value.state
                        .shouldBeInstanceOf<EditorState.Unavailable>()
                    h.draftExists(file) shouldBe true
                    next.discardDraft()
                    next.awaitIdle()
                    h.draftExists(file) shouldBe false
                } finally {
                    next.close()
                }
            }
        }

        "a successful save deletes the draft" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.session.writeDraftNow()
                h.draftExists(file) shouldBe true
                h.session.saveAndWait(30_000) shouldBe true
                h.session.awaitIdle()
                h.draftExists(file) shouldBe false
            }
        }

        "a failed save keeps the draft" {
            withHarness({ _, _, _, _ -> AtomicSaver.Result.Failed(SaveFailure.IO) }) { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.session.writeDraftNow()
                h.session.save()
                TestSupport.await(30_000) { h.editing().saveError != null } shouldBe true
                h.session.awaitIdle()
                h.draftExists(file) shouldBe true
            }
        }

        "a conflict keeps the draft" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.session.writeDraftNow()
                Files.writeString(file, "val a = 99\n")
                h.session.save()
                TestSupport.await(30_000) { h.editing().conflict != null } shouldBe true
                h.session.awaitIdle()
                h.draftExists(file) shouldBe true
            }
        }

        "Don't Save then another file or close deletes the draft; a clean close does too" {
            withHarness { h ->
                val a = h.script()
                val b = TestSupport.script(a.parent, "b", "val b = 1\n")
                h.open(a)
                h.type("val a = 2\n")
                h.session.writeDraftNow()
                h.open(b)
                h.draftExists(a) shouldBe false
                h.type("val b = 2\n")
                h.session.writeDraftNow()
                h.draftExists(b) shouldBe true
                h.session.clear()
                h.session.awaitIdle()
                h.draftExists(b) shouldBe false
                // a draft that stayed behind after "undo to clean" goes when the file is closed
                h.open(a)
                h.type("val a = 5\n")
                h.session.writeDraftNow()
                h.session.undo()
                h.session.view.value.dirty shouldBe false
                h.draftExists(a) shouldBe true
                h.session.clear()
                h.session.awaitIdle()
                h.draftExists(a) shouldBe false
            }
        }

        "undo back to clean keeps the draft and writes nothing more" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.session.writeDraftNow()
                val timersBefore = h.timers.size
                h.session.undo()
                h.session.view.value.dirty shouldBe false
                h.timers.size shouldBe timersBefore
                h.draftExists(file) shouldBe true
                h.session.writeDraftNow() // not dirty: nothing happens
                h.draftExists(file) shouldBe true
            }
        }

        "a write that is due right after Save finds a clean buffer and writes no draft" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.session.save() // the draft write was never run before the save
                h.session.awaitIdle()
                h.session.writeDraftNow() // the timer task that was still scheduled
                h.draftExists(file) shouldBe false
            }
        }

        "a buffer above 1 MiB writes no draft, keeps the old one and notes it once" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.session.writeDraftNow()
                val before = Files.readAllBytes(h.draftFile(file))
                h.type("a\n".repeat(600_000))
                h.session.writeDraftNow()
                h.session.writeDraftNow()
                Files.readAllBytes(h.draftFile(file)).contentEquals(before) shouldBe true
                h.session.view.value.draftNotice shouldBe DesktopErrorCodes.DRAFT_TOO_LARGE
                h.session.dismissNotice()
                h.session.writeDraftNow()
                h.session.view.value.draftNotice shouldBe null // once per session
            }
        }

        "flushForShutdown writes a dirty buffer, but not after finishForQuit" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.session.flushForShutdown()
                h.draftExists(file) shouldBe true
                Files.delete(h.draftFile(file))
                h.session.finishForQuit()
                h.session.flushForShutdown()
                h.draftExists(file) shouldBe false
            }
        }

        "flushForShutdown writes nothing while an offer is open" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                val before = Files.readAllBytes(h.draftFile(file))
                val next = restart(h)
                try {
                    next.open(file)
                    next.awaitIdle()
                    next.onTextChanged("val a = 7\n", 10)
                    next.flushForShutdown()
                    Files.readAllBytes(h.draftFile(file)).contentEquals(before) shouldBe true
                } finally {
                    next.close()
                }
            }
        }

        "flushForShutdown stays within its budget when the store blocks" {
            val dir = TestSupport.newDir()
            val blocking =
                object : DraftStore(dir.resolve("d"), { 1L }) {
                    override fun write(draft: dev.kstep.desktop.draft.Draft): Write {
                        Thread.sleep(60_000)
                        return Write.Written
                    }
                }
            val session = EditorSession(drafts = blocking, draftTimer = DraftTimer { _, _ -> AutoCloseable { } })
            try {
                val file = TestSupport.script(dir, "s", "val a = 1\n")
                session.open(file)
                session.awaitIdle()
                session.onTextChanged("val a = 2\n", 10)
                val started = System.nanoTime()
                session.flushForShutdown(budgetMs = 300)
                ((System.nanoTime() - started) / 1_000_000 < 5_000) shouldBe true
            } finally {
                session.close()
                dir.toFile().deleteRecursively()
            }
        }

        "a draft of a file that is not open shows up when no file is open, only for scripts" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")
                val note =
                    TestSupport
                        .script(
                            file.parent,
                            "n",
                            "x",
                        ).let { Files.move(it, it.resolveSibling("note.md")) }
                h.now += 1_000 // the note draft is the newest one
                val noteSession = restart(h)
                try {
                    noteSession.open(note)
                    noteSession.awaitIdle()
                    noteSession.onTextChanged("note text\n", 10)
                    noteSession.writeDraftNow()
                } finally {
                    noteSession.close()
                }
                val next = restart(h)
                try {
                    next.lookForDraftsElsewhere()
                    next.awaitIdle()
                    next.view.value.draftElsewhere!!
                        .path shouldBe h.key(file)
                    next.open(file)
                    next.awaitIdle()
                    next.view.value.draftElsewhere shouldBe null
                } finally {
                    next.close()
                }
            }
        }

        "typing while a save is in flight keeps a draft scheduled for the newer text" {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val atomicSaver = AtomicSaver()
            withHarness(
                saver = { p, b, hash, force ->
                    entered.countDown()
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    atomicSaver.save(p, b, hash, force)
                },
            ) { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.session.writeDraftNow()
                h.draftExists(file) shouldBe true
                h.session.save()
                entered.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
                h.type("val a = 2\nval b = 3\n") // typed while the save runs
                val timersBefore = h.timers.size
                release.countDown()
                h.session.awaitIdle()
                h.editing().dirty shouldBe true
                (h.timers.size > timersBefore) shouldBe true // a new draft write is pending
                h.session.writeDraftNow()
                val read = h.store.read(h.key(file))
                read.shouldBeInstanceOf<DraftStore.Read.Found>()
                read.draft.body shouldBe "val a = 2\nval b = 3\n"
            }
        }

        "a failed draft attempt does not make every later keystroke write immediately" {
            withHarness { h ->
                val file = h.script()
                h.open(file)
                h.type("val a = 2\n")
                h.type("a\n".repeat(600_000)) // above 1 MiB: TooLarge
                h.session.writeDraftNow()
                h.now += 25_000
                h.type("a\n".repeat(600_000) + "b\n")
                h.timers.last().first shouldBe 2_000
            }
        }

        "an open offer follows the file: changed on disk, deleted, or no longer editable" {
            withHarness { h ->
                val file = h.script()
                leaveDraft(h, file, "val a = 2\n")

                fun nextSession(block: (EditorSession) -> Unit) {
                    val next = restart(h)
                    try {
                        next.open(file)
                        next.awaitIdle()
                        next.view.value.draftOffer!!
                            .kind shouldBe OfferKind.UNCHANGED
                        block(next)
                    } finally {
                        next.close()
                    }
                }
                nextSession { next ->
                    Files.writeString(file, "val a = 9\n")
                    next.onDiskSignal(WatchSignal.CHANGED)
                    next.awaitIdle()
                    next.view.value.draftOffer!!
                        .kind shouldBe OfferKind.CHANGED
                }
                Files.writeString(file, "val a = 1\n")
                nextSession { next ->
                    Files.write(file, byteArrayOf(0x80.toByte(), 0x81.toByte(), 0xC0.toByte()))
                    next.onDiskSignal(WatchSignal.CHANGED)
                    next.awaitIdle()
                    next.view.value.draftOffer!!
                        .kind shouldBe OfferKind.NOT_EDITABLE
                }
                Files.writeString(file, "val a = 1\n")
                nextSession { next ->
                    Files.delete(file)
                    next.onDiskSignal(WatchSignal.MISSING)
                    next.view.value.draftOffer!!
                        .kind shouldBe OfferKind.DELETED
                }
            }
        }
    })
