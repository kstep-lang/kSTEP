package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.PersonAndOrganization
import dev.kstep.core.ap242.Product
import dev.kstep.core.ap242.ProductDefinition
import dev.kstep.core.ap242.ProductDefinitionFormation
import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.core.isValid
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun validProduct(): Product =
    product("BRK-001") {
        name = "Bracket"
        description = "Mounting bracket"
    }.getOrThrow()

private fun validPersonAndOrganization(): PersonAndOrganization =
    personAndOrganization {
        thePerson = "Jane Doe"
    }.getOrThrow()

private fun validFormation(): ProductDefinitionFormation =
    productDefinitionFormation("PDF-001") {
        description = "Bracket formation"
        ofProduct = validProduct()
    }.getOrThrow()

private fun validProductDefinition(): ProductDefinition =
    productDefinition("PD-001") {
        description = "Bracket definition"
        formation = validFormation()
    }.getOrThrow()

class Ap242DslTest :
    StringSpec({
        "product builds a Valid instance with a satisfied WHERE rule" {
            val result =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<Product>>()
            valid.value.id shouldBe "BRK-001"
            valid.value.name shouldBe "Bracket"
            valid.value.description shouldBe "Mounting bracket"
        }

        "personAndOrganization builds a Valid instance when at least one attribute is set" {
            val result = personAndOrganization { thePerson = "Jane Doe" }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<PersonAndOrganization>>()
            valid.value.thePerson shouldBe "Jane Doe"
            valid.value.theOrganization shouldBe ""
        }

        "approval builds a Valid instance when the reference is set and the level is non-empty" {
            val personAndOrg = validPersonAndOrganization()
            val result =
                approval("approved") {
                    level = "3"
                    authorizedBy = personAndOrg
                }
            result.shouldBeInstanceOf<ValidationResult.Valid<*>>()
        }

        "productDefinitionFormation builds a Valid instance when of_product is set" {
            val result =
                productDefinitionFormation("PDF-001") {
                    description = "Bracket formation"
                    ofProduct =
                        validProduct()
                }
            result.shouldBeInstanceOf<ValidationResult.Valid<*>>()
        }

        "productDefinition builds a Valid instance when formation is set and id is non-empty" {
            val result =
                productDefinition("PD-001") {
                    description = "Bracket definition"
                    formation = validFormation()
                }
            result.shouldBeInstanceOf<ValidationResult.Valid<*>>()
        }

        "nextAssemblyUsageOccurrence builds a Valid instance when both references are set and its WHERE rule holds" {
            val relating = validProductDefinition()
            val related = validProductDefinition()
            val result =
                nextAssemblyUsageOccurrence("NAUO-1") {
                    name = "bracket usage"
                    relatingProductDefinition = relating
                    relatedProductDefinition = related
                    referenceDesignator = "A1"
                }
            result.shouldBeInstanceOf<ValidationResult.Valid<*>>()
        }

        "product with an empty id fails its WHERE rule" {
            val result =
                product("") {
                    name = "Bracket"
                    description = "Mounting bracket"
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 1
            result.violations.single().code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "personAndOrganization with both attributes empty fails its WHERE rule" {
            val result = personAndOrganization {}
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "approval with an empty level fails its WHERE rule (reference is otherwise valid)" {
            val personAndOrg = validPersonAndOrganization()
            val result =
                approval("approved") {
                    level = ""
                    authorizedBy = personAndOrg
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "productDefinition with an empty id fails its WHERE rule (reference is otherwise valid)" {
            val result =
                productDefinition("") {
                    description = "Bracket definition"
                    formation = validFormation()
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "nextAssemblyUsageOccurrence with an empty referenceDesignator fails its WHERE rule (references still valid)" {
            val relating = validProductDefinition()
            val related = validProductDefinition()
            val result =
                nextAssemblyUsageOccurrence("NAUO-1") {
                    name = "bracket usage"
                    relatingProductDefinition = relating
                    relatedProductDefinition = related
                    referenceDesignator = ""
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "approval with authorizedBy never set fails with a missing-mandatory-reference violation" {
            val result = approval("approved") { level = "3" }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 1
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_REFERENCE
        }

        "productDefinitionFormation with of_product never set fails with a missing-mandatory-reference violation" {
            val result = productDefinitionFormation("PDF-001") { description = "Bracket formation" }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_REFERENCE
        }

        "productDefinition with formation never set fails with a missing-mandatory-reference violation" {
            val result = productDefinition("PD-001") { description = "Bracket definition" }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_REFERENCE
        }

        "nextAssemblyUsageOccurrence with both references unset fails with two missing-mandatory-reference violations" {
            val result =
                nextAssemblyUsageOccurrence("NAUO-1") {
                    name = "bracket usage"
                    referenceDesignator = "A1"
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 2
            result.violations.map { it.code } shouldContainExactlyInAnyOrder
                listOf(DslViolationCodes.MISSING_MANDATORY_REFERENCE, DslViolationCodes.MISSING_MANDATORY_REFERENCE)
        }

        "nextAssemblyUsageOccurrence with both refs unset and a failing WHERE rule collects all three violations" {
            val result =
                nextAssemblyUsageOccurrence("") {
                    name = "bracket usage"
                    referenceDesignator = ""
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 3
            result.violations.map { it.code } shouldContainExactlyInAnyOrder
                listOf(
                    DslViolationCodes.MISSING_MANDATORY_REFERENCE,
                    DslViolationCodes.MISSING_MANDATORY_REFERENCE,
                    DslViolationCodes.WHERE_RULE_NOT_SATISFIED,
                )
        }

        "ValidationResult.isValid returns true for Valid and false for Invalid" {
            product("BRK-001") { }.isValid() shouldBe true
            product("") { }.isValid() shouldBe false
        }

        "ValidationResult.getOrThrow returns the value for Valid and throws for Invalid" {
            val built = product("BRK-001") { }.getOrThrow()
            built.id shouldBe "BRK-001"
            built.name shouldBe ""
            built.description shouldBe ""
            val exception = runCatching { product("") { }.getOrThrow() }.exceptionOrNull()
            exception.shouldBeInstanceOf<IllegalStateException>()
        }
    })
