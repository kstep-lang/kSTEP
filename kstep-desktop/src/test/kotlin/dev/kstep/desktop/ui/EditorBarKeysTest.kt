package dev.kstep.desktop.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class EditorBarKeysTest :
    StringSpec({
        fun enter(
            find: Boolean = true,
            goTo: Boolean = false,
            focus: BarFocus,
            shift: Boolean = false,
        ) = barEnterAction(find, goTo, focus, shift)

        "Enter in the query field jumps to the next match, Shift+Enter to the previous" {
            enter(focus = BarFocus(query = true)) shouldBe BarEnterAction.NextMatch
            enter(focus = BarFocus(query = true), shift = true) shouldBe BarEnterAction.PreviousMatch
        }

        "Enter in the replace field replaces" {
            enter(focus = BarFocus(replace = true)) shouldBe BarEnterAction.Replace
        }

        "Enter in the go-to field commits the jump" {
            enter(find = false, goTo = true, focus = BarFocus(goTo = true)) shouldBe BarEnterAction.CommitGoTo
        }

        "clicking from the replace field into the editor leaves Enter to the editor" {
            var focus = BarFocus()
            focus = focus.copy(replace = true)
            enter(focus = focus) shouldBe BarEnterAction.Replace
            focus = focus.copy(replace = false) // focus lost: the click into the editor
            focus.any shouldBe false
            enter(focus = focus).shouldBeNull()
        }

        "moving from the query to the replace field and into the editor releases both" {
            var focus = BarFocus(query = true)
            focus = focus.copy(replace = true).copy(query = false)
            enter(focus = focus) shouldBe BarEnterAction.Replace
            focus = focus.copy(replace = false)
            enter(focus = focus).shouldBeNull()
        }

        "the focus events may arrive in either order" {
            // replace gains focus before the query reports its loss
            var focus = BarFocus(query = true).copy(replace = true)
            focus = focus.copy(query = false)
            enter(focus = focus) shouldBe BarEnterAction.Replace
        }

        "no bar open or no field focused leaves Enter to the editor" {
            enter(find = false, goTo = false, focus = BarFocus(query = true)).shouldBeNull()
            enter(focus = BarFocus()).shouldBeNull()
            enter(find = false, goTo = true, focus = BarFocus(query = true)).shouldBeNull()
        }
    })
