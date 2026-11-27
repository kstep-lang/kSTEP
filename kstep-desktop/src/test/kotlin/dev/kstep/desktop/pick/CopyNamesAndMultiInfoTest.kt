package dev.kstep.desktop.pick

import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CopyNamesAndMultiInfoTest :
    StringSpec({
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(20.0, 0.0, 0.0) + triangle(20.0, 20.0, 0.0),
                triangle(40.0, 0.0, 5.0),
                names = listOf("Bolt", "Bolt", "Nut"),
            )

        "copied text is one line per part, # k only for names that repeat" {
            CopyNames.text(listOf(PartRef("Bolt", 1), PartRef("Nut", 0), PartRef("Bolt", 0)), table) shouldBe
                "Bolt #2\nNut\nBolt #1"
        }

        "control and bidirectional characters are made visible, long names are cut" {
            val t = RawTables.table(listOf("a‮b\u0007c", "n".repeat(300)))
            val text = CopyNames.text(listOf(PartRef(t.parts[0].name, 0), PartRef(t.parts[1].name, 0)), t)
            val lines = text.split("\n")
            lines[0] shouldBe "a‹U+202E›b‹U+0007›c"
            lines[1].length shouldBe CopyNames.NAME_CLIP + 1
            lines[1].endsWith("…") shouldBe true
            text.contains('‮') shouldBe false
            text.contains('\u0007') shouldBe false
        }

        "at most the limit of lines" {
            val refs = (0 until MAX_SELECTION + 10).map { PartRef("Nut", 0) }
            CopyNames.text(refs, table).split("\n").size shouldBe MAX_SELECTION
        }

        "multi info sums triangles and merges the boxes, and has no mass" {
            val info = MultiInspection.of(intArrayOf(0, 1, 2), table)!!
            info.partCount shouldBe 3
            info.triangleCount shouldBe 4L
            MultiInspection.title(info) shouldBe "3 parts selected"
            info.bboxMin.toList() shouldBe listOf(0.0, 0.0, 0.0)
            info.bboxMax.toList() shouldBe listOf(50.0, 30.0, 5.0)
            MultiInspection.size(info) shouldBe "50.00 × 30.00 × 5.00 mm"
            MultiInspection.triangles(info) shouldBe "4"
        }

        "multi info ignores indices outside the scene and is null without a valid part" {
            MultiInspection.of(intArrayOf(0, 99, -1), table)!!.partCount shouldBe 1
            MultiInspection.of(intArrayOf(99), table).shouldBeNull()
            MultiInspection.of(IntArray(0), table).shouldBeNull()
        }

        "name lines: at most five, then how many more, names made safe" {
            val refs = (0 until 8).map { PartRef("P$it", 0) }
            MultiInspection.nameLines(refs) shouldBe listOf("P0", "P1", "P2", "P3", "P4", "and 3 more")
            MultiInspection.nameLines(listOf(PartRef("x‮y", 0))) shouldBe listOf("x‹U+202E›y")
        }

        "the click rule per platform" {
            fun of(
                os: MenuOs,
                secondary: Boolean = false,
                meta: Boolean = false,
                ctrl: Boolean = false,
            ) = ClickIntents.of(secondary, meta, ctrl, os)
            of(MenuOs.MAC) shouldBe ClickIntent.Select
            of(MenuOs.MAC, meta = true) shouldBe ClickIntent.Toggle
            of(MenuOs.MAC, ctrl = true) shouldBe ClickIntent.Menu
            of(MenuOs.MAC, secondary = true) shouldBe ClickIntent.Menu
            of(MenuOs.WINDOWS, ctrl = true) shouldBe ClickIntent.Toggle
            of(MenuOs.LINUX, ctrl = true) shouldBe ClickIntent.Toggle
            of(MenuOs.WINDOWS, meta = true) shouldBe ClickIntent.Select
            of(MenuOs.LINUX, secondary = true) shouldBe ClickIntent.Menu
            of(MenuOs.WINDOWS, secondary = true, ctrl = true) shouldBe ClickIntent.Menu
        }
    })
