package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.approvalStatus
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.core.isValid
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.Organization
import dev.kstep.generated.ap242v1.Person
import dev.kstep.generated.ap242v1.PersonAndOrganization
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductContext
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionFormation
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun validApplicationContext(): ApplicationContext =
    applicationContext {
        application = "config control"
    }.getOrThrow()

private fun validProductContext(): ProductContext =
    productContext {
        name = "engineering"
        frameOfReference = validApplicationContext()
        disciplineType = "mechanical"
    }.getOrThrow()

private fun validProductDefinitionContext() =
    productDefinitionContext {
        name = "engineering"
        frameOfReference = validApplicationContext()
        lifeCycleStage = "design"
    }.getOrThrow()

private fun validProduct(): Product =
    product("BRK-001") {
        name = "Bracket"
        description = "Mounting bracket"
        frameOfReference = setOf(validProductContext())
    }.getOrThrow()

private fun validPerson(id: String = "P-001"): Person = person(id) { lastName = "Doe" }.getOrThrow()

private fun validOrganization(): Organization = organization { name = "Acme" }.getOrThrow()

private fun validPersonAndOrganization(): PersonAndOrganization =
    personAndOrganization {
        thePerson = validPerson()
        theOrganization = validOrganization()
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
        frameOfReference = validProductDefinitionContext()
    }.getOrThrow()

