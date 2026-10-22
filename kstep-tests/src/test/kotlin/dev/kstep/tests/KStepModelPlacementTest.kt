package dev.kstep.tests

import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.shape.RigidPlacement
import dev.kstep.shape.Rotation
import dev.kstep.shape.Vec3
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private const val PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    fun pd(id: String) = productDefinition("${'$'}id-D") {
        formation = productDefinitionFormation("${'$'}id-F") { ofProduct = product(id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow() }.getOrThrow()
        frameOfReference = defCtx
    }.getOrThrow()
    val a = pd("A")
    val b = pd("B")
    fun usage(id: String) = nextAssemblyUsageOccurrence(id) { name = id; relatingProductDefinition = a; relatedProductDefinition = b }
    """

private fun eval(body: String) = KStepScriptHost.eval(PRELUDE + body, "placement-test.kstep.kts")

class KStepModelPlacementTest :
    StringSpec({
        "a model without place(...) has no placements" {
            val success =
                eval("""stepFile(fileName = "x.step") { root(usage("U1").getOrThrow()) }""")
                    .shouldBeInstanceOf<KStepScriptOutcome.Success>()
            success.model.placements shouldHaveSize 0
        }

        "place(usage, translation, rotation) registers a usage placement and does not add a root" {
            val success =
                eval(
                    """
                    val u = usage("U1").getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(u)
                        place(u, translation = vec(30.0, 5.0, 10.0), rotation = Rotation.about(Vec3.Z, 90.0))
                    }
                    """,
                ).shouldBeInstanceOf<KStepScriptOutcome.Success>()
            success.model.roots shouldHaveSize 1
            success.model.placements shouldHaveSize 1
            val p = success.model.placements.single()
            p.usage.id shouldBe "U1"
            p.placement shouldBe RigidPlacement(Vec3(30.0, 5.0, 10.0), Rotation.about(Vec3.Z, 90.0))
        }

        "place with a ready-made RigidPlacement and with a raw ValidationResult" {
            val success =
                eval(
                    """
                    val u1 = usage("U1").getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(u1)
                        place(u1, RigidPlacement(vec(1.0, 2.0, 3.0)))
                        place(usage("U2"), translation = vec(4.0, 5.0, 6.0))
                    }
                    """,
                ).shouldBeInstanceOf<KStepScriptOutcome.Success>()
            success.model.placements.map { it.usage.id } shouldBe listOf("U1", "U2")
            success.model.placements[1]
                .placement.translation shouldBe Vec3(4.0, 5.0, 6.0)
        }

        "a non-orthonormal Rotation.fromAxes aborts the script with a clear message (KSTEP-S-003 family)" {
            val outcome =
                eval(
                    """
                    val u = usage("U1").getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(u)
                        place(u, rotation = Rotation.fromAxes(vec(0.0, 0.0, 2.0), vec(1.0, 0.0, 0.0)))
                    }
                    """,
                )
            val error = outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
            error.message shouldContain "unit length"
        }

        "a NaN translation aborts the script with a clear message" {
            val outcome =
                eval(
                    """
                    val u = usage("U1").getOrThrow()
                    stepFile(fileName = "x.step") { root(u); place(u, translation = vec(Double.NaN, 0.0, 0.0)) }
                    """,
                )
            outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>().message shouldContain "finite"
        }
    })
