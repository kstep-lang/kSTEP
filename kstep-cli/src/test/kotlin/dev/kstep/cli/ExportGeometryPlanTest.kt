package dev.kstep.cli

import dev.kstep.geometry.OcctAvailability
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val available = OcctAvailability.Available("test-library", "7.9.3")
private val unavailable = OcctAvailability.Unavailable("no bridge")

class ExportGeometryPlanTest :
    StringSpec({
        "placements without any shape are ignored with a note, never silently" {
            val plan = planExportGeometry(0, false, unavailable, placementCount = 2)
            plan.shouldBeInstanceOf<ExportGeometryPlan.StructureOnly>()
            plan.ignoredPlacementsNote shouldBe "2 placement(s) ignored: the script registers no shape(...)"
        }

        "without placements no note is produced (byte-identical structure export path)" {
            val plan = planExportGeometry(0, false, unavailable)
            plan shouldBe ExportGeometryPlan.StructureOnly()
        }

        "placements with shapes but without OCCT are reported as ignored" {
            val plan = planExportGeometry(3, false, unavailable, placementCount = 1)
            plan.shouldBeInstanceOf<ExportGeometryPlan.StructureOnly>()
            plan.unmergedReason shouldBe "no bridge"
            plan.ignoredPlacementsNote shouldBe "1 placement(s) ignored: no geometry was merged (OCCT unavailable)"
        }

        "with OCCT and shapes the plan is Merge regardless of placements" {
            planExportGeometry(3, false, available, placementCount = 3) shouldBe ExportGeometryPlan.Merge
            planExportGeometry(3, true, available) shouldBe ExportGeometryPlan.Merge
        }

        "--require-geometry keeps failing closed when there is no geometry or no OCCT" {
            planExportGeometry(
                0,
                true,
                unavailable,
                placementCount = 2,
            ).shouldBeInstanceOf<ExportGeometryPlan.Fail>().code shouldBe
                "KSTEP-S-006"
            planExportGeometry(
                2,
                true,
                unavailable,
                placementCount = 2,
            ).shouldBeInstanceOf<ExportGeometryPlan.Fail>().code shouldBe
                "KSTEP-S-007"
        }

        "countPlacedUsages counts one per context-dependent shape representation" {
            countPlacedUsages(
                "#1=CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(#2,#3);\n#4=CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(#5,#6);",
            ) shouldBe
                2
            countPlacedUsages("#1=PRODUCT_DEFINITION_SHAPE('','',#2);") shouldBe 0
        }
    })
