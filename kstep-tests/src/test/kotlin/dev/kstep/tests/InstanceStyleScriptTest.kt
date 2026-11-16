package dev.kstep.tests

import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** `styleInstance(...)` in the script DSL (ADR-0044). Pure Kotlin: runs without OCCT. */
class InstanceStyleScriptTest :
    StringSpec({
        val prelude =
            """
            val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
            val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
            val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
            fun part(id: String): ProductDefinition {
                val p = product(id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow()
                val f = productDefinitionFormation("${'$'}id-F") { ofProduct = p }.getOrThrow()
                return productDefinition("${'$'}id-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
            }
            val asm = part("ASM")
            val box = part("BOX")
            val u1 = nextAssemblyUsageOccurrence("U1") { name = "U1"; relatingProductDefinition = asm; relatedProductDefinition = box }.getOrThrow()
            val u2 = nextAssemblyUsageOccurrence("U2") { name = "U2"; relatingProductDefinition = asm; relatedProductDefinition = box }.getOrThrow()
            """.trimIndent()

        fun eval(body: String) = KStepScriptHost.eval("$prelude\n$body\n")

        "styleInstance registers one colour per usage in registration order" {
            val outcome =
                eval(
                    "stepFile(fileName = \"s.step\") { roots(u1, u2); styleInstance(u2, StepColours.BLUE); styleInstance(u1, StepColours.RED) }",
                )
            val model = outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>().model
            model.usageStyles.map { it.usage.id } shouldBe listOf("U2", "U1")
            model.usageStyles.map { it.colour.b } shouldBe listOf(1.0, 0.0)
            model.isValid shouldBe true
            // an instance colour is not a shape: the model still has no geometry
            model.hasGeometry shouldBe false
        }

        "a model without styleInstance has no instance colours" {
            eval("stepFile(fileName = \"s.step\") { roots(u1) }")
                .shouldBeInstanceOf<KStepScriptOutcome.Success>()
                .model.usageStyles shouldBe emptyList()
        }

        "a second colour for the same usage fails with KSTEP-S-014" {
            val outcome =
                eval(
                    "stepFile(fileName = \"s.step\") { styleInstance(u1, StepColours.RED); styleInstance(u1, StepColours.BLUE) }",
                )
            val error = outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
            error.code shouldBe "KSTEP-S-014"
            error.message.contains("U1") shouldBe true
        }

        "a transparent instance colour fails with KSTEP-S-012" {
            val outcome =
                eval("stepFile(fileName = \"s.step\") { styleInstance(u1, StepColour.rgba(1.0, 0.0, 0.0, 0.5)) }")
            val error = outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
            error.code shouldBe "KSTEP-S-012"
            error.message.contains("opaque") shouldBe true
        }

        "an invalid usage contributes its violations instead of aborting the script" {
            val outcome =
                eval(
                    "val broken = nextAssemblyUsageOccurrence(\"BROKEN\") { name = \"BROKEN\" }\n" +
                        "stepFile(fileName = \"s.step\") { styleInstance(broken, StepColours.RED) }",
                )
            val model =
                when (outcome) {
                    is KStepScriptOutcome.Success -> outcome.model
                    is KStepScriptOutcome.ValidationErrors -> null
                    else -> error("unexpected outcome $outcome")
                }
            if (model != null) {
                model.violations.isNotEmpty() shouldBe true
                model.usageStyles shouldBe emptyList()
            }
        }
    })
