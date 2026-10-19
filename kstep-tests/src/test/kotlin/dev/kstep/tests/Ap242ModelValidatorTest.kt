package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.core.ap242.Ap242ModelValidator
import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.express.validation.ReverseReferenceIndexLimitExceededException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class Ap242ModelValidatorTest :
    StringSpec({
        fun appCtx() = applicationContext { application = "config control" }.getOrThrow()

        "a complete product model is valid and reports exactly the expected assumptions" {
            val ac = appCtx()
            val pc =
                productContext {
                    name = "e"
                    frameOfReference = ac
                    disciplineType = "m"
                }.getOrThrow()
            val pdc =
                productDefinitionContext {
                    name = "e"
                    frameOfReference = ac
                    lifeCycleStage = "d"
                }.getOrThrow()
            val prod =
                product("P-1") {
                    name = "Bracket"
                    frameOfReference = setOf(pc)
                }.getOrThrow()
            val formation = productDefinitionFormation("P-1-F") { ofProduct = prod }.getOrThrow()
            val definition =
                productDefinition("P-1-D") {
                    this.formation = formation
                    frameOfReference = pdc
                }.getOrThrow()
            val who = person("per-1") { lastName = "Doe" }.getOrThrow()
            val org = organization { name = "ACME" }.getOrThrow()
            val po =
                personAndOrganization {
                    thePerson = who
                    theOrganization = org
                }.getOrThrow()

            val result = Ap242ModelValidator.validate(listOf(definition, po))

            result.isValid shouldBe true
            result.findings.shouldBeEmpty()
            result.instanceCount shouldBe 9
            result.assumptions
                .map { Triple(it.entityName, it.ruleLabel, it.roleText.substringAfter("MIM_LF.")) }
                .toSet() shouldBe
                setOf(
                    Triple("application_context", "WR1", "DESCRIPTION_ATTRIBUTE.DESCRIBED_ITEM"),
                    Triple("application_context", "WR2", "ID_ATTRIBUTE.IDENTIFIED_ITEM"),
                    Triple("product_definition", "WR1", "NAME_ATTRIBUTE.NAMED_ITEM"),
                    Triple("person_and_organization", "WR1", "NAME_ATTRIBUTE.NAMED_ITEM"),
                    Triple("person_and_organization", "WR2", "DESCRIPTION_ATTRIBUTE.DESCRIBED_ITEM"),
                )
            result.assumptions shouldHaveSize 5
        }

        "an application_context nobody refers to violates INVERSE [1:?] with KSTEP-I-001" {
            val result = Ap242ModelValidator.validate(listOf(appCtx()))
            result.isValid shouldBe false
            val finding = result.findings.single()
            finding.entityName shouldBe "application_context"
            val violation = finding.violations.single()
            violation.code shouldBe DslViolationCodes.INVERSE_BOUND_VIOLATED
            violation.message shouldContain "found 0, required [1:?]"
            violation.message shouldContain "application_context_element.frame_of_reference"
        }

        "an application_context referenced by both context kinds is valid" {
            val ac = appCtx()
            val pc =
                productContext {
                    name = "e"
                    frameOfReference = ac
                    disciplineType = "m"
                }.getOrThrow()
            val pdc =
                productDefinitionContext {
                    name = "e"
                    frameOfReference = ac
                    lifeCycleStage = "d"
                }.getOrThrow()
            Ap242ModelValidator.validate(listOf(pc, pdc)).isValid shouldBe true
        }

        "structurally equal application_contexts are separate instances: only the unreferenced one is flagged" {
            val referenced = appCtx()
            val orphan = appCtx()
            (referenced == orphan) shouldBe true
            val pc =
                productContext {
                    name = "e"
                    frameOfReference = referenced
                    disciplineType = "m"
                }.getOrThrow()
            val result = Ap242ModelValidator.validate(listOf(pc, orphan))
            result.findings shouldHaveSize 1
            (result.findings.single().instance === orphan) shouldBe true
        }

        "person's local WHERE rule is not re-reported by the model validator" {
            val result = Ap242ModelValidator.validate(listOf(person("x") { firstName = "A" }.getOrThrow()))
            result.isValid shouldBe true
            result.assumptions.shouldBeEmpty()
            result.instanceCount shouldBe 1
        }

        "a population limit propagates as an exception" {
            val ac = appCtx()
            val pc =
                productContext {
                    name = "e"
                    frameOfReference = ac
                    disciplineType = "m"
                }.getOrThrow()
            shouldThrow<ReverseReferenceIndexLimitExceededException> {
                Ap242ModelValidator.validate(listOf(pc), ReverseReferenceIndex.Limits(maxInstances = 1))
            }
        }

        "an instance of an unknown type propagates as an exception" {
            shouldThrow<dev.kstep.express.validation.UnknownInstanceTypeException> {
                Ap242ModelValidator.validate(listOf("not an entity"))
            }.message shouldNotContain "not an entity"
        }

        "the validator is safe to run concurrently on the same instances" {
            val ac = appCtx()
            val pc =
                productContext {
                    name = "e"
                    frameOfReference = ac
                    disciplineType = "m"
                }.getOrThrow()
            val pool =
                java.util.concurrent.Executors
                    .newFixedThreadPool(8)
            try {
                val futures = (1..8).map { pool.submit<Boolean> { Ap242ModelValidator.validate(listOf(pc)).isValid } }
                futures.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }.toSet() shouldBe setOf(true)
            } finally {
                pool.shutdownNow()
            }
        }
    })
