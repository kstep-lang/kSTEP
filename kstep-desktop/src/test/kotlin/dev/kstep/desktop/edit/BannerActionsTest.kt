package dev.kstep.desktop.edit

import dev.kstep.desktop.draft.Draft
import dev.kstep.desktop.draft.OfferKind
import dev.kstep.desktop.draft.RecoveryOffer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** The keyboard logic of the banner buttons (ADR-0086). */
class BannerActionsTest :
    StringSpec({
        val path = Path.of("/tmp/a.kstep.kts").toAbsolutePath()

        fun editing(
            conflict: Conflict? = null,
            deleted: Boolean = false,
        ) = EditorState.Editing(
            path,
            "a",
            ByteArray(32),
            "a",
            LineEnding.LF,
            false,
            false,
            conflict = conflict,
            deletedOnDisk = deleted,
        )

        fun view(
            state: EditorState,
            offer: RecoveryOffer? = null,
            elsewhere: Draft? = null,
            notice: String? = null,
        ) = EditorView(state, 0, 0, 0, draftOffer = offer, draftElsewhere = elsewhere, draftNotice = notice)

        fun offer(kind: OfferKind) = RecoveryOffer(path, kind, 1, "b")

        val reloadable =
            Conflict(ConflictKind.CHANGED, EditorText.Loaded.Ok("b", false, LineEnding.LF, false, ByteArray(32)))

        "the buttons come in drawing order" {
            BannerActions.slots(view(editing(), offer = offer(OfferKind.CHANGED))).map { it.button } shouldBe
                listOf(BannerButton.RESTORE, BannerButton.DISCARD)
            BannerActions.slots(view(editing(reloadable))).map { it.button } shouldBe
                listOf(BannerButton.RELOAD, BannerButton.KEEP_MINE)
            BannerActions.slots(view(EditorState.NoFile, elsewhere = Draft(path, 1, 1, null, 1, 1, ""))).map {
                it.button
            } shouldBe listOf(BannerButton.OPEN)
            BannerActions.slots(view(editing(), notice = "KSTEP-D-042")).map { it.button } shouldBe
                listOf(BannerButton.OK)
        }

        "banners without buttons have no actions" {
            BannerActions.slots(view(editing())) shouldBe emptyList()
            BannerActions.hasActions(view(editing())) shouldBe false
            BannerActions.hasActions(view(EditorState.NoFile)) shouldBe false
            BannerActions.hasActions(view(editing(deleted = true))) shouldBe false
        }

        "Restore is disabled for a draft that cannot be edited, the first focus goes to Discard" {
            val slots = BannerActions.slots(view(editing(), offer = offer(OfferKind.NOT_EDITABLE)))
            slots.map { it.enabled } shouldBe listOf(false, true)
            BannerActions.firstFocus(slots) shouldBe 1
            BannerActions.hasActions(view(editing(), offer = offer(OfferKind.NOT_EDITABLE))) shouldBe true
        }

        "Reload is disabled when the file cannot be reloaded" {
            val slots = BannerActions.slots(view(editing(Conflict(ConflictKind.DELETED, null))))
            slots.map { it.button to it.enabled } shouldBe
                listOf(BannerButton.RELOAD to false, BannerButton.KEEP_MINE to true)
        }

        "Tab and the arrows cycle through the enabled buttons" {
            val slots = listOf(BannerSlot(BannerButton.RESTORE, true), BannerSlot(BannerButton.DISCARD, true))
            BannerActions.onKey(BannerKey.TAB, 0, slots) shouldBe BannerKeyResult.Move(1)
            BannerActions.onKey(BannerKey.TAB, 1, slots) shouldBe BannerKeyResult.Move(0)
            BannerActions.onKey(BannerKey.RIGHT, 0, slots) shouldBe BannerKeyResult.Move(1)
            BannerActions.onKey(BannerKey.SHIFT_TAB, 0, slots) shouldBe BannerKeyResult.Move(1)
            BannerActions.onKey(BannerKey.LEFT, 1, slots) shouldBe BannerKeyResult.Move(0)
        }

        "disabled buttons are skipped" {
            val slots =
                listOf(
                    BannerSlot(BannerButton.RESTORE, false),
                    BannerSlot(BannerButton.DISCARD, true),
                    BannerSlot(BannerButton.OPEN, false),
                )
            BannerActions.onKey(BannerKey.TAB, 1, slots) shouldBe BannerKeyResult.Move(1)
            BannerActions.onKey(BannerKey.SHIFT_TAB, 1, slots) shouldBe BannerKeyResult.Move(1)
            BannerActions.onKey(BannerKey.TAB, 1, listOf(BannerSlot(BannerButton.OK, false))) shouldBe
                BannerKeyResult.Ignore
        }

        "Enter and Space activate, Escape leaves, other keys are ignored" {
            val slots = listOf(BannerSlot(BannerButton.RELOAD, false), BannerSlot(BannerButton.KEEP_MINE, true))
            BannerActions.onKey(BannerKey.ENTER, 1, slots) shouldBe BannerKeyResult.Activate(BannerButton.KEEP_MINE)
            BannerActions.onKey(BannerKey.SPACE, 1, slots) shouldBe BannerKeyResult.Activate(BannerButton.KEEP_MINE)
            BannerActions.onKey(BannerKey.ENTER, 0, slots) shouldBe BannerKeyResult.Ignore
            BannerActions.onKey(BannerKey.ESCAPE, 1, slots) shouldBe BannerKeyResult.Leave
            BannerActions.onKey(BannerKey.OTHER, 1, slots) shouldBe BannerKeyResult.Ignore
        }

        "the slots agree with the banner kind" {
            val v = view(editing(reloadable), offer = offer(OfferKind.UNCHANGED))
            bannerKind(v) shouldBe BannerKind.DRAFT_OFFER
            BannerActions.slots(v).map { it.button } shouldBe listOf(BannerButton.RESTORE, BannerButton.DISCARD)
        }
    })
