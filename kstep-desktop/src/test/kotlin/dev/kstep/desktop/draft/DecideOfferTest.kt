package dev.kstep.desktop.draft

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

class DecideOfferTest :
    StringSpec({
        val path = Path.of("/tmp/a.kstep.kts").toAbsolutePath()
        val hashA = ByteArray(32) { 1 }
        val hashB = ByteArray(32) { 2 }

        fun draft(
            body: String = "mine",
            hash: ByteArray? = hashA,
        ) = Draft(path, 1, 2, hash, 5, 9, body)

        fun kind(d: OfferDecision) = d.shouldBeInstanceOf<OfferDecision.Offer>().offer.kind

        "no draft, no offer" {
            decideOffer(null, FileState.Editable(hashA, "x")) shouldBe OfferDecision.None
            decideOffer(null, FileState.Missing) shouldBe OfferDecision.None
        }

        "the same file hash means the file is unchanged" {
            kind(decideOffer(draft(), FileState.Editable(hashA, "disk"))) shouldBe OfferKind.UNCHANGED
        }

        "another file hash means the file changed" {
            kind(decideOffer(draft(), FileState.Editable(hashB, "disk"))) shouldBe OfferKind.CHANGED
        }

        "a draft started without a file is CHANGED once the file exists" {
            kind(decideOffer(draft(hash = null), FileState.Editable(hashA, "disk"))) shouldBe OfferKind.CHANGED
        }

        "a draft equal to the file text is deleted silently" {
            decideOffer(draft("same"), FileState.Editable(hashB, "same")) shouldBe OfferDecision.DeleteSilently
        }

        "a missing file is DELETED" {
            kind(decideOffer(draft(), FileState.Missing)) shouldBe OfferKind.DELETED
        }

        "a file that cannot be edited any more is NOT_EDITABLE with its reason" {
            val offer =
                decideOffer(draft(), FileState.NotEditable(hashB, "the file is larger than 1 MiB"))
                    .shouldBeInstanceOf<OfferDecision.Offer>()
                    .offer
            offer.kind shouldBe OfferKind.NOT_EDITABLE
            offer.notEditableReason shouldBe "the file is larger than 1 MiB"
            offer.body shouldBe "mine"
            offer.written shouldBe 9
        }
    })
