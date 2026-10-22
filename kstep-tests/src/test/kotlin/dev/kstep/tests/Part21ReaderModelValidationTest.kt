package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun doc(dataSection: String): String =
    "ISO-10303-21;\nHEADER;\n" +
        "FILE_DESCRIPTION((''),'2;1');\n" +
        "FILE_NAME('a.step','2026-07-19T12:00:00',('A'),('kSTEP'),'','kSTEP','');\n" +
        "FILE_SCHEMA(('AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF'));\n" +
        "ENDSEC;\nDATA;\n$dataSection" +
        "ENDSEC;\nEND-ISO-10303-21;\n"

class Part21ReaderModelValidationTest :
    StringSpec({
        "without the opt-in flag the result carries no model validation and behaves as before" {
            val result = Part21Reader.read(doc("#1=APPLICATION_CONTEXT('x');\n"))
            result.modelValidation shouldBe null
            result.isModelValid shouldBe true
            result.isFullySuccessful shouldBe true
            result.instances.size shouldBe 1
        }

        "with the flag an unreferenced APPLICATION_CONTEXT is reported while the read stays successful" {
            val result = Part21Reader.read(doc("#1=APPLICATION_CONTEXT('x');\n"), validateModel = true)
            result.isFullySuccessful shouldBe true
            result.isModelValid shouldBe false
            val finding = result.modelValidation!!.findings.single()
            finding.violations.single().code shouldBe DslViolationCodes.INVERSE_BOUND_VIOLATED
        }

        "with the flag a properly referenced APPLICATION_CONTEXT validates cleanly" {
            val result =
                Part21Reader.read(
                    doc("#1=APPLICATION_CONTEXT('x');\n#2=PRODUCT_CONTEXT('eng',#1,'mech');\n"),
                    validateModel = true,
                )
            result.modelValidation shouldNotBe null
            result.isModelValid shouldBe true
            result.modelValidation!!.instanceCount shouldBe 2
        }

        "TOLERANT: a referrer kept opaque does not cause a false INVERSE finding on its target" {
            val result =
                Part21Reader.read(
                    doc("#1=APPLICATION_CONTEXT('x');\n#2=MECHANICAL_CONTEXT('',#1,'mech');\n"),
                    mode = Part21ReadMode.TOLERANT,
                    validateModel = true,
                )
            result.opaque.keys shouldBe setOf(2)
            result.isModelValid shouldBe true
            result.modelValidation!!.suppressedFindingCount shouldBe 1
        }

        "TOLERANT: a complex instance referencing the target also withholds the INVERSE finding" {
            val result =
                Part21Reader.read(
                    doc("#1=APPLICATION_CONTEXT('x');\n#2=(MECHANICAL_CONTEXT('',#1,'mech')FOO_CONTEXT());\n"),
                    mode = Part21ReadMode.TOLERANT,
                    validateModel = true,
                )
            result.opaque.keys shouldBe setOf(2)
            result.isModelValid shouldBe true
            result.modelValidation!!.suppressedFindingCount shouldBe 1
        }
    })
