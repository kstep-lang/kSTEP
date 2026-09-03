package dev.kstep.tests

import dev.kstep.cli.PreviewSummary
import dev.kstep.geometry.OcctAvailability
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val FIXED_TIMESTAMP = "2026-01-01T00:00:00Z"

private const val PRODUCT_STRUCTURE_SCRIPT =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    val part = product("PS-001") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
    val prodFormation = productDefinitionFormation("PS-001-F") { ofProduct = part }.getOrThrow()
    val definition = productDefinition("PS-001-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
    stepFile(fileName = "ps.step") { root(definition) }
    """

private val availableOcct = OcctAvailability.Available(librarySource = "test", occtVersion = "7.9.2")
private val unavailableOcct = OcctAvailability.Unavailable(reason = "OCCT native bridge unavailable: test")

class PreviewSummaryTest :
    StringSpec({
        fun fixedModel() =
            (KStepScriptHost.eval(PRODUCT_STRUCTURE_SCRIPT, "ps.kstep.kts") as KStepScriptOutcome.Success)
                .model
                .let { it.copy(header = it.header.copy(timestamp = FIXED_TIMESTAMP)) }

        "modelLines renders a deterministic snapshot for a fixed model" {
            val model = fixedModel()
            val lines = PreviewSummary.modelLines("ps.kstep.kts", model, availableOcct, withStep = false)

            lines[0] shouldBe "kSTEP preview -- ps.kstep.kts"
            lines shouldContainLine "  file name   ps.step"
            lines shouldContainLine "  timestamp   $FIXED_TIMESTAMP"
            lines shouldContainLine "  roots       1"
            lines shouldContainLine "  shapes      0"
            lines shouldContainLine "  geometry    none -- product structure only"
            lines shouldContainLine "Validation"
            lines shouldContainLine "  OK -- 0 violations"
            lines shouldContainLine "OCCT"
            lines shouldContainLine "  available -- 7.9.2 (test)"
            lines.any { it.contains("PRODUCT_DEFINITION") } shouldBe true
        }

        "modelLines reports OCCT unavailability when given an Unavailable availability" {
            val model = fixedModel()
            val lines = PreviewSummary.modelLines("ps.kstep.kts", model, unavailableOcct, withStep = false)
            lines.any { it.contains("unavailable") } shouldBe true
        }

        "modelLines with withStep=true includes the full Part-21 text, without it, it does not" {
            val model = fixedModel()
            val withStep = PreviewSummary.modelLines("ps.kstep.kts", model, availableOcct, withStep = true)
            val withoutStep = PreviewSummary.modelLines("ps.kstep.kts", model, availableOcct, withStep = false)

            withStep.any { it.contains("ISO-10303-21;") } shouldBe true
            withoutStep.any { it.contains("ISO-10303-21;") } shouldBe false
        }

        "modelLines caps the entity listing at 200 with a summary line beyond that" {
            // 100 independent product/product_definition pairs -- well beyond MAX_ENTITY_LINES
            // (200) once contexts, products, formations, and definitions are all counted.
            val script =
                buildString {
                    appendLine(
                        """
                        val appCtx = applicationContext { application = "config control" }.getOrThrow()
                        val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
                        val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
                        """.trimIndent(),
                    )
                    for (i in 1..100) {
                        appendLine(
                            """
                            val part$i = product("P-$i") { name = "Part $i"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                            val formation$i = productDefinitionFormation("P-$i-F") { ofProduct = part$i }.getOrThrow()
                            val def$i = productDefinition("P-$i-D") { formation = formation$i; frameOfReference = defCtx }.getOrThrow()
                            """.trimIndent(),
                        )
                    }
                    appendLine("stepFile(fileName = \"many.step\") {")
                    for (i in 1..100) appendLine("    root(def$i)")
                    appendLine("}")
                }
            val outcome = KStepScriptHost.eval(script, "many.kstep.kts")
            val model = outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>().model

            val lines = PreviewSummary.modelLines("many.kstep.kts", model, availableOcct, withStep = false)
            lines.any { it.contains("... and") && it.contains("more") } shouldBe true
        }

        "noticeLines includes the reason, the apt-get install line when OCCT is unavailable, and roots/shapes counts" {
            val model = fixedModel()
            val lines =
                PreviewSummary.noticeLines(
                    "ps.kstep.kts",
                    "OCCT native bridge unavailable: test",
                    unavailableOcct,
                    model,
                )

            lines.any { it.contains("Geometry preview unavailable") } shouldBe true
            lines.any { it.contains("reason:") && it.contains("OCCT native bridge unavailable") } shouldBe true
            lines.any { it.contains("apt-get") } shouldBe true
            lines.any { it.contains("roots") && it.contains("1") } shouldBe true
        }

        "noticeLines omits install instructions when OCCT IS available (a triangulation-only failure)" {
            val model = fixedModel()
            val lines = PreviewSummary.noticeLines("ps.kstep.kts", "triangulation failed", availableOcct, model)
            lines.any { it.contains("apt-get") } shouldBe false
        }

        // Regression test for a real, reproduced crash: Part21Writer.emit throws
        // Part21WriteException the moment ANY object reachable from model.roots is not one of the
        // twelve supported kstep-core AP242 V1/support entity types -- root(entity: Any) accepts
        // anything, so this is reachable from a script, not just a hypothetical. Both content
        // paths (modelLines -- summary/geometry, and noticeLines -- the Pflicht-Fallback) must
        // degrade to an "unavailable: ..." line instead of propagating the exception.
        fun unsupportedRootModel() =
            (
                KStepScriptHost.eval(
                    """
                    val appCtx = applicationContext { application = "config control" }.getOrThrow()
                    stepFile(fileName = "u.step") { root(appCtx); root("this is not an AP242 entity") }
                    """.trimIndent(),
                    "unsupported.kstep.kts",
                ) as KStepScriptOutcome.Success
            ).model

        "modelLines reports the entity list as unavailable instead of crashing on an unsupported root type" {
            val model = unsupportedRootModel()
            val lines = PreviewSummary.modelLines("unsupported.kstep.kts", model, availableOcct, withStep = false)
            lines.any { it.contains("Part 21 instances") && it.contains("unavailable") } shouldBe true
        }

        "modelLines redacts an absolute filesystem path in librarySource down to its file name" {
            val model = fixedModel()
            val occtWithPath =
                OcctAvailability.Available(
                    librarySource =
                        "override property (kstep.occt.bridge.library=/home/alice/dev/libkstep_occt_bridge.so)",
                    occtVersion = "7.9.2",
                )
            val lines = PreviewSummary.modelLines("ps.kstep.kts", model, occtWithPath, withStep = false)

            lines.none { it.contains("/home/alice") } shouldBe true
            lines.any { it.contains("<redacted>/libkstep_occt_bridge.so") } shouldBe true
        }

        "modelLines redacts an absolute filesystem path in an Unavailable reason down to its file name" {
            val model = fixedModel()
            val occtUnavailableWithPath =
                OcctAvailability.Unavailable(
                    reason =
                        "OCCT native bridge unavailable: java.lang.UnsatisfiedLinkError: " +
                            "/home/alice/dev/libkstep_occt_bridge.so: cannot open shared object file",
                )
            val lines = PreviewSummary.modelLines("ps.kstep.kts", model, occtUnavailableWithPath, withStep = false)

            lines.none { it.contains("/home/alice") } shouldBe true
            lines.any { it.contains("<redacted>/libkstep_occt_bridge.so") } shouldBe true
        }

        "noticeLines redacts an absolute filesystem path in the reason down to its file name" {
            val model = fixedModel()
            val lines =
                PreviewSummary.noticeLines(
                    "ps.kstep.kts",
                    "OCCT native bridge unavailable: java.lang.UnsatisfiedLinkError: " +
                        "/home/alice/dev/libkstep_occt_bridge.so: cannot open shared object file",
                    unavailableOcct,
                    model,
                )

            lines.none { it.contains("/home/alice") } shouldBe true
            lines.any { it.contains("<redacted>/libkstep_occt_bridge.so") } shouldBe true
        }

        "noticeLines reports the entity list as unavailable instead of crashing on an unsupported root type" {
            val model = unsupportedRootModel()
            val lines =
                PreviewSummary.noticeLines(
                    "unsupported.kstep.kts",
                    "triangulation failed",
                    availableOcct,
                    model,
                )
            lines.any { it.contains("Part 21 instances") && it.contains("unavailable") } shouldBe true
        }
    })

private infix fun List<String>.shouldContainLine(expected: String) {
    this.contains(expected) shouldBe true
}
