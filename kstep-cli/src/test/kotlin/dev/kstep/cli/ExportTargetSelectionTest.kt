package dev.kstep.cli

import dev.kstep.script.ScriptExportFormat
import dev.kstep.script.ScriptExportTarget
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** `selectExportTargets` (ADR-0065): pure; re-validation of every declared name, then the selection. */
class ExportTargetSelectionTest :
    StringSpec({
        val step = ScriptExportTarget(ScriptExportFormat.STEP, "part.step")
        val glb = ScriptExportTarget(ScriptExportFormat.GLB, "part.glb")
        val stl = ScriptExportTarget(ScriptExportFormat.STL, "part.stl")
        val mf = ScriptExportTarget(ScriptExportFormat.THREE_MF, "part.3mf")
        val declared = listOf(step, glb, stl, mf)

        fun named(vararg s: String) = ExportTargetSelection.Named(s.toList())

        fun ok(r: TargetSelectionResult) = r.shouldBeInstanceOf<TargetSelectionResult.Ok>().targets

        fun kind(r: TargetSelectionResult) = r.shouldBeInstanceOf<TargetSelectionResult.Failed>().kind

        "all targets keep the declaration order" {
            ok(selectExportTargets(declared, ExportTargetSelection.All)) shouldBe declared
        }

        "a selector is a format id or the exact declared name, in selection order" {
            ok(selectExportTargets(declared, named("stl", "part.step"))) shouldBe listOf(stl, step)
            ok(selectExportTargets(declared, named("3mf"))) shouldBe listOf(mf)
        }

        "a .stp name is selected through the format step" {
            val stp = ScriptExportTarget(ScriptExportFormat.STEP, "part.stp")
            ok(selectExportTargets(listOf(stp), named("step"))) shouldBe listOf(stp)
            ok(selectExportTargets(listOf(stp), named("part.stp"))) shouldBe listOf(stp)
        }

        "no declared target, an undeclared target and a doubly resolved selector" {
            kind(selectExportTargets(emptyList(), ExportTargetSelection.All)) shouldBe
                ExportTargetsErrorKind.NO_EXPORT_TARGETS
            kind(selectExportTargets(emptyList(), named("step"))) shouldBe ExportTargetsErrorKind.NO_EXPORT_TARGETS
            kind(selectExportTargets(listOf(step), named("stl"))) shouldBe ExportTargetsErrorKind.TARGET_NOT_DECLARED
            kind(selectExportTargets(listOf(step), named("PART.STEP"))) shouldBe
                ExportTargetsErrorKind.TARGET_NOT_DECLARED
            kind(selectExportTargets(declared, named("step", "part.step"))) shouldBe
                ExportTargetsErrorKind.INVALID_REQUEST
        }

        // Mirror of KStepExportBlockTest.rejected: models built through the public constructor skip the builder.
        val hostile =
            listOf(
                ScriptExportFormat.STEP to "../a.step",
                ScriptExportFormat.STEP to "a/b.step",
                ScriptExportFormat.STEP to "a\\b.step",
                ScriptExportFormat.STEP to "a\u0000.step",
                ScriptExportFormat.STEP to "a‮.step",
                ScriptExportFormat.STEP to "a\u0007.step",
                ScriptExportFormat.STEP to "CON.step",
                ScriptExportFormat.STEP to "é.step",
                ScriptExportFormat.STEP to ".step",
                ScriptExportFormat.STEP to ".hidden.step",
                ScriptExportFormat.STEP to "a.stl",
                ScriptExportFormat.GLB to "a.step",
                ScriptExportFormat.STEP to "",
                ScriptExportFormat.STEP to "a.step ",
                ScriptExportFormat.STEP to "a:b.step",
                ScriptExportFormat.STEP to "x".repeat(130) + ".step",
            )

        "a hostile declared name rejects the whole list, also when it is not selected" {
            for ((format, name) in hostile) {
                val list =
                    listOf(ScriptExportTarget(ScriptExportFormat.STL, "fine.stl"), ScriptExportTarget(format, name))
                kind(selectExportTargets(list, named("stl"))) shouldBe ExportTargetsErrorKind.TARGET_INVALID
                kind(selectExportTargets(list, ExportTargetSelection.All)) shouldBe
                    ExportTargetsErrorKind.TARGET_INVALID
            }
        }

        "a list with a repeated format, a case-insensitive repeated name or five targets is rejected" {
            kind(
                selectExportTargets(
                    listOf(step, ScriptExportTarget(ScriptExportFormat.STEP, "b.step")),
                    ExportTargetSelection.All,
                ),
            ) shouldBe
                ExportTargetsErrorKind.TARGET_INVALID
            kind(
                selectExportTargets(
                    listOf(
                        ScriptExportTarget(ScriptExportFormat.STEP, "a.step"),
                        ScriptExportTarget(ScriptExportFormat.STL, "A.STEP"),
                    ),
                    ExportTargetSelection.All,
                ),
            ) shouldBe ExportTargetsErrorKind.TARGET_INVALID
            kind(selectExportTargets(declared + step, ExportTargetSelection.All)) shouldBe
                ExportTargetsErrorKind.TARGET_INVALID
        }

        "a control or direction character never reaches the message raw" {
            val r =
                selectExportTargets(
                    listOf(ScriptExportTarget(ScriptExportFormat.STEP, "a‮.step")),
                    ExportTargetSelection.All,
                ).shouldBeInstanceOf<TargetSelectionResult.Failed>()
            r.message shouldNotContain "‮"
        }
    })
