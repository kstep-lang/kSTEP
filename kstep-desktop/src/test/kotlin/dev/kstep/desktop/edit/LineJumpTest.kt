package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LineJumpTest :
    StringSpec({
        "valid lines, also at the edges and with padding" {
            LineJump.parse("42", 100) shouldBe LineJump.Result.Valid(42)
            LineJump.parse(" 7 ", 100) shouldBe LineJump.Result.Valid(7)
            LineJump.parse("\t7\t", 100) shouldBe LineJump.Result.Valid(7)
            LineJump.parse("1", 100) shouldBe LineJump.Result.Valid(1)
            LineJump.parse("100", 100) shouldBe LineJump.Result.Valid(100)
            LineJump.parse("000000007", 100) shouldBe LineJump.Result.Valid(7)
        }

        "out of range reports the allowed range" {
            LineJump.parse("0", 100) shouldBe LineJump.Result.OutOfRange(1, 100)
            LineJump.parse("101", 100) shouldBe LineJump.Result.OutOfRange(1, 100)
            LineJump.parse("999999999", 100) shouldBe LineJump.Result.OutOfRange(1, 100)
        }

        "empty input" {
            LineJump.parse("", 5) shouldBe LineJump.Result.Empty
            LineJump.parse("   ", 5) shouldBe LineJump.Result.Empty
        }

        "not a number" {
            for (s in listOf("abc", "4 2", "-3", "+3", "3:5", "٣", "４２", "1234567890", "4\n2", " 7")) {
                LineJump.parse(s, 100) shouldBe LineJump.Result.NotANumber
            }
        }

        "an empty file has one line" {
            LineJump.parse("1", 1) shouldBe LineJump.Result.Valid(1)
            LineJump.parse("2", 1) shouldBe LineJump.Result.OutOfRange(1, 1)
            LineJump.parse("1", 0) shouldBe LineJump.Result.Valid(1)
        }
    })
