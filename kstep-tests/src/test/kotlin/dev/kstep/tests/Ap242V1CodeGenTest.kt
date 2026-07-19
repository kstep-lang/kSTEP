package dev.kstep.tests

import com.squareup.kotlinpoet.STRING
import dev.kstep.express.codegen.Ap242V1CodeGen
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * M1 Welle 4, deliverable 3: regenerates the six V1 AP242 entities from the real-schema
 * extraction (`ap242-v1-entities.exp`, see its header comment for provenance) via
 * [Ap242V1CodeGen] — the same object backing the `generateExpressKotlin` Gradle task (see
 * kstep-express/build.gradle.kts).
 */
class Ap242V1CodeGenTest :
    StringSpec({
        "codegen succeeds for exactly five of the six V1 entities against real-schema ground truth" {
            val outcome = Ap242V1CodeGen.generate()

            outcome.generatedEntityNames shouldContainExactlyInAnyOrder
                listOf(
                    "product",
                    "product_definition",
                    "product_definition_formation",
                    "approval",
                    "person_and_organization",
                )
        }

        "real-schema next_assembly_usage_occurrence (SUBTYPE OF, no own attributes) throws CodeGenException" {
            val outcome = Ap242V1CodeGen.generate()

            outcome.skipped shouldContainKey "next_assembly_usage_occurrence"
            outcome.skipped.getValue("next_assembly_usage_occurrence") shouldContain "SUBTYPE OF"
        }

        "product's real-schema constructor resolves id/name TYPE aliases to String and includes frame_of_reference" {
            val outcome = Ap242V1CodeGen.generate()

            val product = outcome.fileSpec.typeSpecs.single { it.name == "Product" }
            val parameters = product.primaryConstructor!!.parameters
            parameters.map { it.name } shouldBe listOf("id", "name", "description", "frameOfReference")
            parameters.single { it.name == "id" }.type shouldBe STRING
            parameters.single { it.name == "name" }.type shouldBe STRING
            // description is OPTIONAL text -> nullable String.
            parameters.single { it.name == "description" }.type shouldBe STRING.copy(nullable = true)
        }

        "approval's real-schema constructor resolves level (a label TYPE alias) to String" {
            val outcome = Ap242V1CodeGen.generate()

            val approval = outcome.fileSpec.typeSpecs.single { it.name == "Approval" }
            val levelParameter = approval.primaryConstructor!!.parameters.single { it.name == "level" }
            levelParameter.type shouldBe STRING
        }

        "the emitted file also contains support entities so Approval/PersonAndOrganization are self-contained" {
            val outcome = Ap242V1CodeGen.generate()

            outcome.fileSpec.typeSpecs.map { it.name } shouldContainExactlyInAnyOrder
                listOf(
                    "Product",
                    "ProductDefinition",
                    "ProductDefinitionFormation",
                    "Approval",
                    "PersonAndOrganization",
                    "ApprovalStatus",
                    "Person",
                    "Organization",
                )
        }
    })
