package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.script.KStepModel
import dev.kstep.script.ModelValidationOutcome
import dev.kstep.script.UnavailableKind
import dev.kstep.script.validateModel
import dev.kstep.step21.Part21Header
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private fun modelOf(vararg roots: Any): KStepModel =
    KStepModel(
        header =
            Part21Header(
                fileName = "t.step",
                timestamp = "2026-01-01T00:00:00",
                schemaIdentifiers = listOf("AP242"),
            ),
        roots = roots.toList(),
        violations = emptyList(),
    )

class KStepModelValidationTest :
    StringSpec({
        "validateModel on a valid assembly is Checked and valid" {
            val m = AssemblyTestModel()
            val outcome = modelOf(m.usage("U1", m.pd("TOP"), m.pd("LEAF"))).validateModel()
            val checked = outcome.shouldBeInstanceOf<ModelValidationOutcome.Checked>()
            checked.result.isValid shouldBe true
        }

        "validateModel reports a usage cycle as Checked with KSTEP-W-002" {
            val m = AssemblyTestModel()
            val a = m.pd("A")
            val b = m.pd("B")
            val checked =
                modelOf(m.usage("U1", a, b), m.usage("U2", b, a))
                    .validateModel()
                    .shouldBeInstanceOf<ModelValidationOutcome.Checked>()
            checked.result.isValid shouldBe false
            checked.result.findings
                .flatMap { it.violations }
                .map { it.code }
                .toSet() shouldBe setOf(DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED)
        }

        "validateModel with an exceeded instance limit is Unavailable(LIMIT_EXCEEDED) naming the limit" {
            val m = AssemblyTestModel()
            val outcome =
                modelOf(m.usage("U1", m.pd("TOP"), m.pd("LEAF")))
                    .validateModel(ReverseReferenceIndex.Limits(maxInstances = 1))
            val unavailable = outcome.shouldBeInstanceOf<ModelValidationOutcome.Unavailable>()
            unavailable.kind shouldBe UnavailableKind.LIMIT_EXCEEDED
            unavailable.message shouldContain "1"
        }

        "validateModel with an unknown root type is Unavailable(UNKNOWN_INSTANCE_TYPE)" {
            val unavailable =
                modelOf("not an AP242 entity")
                    .validateModel()
                    .shouldBeInstanceOf<ModelValidationOutcome.Unavailable>()
            unavailable.kind shouldBe UnavailableKind.UNKNOWN_INSTANCE_TYPE
        }
    })
