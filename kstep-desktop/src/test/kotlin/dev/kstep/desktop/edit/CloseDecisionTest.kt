package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CloseDecisionTest :
    StringSpec({
        "Save proceeds only if the save succeeded" {
            mayProceed(CloseDecision.SAVE) { true } shouldBe true
            mayProceed(CloseDecision.SAVE) { false } shouldBe false
        }

        "Don't Save proceeds without saving, Cancel never does" {
            var saved = false
            mayProceed(CloseDecision.DONT_SAVE) {
                saved = true
                true
            } shouldBe true
            mayProceed(CloseDecision.CANCEL) {
                saved = true
                true
            } shouldBe false
            saved shouldBe false
        }
    })