class Ap242DslTest :
    StringSpec({
        "applicationContext builds a Valid instance" {
            val result = applicationContext { application = "config control" }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<ApplicationContext>>()
            valid.value.application shouldBe "config control"
        }

        "applicationContext with application never set fails with a missing-mandatory-attribute violation" {
            val result = applicationContext { }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE
        }

        "productContext builds a Valid instance" {
            val ctx = validApplicationContext()
            val result =
                productContext {
                    name = "engineering"
                    frameOfReference = ctx
                    disciplineType = "mechanical"
                }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<ProductContext>>()
            valid.value.frameOfReference shouldBe ctx
        }

        "productContext with frameOfReference never set fails with a missing-mandatory-reference violation" {
            val result =
                productContext {
                    name = "engineering"
                    disciplineType = "mechanical"
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_REFERENCE
        }

        "person builds a Valid instance with only id set — every other attribute is genuinely optional structurally" {
            // WR1 (EXISTS(last_name) OR EXISTS(first_name)) still applies — see the dedicated tests below;
            // this test only proves that no *structural* (missing-mandatory) violation applies to the rest.
            val result = person("P-001") { firstName = "Jane" }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<Person>>()
            valid.value.id shouldBe "P-001"
            valid.value.lastName shouldBe null
            valid.value.middleNames shouldBe null
        }

        "person WR1: at least one of last_name/first_name must be set" {
            val result = person("P-001") { }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            val violation = result.violations.single()
            violation.code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
            violation.ruleLabel shouldBe "WR1"
        }

        "person WR1 is satisfied by firstName alone" {
            person("P-001") { firstName = "Jane" }.isValid() shouldBe true
        }

        "person WR1 is satisfied by lastName alone" {
            person("P-001") { lastName = "Doe" }.isValid() shouldBe true
        }

        "person WR1 is satisfied even by an explicit empty-string lastName — presence, not non-emptiness" {
            // EXISTS() checks whether the attribute was assigned at all, not whether it's non-blank.
            person("P-001") { lastName = "" }.isValid() shouldBe true
        }

        "person defensively copies its list attributes — mutating the caller's list afterward does not affect it" {
            val mutableMiddleNames = mutableListOf("Alice")
            val built =
                person("P-001") {
                    lastName = "Doe"
                    middleNames = mutableMiddleNames
                }.getOrThrow()
            mutableMiddleNames.add("Bob")
            built.middleNames shouldBe listOf("Alice")
        }

        // kSTEP review fix: middle_names/prefix_titles/suffix_titles are each `OPTIONAL LIST
        // [1:?] OF label` — the whole attribute may be absent (null), but an *explicitly
        // assigned empty list* still violates the [1:?] lower bound, exactly like
        // ProductBuilder.frameOfReference's mandatory (non-OPTIONAL) [1:?] SET.
        (
            "person with an explicitly-empty middleNames/prefixTitles/suffixTitles fails with an " +
                "aggregation-bound violation for each, distinct from leaving the attribute null"
        ) {
            val result =
                person("P-001") {
                    lastName = "Doe"
                    middleNames = emptyList()
                    prefixTitles = emptyList()
                    suffixTitles = emptyList()
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 3
            result.violations.forEach { it.code shouldBe DslViolationCodes.AGGREGATION_BOUND_VIOLATED }
        }

        (
            "person with middleNames/prefixTitles/suffixTitles left null (never set) is Valid — OPTIONAL, not " +
                "an aggregation-bound violation"
        ) {
            person("P-001") { lastName = "Doe" }.isValid() shouldBe true
        }

        "organization builds a Valid instance" {
            val result = organization { name = "Acme" }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<Organization>>()
            valid.value.id shouldBe null
        }

        "organization with name never set fails with a missing-mandatory-attribute violation" {
            val result = organization { }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE
        }

        "approvalStatus builds a Valid instance" {
            approvalStatus { name = "approved" }.isValid() shouldBe true
        }

        "product builds a Valid instance with a satisfied WHERE rule and a non-empty frame_of_reference" {
            val ctx = validProductContext()
            val result =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                    frameOfReference = setOf(ctx)
                }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<Product>>()
            valid.value.id shouldBe "BRK-001"
            valid.value.name shouldBe "Bracket"
            valid.value.description shouldBe "Mounting bracket"
            valid.value.frameOfReference shouldBe setOf(ctx)
        }

        "product defensively copies frameOfReference — mutating the caller's set afterward does not affect it" {
            val ctx = validProductContext()
            val mutableSet = mutableSetOf(ctx)
            val built =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = mutableSet
                }.getOrThrow()
            mutableSet.clear()
            built.frameOfReference shouldBe setOf(ctx)
        }

        "product with frameOfReference never set fails with a missing-mandatory-reference violation (KSTEP-M-001)" {
            val result = product("BRK-001") { name = "Bracket" }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_REFERENCE
        }

        "product with an explicitly empty frameOfReference fails KSTEP-A-001, distinct from never-set" {
            val result =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = emptySet()
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.AGGREGATION_BOUND_VIOLATED
        }

        "personAndOrganization builds a Valid instance when both references are set" {
            val person = validPerson()
            val org = validOrganization()
            val result =
                personAndOrganization {
                    thePerson = person
                    theOrganization = org
                }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<PersonAndOrganization>>()
            valid.value.thePerson shouldBe person
            valid.value.theOrganization shouldBe org
        }

        "personAndOrganization with both references unset fails with two missing-mandatory-reference violations" {
            val result = personAndOrganization { }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 2
            result.violations.map { it.code } shouldContainExactlyInAnyOrder
                listOf(DslViolationCodes.MISSING_MANDATORY_REFERENCE, DslViolationCodes.MISSING_MANDATORY_REFERENCE)
        }

        "approval builds a Valid instance when the status reference is set and the level is non-empty" {
            val approvedStatus = approvalStatus { name = "approved" }.getOrThrow()
            val result =
                approval {
                    status = approvedStatus
                    level = "3"
                }
            result.shouldBeInstanceOf<ValidationResult.Valid<*>>()
        }

        "productDefinitionFormation builds a Valid instance when of_product is set" {
            val result =
                productDefinitionFormation("PDF-001") {
                    description = "Bracket formation"
                    ofProduct = validProduct()
                }
            result.shouldBeInstanceOf<ValidationResult.Valid<*>>()
        }

        "productDefinition builds a Valid instance when formation and frame_of_reference are both set" {
            val result =
                productDefinition("PD-001") {
                    description = "Bracket definition"
                    formation = validFormation()
                    frameOfReference = validProductDefinitionContext()
                }
            result.shouldBeInstanceOf<ValidationResult.Valid<*>>()
        }

        "nextAssemblyUsageOccurrence builds a Valid instance when both references are set (no WHERE rule any more)" {
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

        "nextAssemblyUsageOccurrence builds Valid with reference_designator left unset — genuinely OPTIONAL now" {
            val relating = validProductDefinition()
            val related = validProductDefinition()
            val result =
                nextAssemblyUsageOccurrence("NAUO-1") {
                    name = "bracket usage"
                    relatingProductDefinition = relating
                    relatedProductDefinition = related
                }
            val valid = result.shouldBeInstanceOf<ValidationResult.Valid<*>>()
            (valid.value as dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence).referenceDesignator shouldBe null
        }

        "product with an empty id fails its kstep_wr1 WHERE rule (frame_of_reference otherwise valid)" {
            val ctx = validProductContext()
            val result =
                product("") {
                    name = "Bracket"
                    frameOfReference = setOf(ctx)
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 1
            val violation = result.violations.single()
            violation.code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
            violation.ruleLabel shouldBe "kstep_wr1"
        }

        "approval with an empty level fails its kstep_wr1 WHERE rule (status reference is otherwise valid)" {
            val status = approvalStatus { name = "approved" }.getOrThrow()
            val result =
                approval {
                    this.status = status
                    level = ""
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "approval with level never set fails with only a missing-mandatory-attribute violation, no duplicate" {
            val status = approvalStatus { name = "approved" }.getOrThrow()
            val result = approval { this.status = status }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 1
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE
        }

        "productDefinition with an empty id fails its kstep_wr1 WHERE rule (references otherwise valid)" {
            val result =
                productDefinition("") {
                    description = "Bracket definition"
                    formation = validFormation()
                    frameOfReference = validProductDefinitionContext()
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "approval with status never set fails with a missing-mandatory-reference violation" {
            val result = approval { level = "3" }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_REFERENCE
        }

        "productDefinitionFormation with of_product never set fails with a missing-mandatory-reference violation" {
            val result = productDefinitionFormation("PDF-001") { description = "Bracket formation" }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations.single().code shouldBe DslViolationCodes.MISSING_MANDATORY_REFERENCE
        }

        "productDefinition with formation and frame_of_reference unset fails with two M-001 violations" {
            val result = productDefinition("PD-001") { description = "Bracket definition" }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 2
            result.violations.map { it.code } shouldContainExactlyInAnyOrder
                listOf(DslViolationCodes.MISSING_MANDATORY_REFERENCE, DslViolationCodes.MISSING_MANDATORY_REFERENCE)
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

        "product with name never set fails with a missing-mandatory-attribute violation" {
            val ctx = validProductContext()
            val result = product("BRK-001") { frameOfReference = setOf(ctx) }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 1
            val violation = result.violations.single()
            violation.code shouldBe DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE
            violation.entityName shouldBe "product"
        }

        "product with name explicitly set to empty is Valid — presence, not non-emptiness, is enforced" {
            val ctx = validProductContext()
            val result =
                product("BRK-001") {
                    name = ""
                    frameOfReference = setOf(ctx)
                }
            result.shouldBeInstanceOf<ValidationResult.Valid<Product>>()
            result.value.name shouldBe ""
        }

        "nextAssemblyUsageOccurrence with name never set fails with a missing-mandatory-attribute violation" {
            val relating = validProductDefinition()
            val related = validProductDefinition()
            val result =
                nextAssemblyUsageOccurrence("NAUO-1") {
                    relatingProductDefinition = relating
                    relatedProductDefinition = related
                    referenceDesignator = "A1"
                }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 1
            val violation = result.violations.single()
            violation.code shouldBe DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE
            violation.entityName shouldBe "next_assembly_usage_occurrence"
        }

        "nextAssemblyUsageOccurrence with name/both refs all missing collects three violations" {
            val result = nextAssemblyUsageOccurrence("NAUO-1") { }
            result.shouldBeInstanceOf<ValidationResult.Invalid>()
            result.violations shouldHaveSize 3
            result.violations.map { it.code } shouldContainExactlyInAnyOrder
                listOf(
                    DslViolationCodes.MISSING_MANDATORY_REFERENCE,
                    DslViolationCodes.MISSING_MANDATORY_REFERENCE,
                    DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE,
                )
        }

        "ValidationResult.isValid returns true for Valid and false for Invalid" {
            val ctx = validProductContext()
            product("BRK-001") {
                name = "Bracket"
                frameOfReference = setOf(ctx)
            }.isValid() shouldBe true
            product("") {
                name = "Bracket"
                frameOfReference = setOf(ctx)
            }.isValid() shouldBe false
        }

        "ValidationResult.getOrThrow returns the value for Valid and throws for Invalid" {
            val ctx = validProductContext()
            val built =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = setOf(ctx)
                }.getOrThrow()
            built.id shouldBe "BRK-001"
            built.name shouldBe "Bracket"
            built.description shouldBe null
            val exception =
                runCatching {
                    product("") {
                        name = "Bracket"
                        frameOfReference = setOf(ctx)
                    }.getOrThrow()
                }.exceptionOrNull()
            exception.shouldBeInstanceOf<IllegalStateException>()
        }
    })
