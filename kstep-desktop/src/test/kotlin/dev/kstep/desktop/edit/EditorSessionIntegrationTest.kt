package dev.kstep.desktop.edit

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.watch.FileChangeWatcher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The editor with a real watcher and a fake runner (no process, no OCCT): one save is one run and no false conflict. */
class EditorSessionIntegrationTest :
    StringSpec({
        class Harness {
            val runs = AtomicInteger()
            val controller =
                LiveRunController(
                    runner = { _, gen, _ ->
                        runs.incrementAndGet()
                        RunResult.Success(DesktopModel(null, 0, gen, Instant.EPOCH, 1, emptyList()))
                    },
                    watcherFactory = {
                        file,
                        onEvent,
                        ->
                        FileChangeWatcher(file, pollMillis = 40, quietMillis = 120, onEvent = onEvent)
                    },
                )
            val session = EditorSession()

            init {
                controller.onDiskSignal = session::onDiskSignal
            }

            fun open(path: java.nio.file.Path) {
                session.open(path)
                controller.open(path)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                TestSupport.await(30_000) { runs.get() >= 1 } shouldBe true
                Thread.sleep(300) // the watcher's first poll only records the initial state
            }

            fun editing() =
                session.view.value.state
                    .shouldBeInstanceOf<EditorState.Editing>()

            fun close() {
                controller.close()
                session.close()
            }
        }

        "a save produces exactly one run and the editor ignores its own change".config(timeout = 90.seconds) {
            val h = Harness()
            try {
                val file = TestSupport.script(TestSupport.newDir(), "s", "val a = 1\n")
                h.open(file)
                h.session.onTextChanged("val a = 2\n", 9)
                h.session.save()
                TestSupport.await(30_000) {
                    h.session.view.value.dirty
                        .not() &&
                        h.editing().saving == null
                } shouldBe
                    true
                Files.readString(file) shouldBe "val a = 2\n"
                TestSupport.await(30_000) { h.runs.get() >= 2 } shouldBe true
                Thread.sleep(1_000) // a second, spurious run would show up here
                h.runs.get() shouldBe 2
                h.editing().conflict shouldBe null
            } finally {
                h.close()
            }
        }

        "an external write while clean reloads the buffer".config(timeout = 90.seconds) {
            val h = Harness()
            try {
                val file = TestSupport.script(TestSupport.newDir(), "s", "one\n")
                h.open(file)
                Thread.sleep(300)
                Files.writeString(file, "two, longer\n")
                TestSupport.await(30_000) { h.editing().text == "two, longer\n" } shouldBe true
                h.editing().dirty shouldBe false
            } finally {
                h.close()
            }
        }

        "an external write while dirty is a conflict, Keep mine then Save overwrites".config(timeout = 90.seconds) {
            val h = Harness()
            try {
                val file = TestSupport.script(TestSupport.newDir(), "s", "one\n")
                h.open(file)
                Thread.sleep(300)
                h.session.onTextChanged("mine\n", 5)
                Files.writeString(file, "theirs, longer\n")
                TestSupport.await(30_000) { h.editing().conflict != null } shouldBe true
                h.session.save() // pulses, does not write
                Files.readString(file) shouldBe "theirs, longer\n"
                h.session.keepMine()
                h.session.save()
                TestSupport.await(30_000) { !h.session.view.value.dirty } shouldBe true
                Files.readString(file) shouldBe "mine\n"
            } finally {
                h.close()
            }
        }

        "undo and redo restore the text through the session".config(timeout = 90.seconds) {
            val h = Harness()
            try {
                val file = TestSupport.script(TestSupport.newDir(), "s", "one\n")
                h.open(file)
                h.session.onTextChanged("one!\n", 4)
                h.session.undo()
                h.editing().text shouldBe "one\n"
                h.editing().dirty shouldBe false
                h.session.redo()
                h.editing().text shouldBe "one!\n"
            } finally {
                h.close()
            }
        }

        "saveAndWait reports success and failure".config(timeout = 90.seconds) {
            val file = TestSupport.script(TestSupport.newDir(), "s", "one\n")
            val failing = EditorSession(saver = { _, _, _, _ -> AtomicSaver.Result.Failed(SaveFailure.DISK_FULL) })
            try {
                failing.open(file)
                TestSupport.await(30_000) { failing.view.value.state is EditorState.Editing } shouldBe true
                failing.onTextChanged("two\n", 4)
                failing.saveAndWait() shouldBe false
                failing.hasUnsavedChanges() shouldBe true
            } finally {
                failing.close()
            }
            val real = EditorSession()
            try {
                real.open(file)
                TestSupport.await(30_000) { real.view.value.state is EditorState.Editing } shouldBe true
                real.onTextChanged("two\n", 4)
                real.saveAndWait() shouldBe true
                Files.readString(file) shouldBe "two\n"
            } finally {
                real.close()
            }
        }

        "a saver with a path guard refuses a link and leaves the original untouched".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            try {
                val target = Files.writeString(dir.resolve("t.md"), "original")
                val real = AtomicSaver()
                val session =
                    EditorSession(
                        saver = { p, b, hash, force ->
                            if (p.fileName.toString() == "t.md") {
                                AtomicSaver.Result.Failed(SaveFailure.PATH_REJECTED)
                            } else {
                                real.save(p, b, hash, force)
                            }
                        },
                    )
                try {
                    session.open(target)
                    TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                    session.onTextChanged("changed", 7)
                    session.save()
                    TestSupport.await(30_000) {
                        (session.view.value.state as? EditorState.Editing)?.saveError == SaveFailure.PATH_REJECTED
                    } shouldBe true
                    Files.readString(target) shouldBe "original"
                } finally {
                    session.close()
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
