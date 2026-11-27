package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartPlacement
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** ADR-0091: a part's line is trusted only against exactly the text that was evaluated. */
class ShowInScriptInputTest :
    StringSpec({
        fun info(line: Int?) =
            PartInfo(
                "A",
                0,
                1,
                PartPlacement(DoubleArray(3), DoubleArray(9)),
                "#000000",
                DoubleArray(3),
                DoubleArray(3),
                DoubleArray(3),
                line,
            )

        "usable only with a line and an identical text" {
            ShowInScriptInput.positionUsable(info(3), "abc", "abc") shouldBe true
            ShowInScriptInput.positionUsable(info(3), "abc", "abd") shouldBe false // buffer changed since the run
            ShowInScriptInput.positionUsable(info(3), null, "abc") shouldBe false // no recorded text
            ShowInScriptInput.positionUsable(info(null), "abc", "abc") shouldBe false // old worker, no line
            ShowInScriptInput.positionUsable(null, "abc", "abc") shouldBe false // no part (group)
        }

        "a dirty buffer after a saved-file run is not usable" {
            ShowInScriptInput.positionUsable(info(2), "saved", "saved + edit") shouldBe false
        }
    })
