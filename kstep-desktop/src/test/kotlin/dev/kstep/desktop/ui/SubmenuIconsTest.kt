package dev.kstep.desktop.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem

class SubmenuIconsTest :
    StringSpec({
        fun icon() = ImageIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))

        fun bar(): Triple<JMenuBar, JMenu, JMenuItem> {
            val bar = JMenuBar()
            val file = JMenu("File")
            val recent = JMenu("Open Recent")
            val plain = JMenuItem("Open Recent")
            file.add(plain)
            file.add(recent)
            bar.add(file)
            return Triple(bar, recent, plain)
        }

        "sets the icon of the submenu below the top menu" {
            val (bar, recent, _) = bar()
            val i = icon()
            applySubmenuIcons(bar, "File", mapOf("Open Recent" to i)) shouldBe 1
            (recent.icon === i) shouldBe true
        }

        "a second call with the same icon changes nothing" {
            val (bar, _, _) = bar()
            val i = icon()
            applySubmenuIcons(bar, "File", mapOf("Open Recent" to i))
            applySubmenuIcons(bar, "File", mapOf("Open Recent" to i)) shouldBe 0
        }

        "a new icon object replaces the old one" {
            val (bar, recent, _) = bar()
            applySubmenuIcons(bar, "File", mapOf("Open Recent" to icon()))
            val n = icon()
            applySubmenuIcons(bar, "File", mapOf("Open Recent" to n)) shouldBe 1
            (recent.icon === n) shouldBe true
        }

        "a wrong top menu title or no menu bar sets nothing" {
            val (bar, recent, _) = bar()
            applySubmenuIcons(bar, "Edit", mapOf("Open Recent" to icon())) shouldBe 0
            applySubmenuIcons(null, "File", mapOf("Open Recent" to icon())) shouldBe 0
            recent.icon shouldBe null
        }

        "an unknown submenu text stays unchanged" {
            val (bar, recent, _) = bar()
            applySubmenuIcons(bar, "File", mapOf("Other" to icon())) shouldBe 0
            recent.icon shouldBe null
        }

        "a plain menu item with the same text is never touched" {
            val (bar, _, plain) = bar()
            applySubmenuIcons(bar, "File", mapOf("Open Recent" to icon()))
            plain.icon shouldBe null
        }
    })
