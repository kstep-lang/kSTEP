package dev.kstep.desktop.ui

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class MenuIconTintTest :
    StringSpec({
        "macOS follows the system appearance" {
            menuIconTint(true, false, null, true) shouldBe Color(0xFF2B2B2B)
            menuIconTint(true, true, null, true) shouldBe Color(0xFFE0E0E0)
        }

        "macOS ignores the Swing colour" {
            menuIconTint(true, false, java.awt.Color(255, 0, 0), true) shouldBe Color(0xFF2B2B2B)
        }

        "other systems take the Swing colour including its alpha" {
            menuIconTint(false, true, java.awt.Color(10, 20, 30, 128), true) shouldBe Color(10, 20, 30, 128)
        }

        "other systems without a Swing colour fall back to the light tint" {
            menuIconTint(false, true, null, true) shouldBe Color(0xFF2B2B2B)
        }

        "disabled gives 35 percent alpha and keeps the colour" {
            val t = menuIconTint(true, true, null, false)
            (abs(t.alpha - 0.35f) < 0.01f) shouldBe true
            t.red shouldBe Color(0xFFE0E0E0).red
            t.green shouldBe Color(0xFFE0E0E0).green
            t.blue shouldBe Color(0xFFE0E0E0).blue
        }
    })
