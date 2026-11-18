package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class GroupInspectionTest :
    StringSpec({
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(30.0, 0.0, 0.0),
                triangle(60.0, 5.0, 2.0),
                names = listOf("A", "B", "C"),
            )
        // Top > [A, Sub > [B, C]]
        val tree =
            AssemblyTree(
                arrayOf("Top", "A", "Sub", "B", "C"),
                intArrayOf(-1, 0, 0, 2, 2),
                intArrayOf(-1, 0, -1, 1, 2),
            )

        "a group sums the triangles of its part range and joins their boxes" {
            val info = GroupInspection.of(tree, 2, table)!!
            info.name shouldBe "Sub"
            info.partCount shouldBe 2
            info.triangleCount shouldBe 2L
            info.bboxMin.toList() shouldBe listOf(30.0, 0.0, 0.0)
            info.bboxMax.toList() shouldBe listOf(70.0, 15.0, 2.0)
            info.center.toList() shouldBe listOf(50.0, 7.5, 1.0)
            GroupInspection.size(info) shouldBe "40.00 × 15.00 × 2.00 mm"
        }

        "the root covers all parts" {
            val info = GroupInspection.of(tree, 0, table)!!
            info.partCount shouldBe 3
            info.bboxMin.toList() shouldBe listOf(0.0, 0.0, 0.0)
        }

        "a leaf, an unknown node or a tree that does not match the table gives no group" {
            GroupInspection.of(tree, 1, table).shouldBeNull()
            GroupInspection.of(tree, 9, table).shouldBeNull()
            val (_, small) = TestScenes.scene(triangle(0.0, 0.0, 0.0), names = listOf("A"))
            GroupInspection.of(tree, 0, small).shouldBeNull()
        }

        "triangle counts use a thousands separator" {
            GroupInspection.triangles(GroupInfo("g", 1, 1_234_567L, DoubleArray(3), DoubleArray(3))) shouldBe
                "1,234,567"
        }
    })
