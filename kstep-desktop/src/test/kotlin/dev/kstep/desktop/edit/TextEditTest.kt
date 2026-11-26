package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TextEditTest :
    StringSpec({
        "equal texts give no edit" {
            TextEdit.between("abc", "abc") shouldBe null
            TextEdit.between("", "") shouldBe null
        }

        "insert, delete and replace" {
            TextEdit.between("abc", "abXc") shouldBe TextEdit(2, 2, 3)
            TextEdit.between("abXc", "abc") shouldBe TextEdit(2, 3, 2)
            TextEdit.between("abcd", "aXYd") shouldBe TextEdit(1, 3, 3)
            TextEdit.between("", "abc") shouldBe TextEdit(0, 0, 3)
            TextEdit.between("abc", "") shouldBe TextEdit(0, 3, 0)
        }

        "delta is the change of length" {
            TextEdit.between("abc", "abXXc")!!.delta shouldBe 2
            TextEdit.between("abXXc", "abc")!!.delta shouldBe -2
        }

        "prefix and suffix never overlap" {
            TextEdit.between("aaaa", "aaa") shouldBe TextEdit(3, 4, 3)
            TextEdit.between("aaa", "aaaa") shouldBe TextEdit(3, 3, 4)
            val e = TextEdit.between("aaa", "aa")!!
            (e.start <= e.oldEnd && e.start <= e.newEnd) shouldBe true
        }

        "an edit never splits a surrogate pair" {
            val a = "x😀y"
            val b = "x😁y"
            val e = TextEdit.between(a, b)!!
            e shouldBe TextEdit(1, 3, 3)
            val c = "x😀😀"
            val d = "x😀"
            TextEdit.between(c, d) shouldBe TextEdit(3, 5, 3)
            // a surrogate pair inserted in front of an equal-looking low half
            TextEdit.between("\uDE00", "😀")!!.let { it.start shouldBe 0 }
        }
    })
