package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DisplayNameTest :
    StringSpec({
        "a right-to-left override is made visible" {
            val spans = DisplayName.spans("ab" + 0x202E.toChar() + "cd")
            spans.map { it.text } shouldBe listOf("ab", "‹U+202E›", "cd")
            spans[1].escaped shouldBe true
        }

        "isolates, C0, C1 and DEL are escaped" {
            DisplayName.isSuspicious(0x2066) shouldBe true
            DisplayName.isSuspicious(0x2069) shouldBe true
            DisplayName.isSuspicious(0x00) shouldBe true
            DisplayName.isSuspicious(0x1B) shouldBe true
            DisplayName.isSuspicious(0x7F) shouldBe true
            DisplayName.isSuspicious(0x85) shouldBe true
            DisplayName.isSuspicious(0x200E) shouldBe true
            DisplayName.isSuspicious(0x061C) shouldBe true
            DisplayName.isSuspicious(0x2028) shouldBe true
            DisplayName.isSuspicious('a'.code) shouldBe false
            DisplayName.isSuspicious(0x00E4) shouldBe false
        }

        "a surrogate pair stays intact" {
            val s = "x😀y"
            DisplayName.spans(s).single().text shouldBe s
            DisplayName.escaped("\u001B[31m") shouldBe "‹U+001B›[31m"
        }

        "long names are cut" {
            DisplayName.escaped("a".repeat(300), maxChars = 10) shouldBe "aaaaaaaaaa…"
        }
    })
