package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class IndentTest :
    StringSpec({
        "Enter keeps the indentation of the line" {
            Indent.onEnter("  foo", 5, 5) shouldBe ("  foo\n  " to 8)
        }

        "Enter after an opening brace indents one level deeper" {
            Indent.onEnter("a {", 3, 3) shouldBe ("a {\n    " to 8)
        }

        "Enter between braces opens a block" {
            Indent.onEnter("a {}", 3, 3) shouldBe ("a {\n    \n}" to 8)
        }

        "Enter replaces the selection" {
            Indent.onEnter("abcd", 1, 3) shouldBe ("a\nd" to 2)
        }

        "Tab inserts four spaces and replaces the selection" {
            Indent.onTab("ab", 1, 1) shouldBe ("a    b" to 5)
            Indent.onTab("abc", 0, 2) shouldBe ("    c" to 4)
        }
    })
