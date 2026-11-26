package dev.kstep.desktop.ui

import dev.kstep.desktop.draft.Draft
import dev.kstep.desktop.draft.OfferKind
import dev.kstep.desktop.draft.RecoveryOffer
import dev.kstep.desktop.edit.Conflict
import dev.kstep.desktop.edit.ConflictKind
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.LineEnding
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class EditorBannerKindTest :
    StringSpec({
        val path = Path.of("/tmp/a.kstep.kts").toAbsolutePath()

        fun editing(conflict: Conflict? = null) =
            EditorState.Editing(path, "a", ByteArray(32), "a", LineEnding.LF, false, false, conflict = conflict)

        val offer = RecoveryOffer(path, OfferKind.UNCHANGED, 1, "b")
        val elsewhere = Draft(path, 1, 1, null, 1, 1, "")

        fun view(
            state: EditorState,
            offer: RecoveryOffer? = null,
            elsewhere: Draft? = null,
            notice: String? = null,
        ) = EditorView(state, 0, 0, 0, draftOffer = offer, draftElsewhere = elsewhere, draftNotice = notice)

        "nothing to say" {
            bannerKind(view(editing())) shouldBe null
            bannerKind(view(EditorState.NoFile)) shouldBe null
        }

        "the draft offer beats a conflict" {
            val conflict = Conflict(ConflictKind.DELETED, null)
            bannerKind(view(editing(conflict))) shouldBe BannerKind.CONFLICT
            bannerKind(view(editing(conflict), offer = offer)) shouldBe BannerKind.DRAFT_OFFER
        }

        "a draft of another file shows while no file is open, and only then" {
            bannerKind(view(EditorState.NoFile, elsewhere = elsewhere)) shouldBe BannerKind.DRAFT_ELSEWHERE
            bannerKind(view(editing(), elsewhere = elsewhere)) shouldBe null
        }

        "the notice has the lowest priority" {
            bannerKind(view(editing(), notice = "KSTEP-D-042")) shouldBe BannerKind.DRAFT_NOTICE
            bannerKind(view(editing(Conflict(ConflictKind.DELETED, null)), notice = "KSTEP-D-042")) shouldBe
                BannerKind.CONFLICT
            bannerKind(view(editing(), offer = offer, notice = "KSTEP-D-042")) shouldBe BannerKind.DRAFT_OFFER
        }
    })
