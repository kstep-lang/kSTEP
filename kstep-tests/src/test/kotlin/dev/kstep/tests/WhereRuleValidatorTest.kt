package dev.kstep.tests

import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class WhereRuleValidatorTest :
    StringSpec({
        "all rules satisfied returns an empty violation list" {
            val rules = listOf(WhereRuleSpec(label = "wr1", expressionText = "SELF.level >= 0"))
            val violations =
                WhereRuleValidator.validate("widget", rules, mapOf("level" to WhereRuleValue.IntegerValue(5)))
            violations.shouldBeEmpty()
        }

        "one failing rule returns a single violation with the correct fields" {
            val rules = listOf(WhereRuleSpec(label = "wr1", expressionText = "SELF.level >= 0", sourceLine = 42))
            val violations =
                WhereRuleValidator.validate("widget", rules, mapOf("level" to WhereRuleValue.IntegerValue(-1)))

            violations shouldHaveSize 1
            val violation = violations.single()
            violation.entityName shouldBe "widget"
            violation.ruleLabel shouldBe "wr1"
            violation.expressionText shouldBe "SELF.level >= 0"
            violation.sourceLine shouldBe 42
        }

        "a mix of passing and failing rules only reports the failing ones" {
            val rules =
                listOf(
                    WhereRuleSpec(label = "wr1", expressionText = "SELF.id <> ''"),
                    WhereRuleSpec(label = "wr2", expressionText = "SELF.level >= 0"),
                )
            val attributeValues =
                mapOf(
                    "id" to WhereRuleValue.StringValue("BRK-001"),
                    "level" to WhereRuleValue.IntegerValue(-5),
                )
            val violations = WhereRuleValidator.validate("widget", rules, attributeValues)

            violations shouldHaveSize 1
            violations.single().ruleLabel shouldBe "wr2"
        }

        "an entity with zero rules returns an empty list without throwing" {
            val violations = WhereRuleValidator.validate("widget", emptyList(), emptyMap())
            violations.shouldBeEmpty()
        }
    })
