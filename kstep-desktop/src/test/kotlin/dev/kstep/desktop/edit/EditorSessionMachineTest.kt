package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

class EditorSessionMachineTest :
    StringSpec({
        val machine = EditorSessionMachine()
        val path = Path.of("/tmp/a.kstep.kts")

        fun loaded(text: String) = EditorText.decode(text.toByteArray()).shouldBeInstanceOf<EditorText.Loaded.Ok>()

        fun hashOf(text: String) = EditorText.sha256(text.toByteArray())

        fun open(text: String = "base"): EditorState.Editing =
            machine.reduce(EditorState.NoFile, EditorEvent.Opened(path, loaded(text))).state.shouldBeInstanceOf()

        fun EditorState.editing() = this.shouldBeInstanceOf<EditorState.Editing>()

        fun step(
            s: EditorState,
            e: EditorEvent,
        ) = machine.reduce(s, e)

        fun dirty(text: String = "mine") = step(open(), EditorEvent.TextEdited(text)).state.editing()

        "opening a readable file gives a clean buffer" {
            val s = open("abc")
            s.text shouldBe "abc"
            s.dirty shouldBe false
        }

        "opening a refused file gives preview-only" {
            val t = step(EditorState.NoFile, EditorEvent.Opened(path, EditorText.decode(ByteArray(2_000_000))))
            t.state.shouldBeInstanceOf<EditorState.Unavailable>()
        }

        "1: a disk change equal to the base or to the in-flight save is ignored" {
            val d = dirty("mine")
            val saving = step(d, EditorEvent.SaveRequested).state.editing()
            // between the move and the completion the signal carries the in-flight hash
            val t = step(saving, EditorEvent.DiskChanged(loaded("mine")))
            t.state.editing().conflict shouldBe null
            val t2 = step(d, EditorEvent.DiskChanged(loaded("base")))
            t2.state.editing().conflict shouldBe null
            t2.effects shouldBe emptyList()
        }

        "2: a clean buffer reloads from disk as an undoable step" {
            val t = step(open("one"), EditorEvent.DiskChanged(loaded("two")))
            t.state.editing().text shouldBe "two"
            t.state.editing().dirty shouldBe false
            t.effects
                .single()
                .shouldBeInstanceOf<EditorEffect.ReloadApplied>()
                .previousText shouldBe "one"
        }

        "3: a clean buffer whose file turns oversized becomes preview-only" {
            val t = step(open(), EditorEvent.DiskChanged(EditorText.decode(ByteArray(EditorText.MAX_BYTES + 5))))
            t.state.shouldBeInstanceOf<EditorState.Unavailable>()
        }

        "4: a dirty buffer gets a conflict, a second change replaces the disk version" {
            val c1 = step(dirty(), EditorEvent.DiskChanged(loaded("disk1"))).state.editing()
            c1.conflict!!.kind shouldBe ConflictKind.CHANGED
            c1.text shouldBe "mine"
            val c2 = step(c1, EditorEvent.DiskChanged(loaded("disk2"))).state.editing()
            (c2.conflict!!.disk as EditorText.Loaded.Ok).text shouldBe "disk2"
        }

        "5: a deleted file: dirty gives a conflict, clean marks the buffer dirty" {
            step(dirty(), EditorEvent.DiskMissing)
                .state
                .editing()
                .conflict!!
                .kind shouldBe ConflictKind.DELETED
            val clean = step(open(), EditorEvent.DiskMissing).state.editing()
            clean.conflict shouldBe null
            clean.deletedOnDisk shouldBe true
            clean.dirty shouldBe true
            clean.baseHash shouldBe null
        }

        "6: save writes with the base hash; in a conflict it only pulses the banner" {
            val t = step(dirty("mine"), EditorEvent.SaveRequested)
            val write = t.effects.single().shouldBeInstanceOf<EditorEffect.WriteFile>()
            write.text shouldBe "mine"
            write.expectedBaseHash!!.toList() shouldBe hashOf("base").toList()
            val conflicted = step(dirty(), EditorEvent.DiskChanged(loaded("disk"))).state
            val pulse = step(conflicted, EditorEvent.SaveRequested)
            pulse.effects.single() shouldBe EditorEffect.PulseBanner
        }

        "6: saving while saving asks for exactly one catch-up save" {
            val saving = step(dirty("a"), EditorEvent.SaveRequested).state.editing()
            val edited = step(saving, EditorEvent.TextEdited("ab")).state
            val again = step(edited, EditorEvent.SaveRequested)
            again.effects shouldBe emptyList()
            again.state.editing().pendingSave shouldBe true
            val twice = step(again.state, EditorEvent.SaveRequested)
            twice.effects shouldBe emptyList()
            val done = step(again.state, EditorEvent.SaveFinished(SaveOutcome.Saved(hashOf("a"), "a")))
            val catchUp = done.effects.single().shouldBeInstanceOf<EditorEffect.WriteFile>()
            catchUp.text shouldBe "ab"
            catchUp.expectedBaseHash!!.toList() shouldBe hashOf("a").toList()
            done.state.editing().pendingSave shouldBe false
        }

        "6: save in preview-only or without a file is ignored" {
            step(EditorState.NoFile, EditorEvent.SaveRequested).effects shouldBe emptyList()
            step(EditorState.Unavailable(path, "X", "r"), EditorEvent.SaveRequested).effects shouldBe emptyList()
        }

        "7: a finished save makes the saved text the base; edits during the save survive" {
            val saving = step(dirty("a"), EditorEvent.SaveRequested).state.editing()
            val edited = step(saving, EditorEvent.TextEdited("ab")).state
            val done = step(edited, EditorEvent.SaveFinished(SaveOutcome.Saved(hashOf("a"), "a"))).state.editing()
            done.base shouldBe "a"
            done.text shouldBe "ab"
            done.dirty shouldBe true
            done.saving shouldBe null
            done.baseHash!!.toList() shouldBe hashOf("a").toList()
        }

        "8: a failed save keeps the buffer and shows the error" {
            val saving = step(dirty("a"), EditorEvent.SaveRequested).state
            val failed =
                step(
                    saving,
                    EditorEvent.SaveFinished(SaveOutcome.Failed(SaveFailure.DISK_FULL)),
                ).state.editing()
            failed.text shouldBe "a"
            failed.dirty shouldBe true
            failed.saveError shouldBe SaveFailure.DISK_FULL
            val ok = step(failed, EditorEvent.SaveFinished(SaveOutcome.Saved(hashOf("a"), "a"))).state.editing()
            ok.saveError shouldBe null
        }

        "8: a save that finds a changed disk becomes a conflict without writing" {
            val saving = step(dirty("a"), EditorEvent.SaveRequested).state
            val t = step(saving, EditorEvent.SaveFinished(SaveOutcome.Conflicted(loaded("disk")))).state.editing()
            t.conflict!!.kind shouldBe ConflictKind.CHANGED
            t.text shouldBe "a"
        }

        "9: keep mine adopts the disk version as base without writing; the next save overwrites" {
            val c = step(dirty("mine"), EditorEvent.DiskChanged(loaded("disk"))).state
            val kept = step(c, EditorEvent.KeepMineChosen)
            kept.effects shouldBe emptyList()
            val s = kept.state.editing()
            s.conflict shouldBe null
            s.dirty shouldBe true
            s.baseHash!!.toList() shouldBe hashOf("disk").toList()
            val save = step(s, EditorEvent.SaveRequested).effects.single().shouldBeInstanceOf<EditorEffect.WriteFile>()
            save.expectedBaseHash!!.toList() shouldBe hashOf("disk").toList()
        }

        "9: keep mine after a deletion recreates the file on save" {
            val c = step(dirty("mine"), EditorEvent.DiskMissing).state
            val s = step(c, EditorEvent.KeepMineChosen).state.editing()
            s.deletedOnDisk shouldBe true
            val save = step(s, EditorEvent.SaveRequested).effects.single().shouldBeInstanceOf<EditorEffect.WriteFile>()
            save.expectedBaseHash shouldBe null
        }

        "10: reload takes the disk version as an undoable step; it is impossible for a deleted file" {
            val c = step(dirty("mine"), EditorEvent.DiskChanged(loaded("disk"))).state
            val t = step(c, EditorEvent.ReloadChosen)
            t.state.editing().text shouldBe "disk"
            t.state.editing().dirty shouldBe false
            t.effects
                .single()
                .shouldBeInstanceOf<EditorEffect.ReloadApplied>()
                .previousText shouldBe "mine"
            val deleted = step(dirty("mine"), EditorEvent.DiskMissing).state
            step(deleted, EditorEvent.ReloadChosen).state.editing().text shouldBe "mine"
            deleted.editing().conflict!!.reloadBlockedReason shouldBe "the file no longer exists"
        }

        "11: closing asks only when there are unsaved changes" {
            step(open(), EditorEvent.CloseRequested).effects.single() shouldBe EditorEffect.ContinueClose
            step(dirty(), EditorEvent.CloseRequested).effects.single() shouldBe EditorEffect.AskCloseDecision
        }

        "12: a disk version that turns binary while dirty is a conflict that cannot be reloaded" {
            val c = step(dirty(), EditorEvent.DiskChanged(EditorText.decode("a\u0000".toByteArray()))).state.editing()
            c.conflict.shouldBeInstanceOf<Conflict>().reloadable shouldBe false
            c.conflict!!.reloadBlockedReason shouldBe "the file contains a NUL byte"
            step(c, EditorEvent.ReloadChosen).state.editing().text shouldBe "mine"
            val kept = step(c, EditorEvent.KeepMineChosen).state.editing()
            kept.conflict shouldBe null
        }

        "13: undoing back to the base text makes the buffer clean" {
            val s = dirty("mine")
            step(s, EditorEvent.TextEdited("base")).state.editing().dirty shouldBe false
        }

        "a disk change back to the base version clears an open conflict" {
            val c = step(dirty(), EditorEvent.DiskChanged(loaded("disk"))).state
            step(c, EditorEvent.DiskChanged(loaded("base"))).state.editing().conflict shouldBe null
        }
    })
