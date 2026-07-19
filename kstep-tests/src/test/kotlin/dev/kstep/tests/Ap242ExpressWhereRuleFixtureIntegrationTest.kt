package dev.kstep.tests

import dev.kstep.express.semantic.ExpressEntity
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun loadFixture(name: String): String =
    requireNotNull(
        Ap242ExpressWhereRuleFixtureIntegrationTest::class.java.getResourceAsStream("/$name"),
    ) { "fixture $name not found on test classpath" }
        .bufferedReader()
        .use { it.readText() }

private fun loadEntities(): Map<String, ExpressEntity> =
    ExpressSemanticModelBuilder
        .build(loadFixture("ap242-subset.exp"))
        .schemas
        .single()
        .entities
        .associateBy { it.name }

/**
 * End-to-end proof that deliverable 1 (the WHERE-rule evaluator) works against Wave 2's real
 * parser/semantic-model output for the actual ap242-subset fixture — not synthetic strings.
 */
class Ap242ExpressWhereRuleFixtureIntegrationTest :
    StringSpec({
        "person_and_organization's WHERE rule passes when at least one of the two attributes is set" {
            val entity = loadEntities().getValue("person_and_organization")
            val satisfying =
                mapOf(
                    "the_person" to WhereRuleValue.StringValue("Jane Doe"),
                    "the_organization" to WhereRuleValue.StringValue(""),
                )
            val violating =
                mapOf(
                    "the_person" to WhereRuleValue.StringValue(""),
                    "the_organization" to WhereRuleValue.StringValue(""),
                )

            WhereRuleValidator.validate(entity, satisfying).shouldBeEmpty()
            val violations = WhereRuleValidator.validate(entity, violating)
            violations shouldHaveSize 1
            violations.single().ruleLabel shouldBe "wr1"
        }

        "approval's WHERE rule passes for a non-empty level and fails for an empty one" {
            val entity = loadEntities().getValue("approval")
            val satisfying =
                mapOf(
                    "status" to WhereRuleValue.StringValue("approved"),
                    "level" to WhereRuleValue.StringValue("3"),
                )
            val violating =
                mapOf(
                    "status" to WhereRuleValue.StringValue("approved"),
                    "level" to WhereRuleValue.StringValue(""),
                )

            WhereRuleValidator.validate(entity, satisfying).shouldBeEmpty()
            val violations = WhereRuleValidator.validate(entity, violating)
            violations shouldHaveSize 1
            violations.single().entityName shouldBe "approval"
        }

        "product's WHERE rule passes for a non-empty id and fails for an empty one" {
            val entity = loadEntities().getValue("product")
            val satisfying =
                mapOf(
                    "id" to WhereRuleValue.StringValue("BRK-001"),
                    "name" to WhereRuleValue.StringValue("Bracket"),
                    "description" to WhereRuleValue.StringValue("Mounting bracket"),
                )
            val violating = satisfying + ("id" to WhereRuleValue.StringValue(""))

            WhereRuleValidator.validate(entity, satisfying).shouldBeEmpty()
            WhereRuleValidator.validate(entity, violating) shouldHaveSize 1
        }

        "product_definition's WHERE rule passes for a non-empty id and fails for an empty one" {
            val entity = loadEntities().getValue("product_definition")
            val satisfying =
                mapOf(
                    "id" to WhereRuleValue.StringValue("PD-001"),
                    "description" to WhereRuleValue.StringValue("Bracket definition"),
                )
            val violating = satisfying + ("id" to WhereRuleValue.StringValue(""))

            WhereRuleValidator.validate(entity, satisfying).shouldBeEmpty()
            WhereRuleValidator.validate(entity, violating) shouldHaveSize 1
        }

        "next_assembly_usage_occurrence's WHERE rule requires both id and reference_designator to be non-empty" {
            val entity = loadEntities().getValue("next_assembly_usage_occurrence")
            val satisfying =
                mapOf(
                    "id" to WhereRuleValue.StringValue("NAUO-1"),
                    "name" to WhereRuleValue.StringValue("bracket usage"),
                    "reference_designator" to WhereRuleValue.StringValue("A1"),
                )
            val violating =
                mapOf(
                    "id" to WhereRuleValue.StringValue(""),
                    "name" to WhereRuleValue.StringValue("bracket usage"),
                    "reference_designator" to WhereRuleValue.StringValue(""),
                )

            WhereRuleValidator.validate(entity, satisfying).shouldBeEmpty()
            val violations = WhereRuleValidator.validate(entity, violating)
            violations shouldHaveSize 1
            violations.single().expressionText shouldBe "(SELF.id <> '') AND (SELF.reference_designator <> '')"
        }

        "product_definition_formation has no WHERE rule and always validates cleanly" {
            val entity = loadEntities().getValue("product_definition_formation")
            entity.whereRules.shouldBeEmpty()
            WhereRuleValidator.validate(entity, emptyMap()).shouldBeEmpty()
        }
    })
