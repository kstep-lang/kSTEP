package dev.kstep.tests

import dev.kstep.express.validation.ComparisonOperator
import dev.kstep.express.validation.WhereRuleEvaluationException
import dev.kstep.express.validation.WhereRuleEvaluator
import dev.kstep.express.validation.WhereRuleExpression
import dev.kstep.express.validation.WhereRuleValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun comparison(
    operator: ComparisonOperator,
    left: WhereRuleExpression,
    right: WhereRuleExpression,
): WhereRuleExpression.Comparison = WhereRuleExpression.Comparison(operator, left, right)

class WhereRuleEvaluatorTest :
    StringSpec({
        "GREATER_THAN evaluates both directions correctly" {
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.IntegerLiteral(5),
                    WhereRuleExpression.IntegerLiteral(3),
                ),
                emptyMap(),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.IntegerLiteral(3),
                    WhereRuleExpression.IntegerLiteral(5),
                ),
                emptyMap(),
            ) shouldBe false
        }

        "GREATER_THAN_OR_EQUAL evaluates both directions correctly" {
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.GREATER_THAN_OR_EQUAL,
                    WhereRuleExpression.IntegerLiteral(5),
                    WhereRuleExpression.IntegerLiteral(5),
                ),
                emptyMap(),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.GREATER_THAN_OR_EQUAL,
                    WhereRuleExpression.IntegerLiteral(4),
                    WhereRuleExpression.IntegerLiteral(5),
                ),
                emptyMap(),
            ) shouldBe false
        }

        "LESS_THAN evaluates both directions correctly" {
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.LESS_THAN,
                    WhereRuleExpression.IntegerLiteral(3),
                    WhereRuleExpression.IntegerLiteral(5),
                ),
                emptyMap(),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.LESS_THAN,
                    WhereRuleExpression.IntegerLiteral(5),
                    WhereRuleExpression.IntegerLiteral(3),
                ),
                emptyMap(),
            ) shouldBe false
        }

        "LESS_THAN_OR_EQUAL evaluates both directions correctly" {
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.LESS_THAN_OR_EQUAL,
                    WhereRuleExpression.IntegerLiteral(5),
                    WhereRuleExpression.IntegerLiteral(5),
                ),
                emptyMap(),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.LESS_THAN_OR_EQUAL,
                    WhereRuleExpression.IntegerLiteral(6),
                    WhereRuleExpression.IntegerLiteral(5),
                ),
                emptyMap(),
            ) shouldBe false
        }

        "EQUAL evaluates both directions correctly" {
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(5),
                    WhereRuleExpression.IntegerLiteral(5),
                ),
                emptyMap(),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(5),
                    WhereRuleExpression.IntegerLiteral(6),
                ),
                emptyMap(),
            ) shouldBe false
        }

        "NOT_EQUAL evaluates both directions correctly" {
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.NOT_EQUAL,
                    WhereRuleExpression.IntegerLiteral(5),
                    WhereRuleExpression.IntegerLiteral(6),
                ),
                emptyMap(),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.NOT_EQUAL,
                    WhereRuleExpression.IntegerLiteral(5),
                    WhereRuleExpression.IntegerLiteral(5),
                ),
                emptyMap(),
            ) shouldBe false
        }

        "AND truth table" {
            val trueExpr =
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(1),
                    WhereRuleExpression.IntegerLiteral(1),
                )
            val falseExpr =
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(1),
                    WhereRuleExpression.IntegerLiteral(2),
                )

            WhereRuleEvaluator.evaluate(WhereRuleExpression.And(trueExpr, trueExpr), emptyMap()) shouldBe true
            WhereRuleEvaluator.evaluate(WhereRuleExpression.And(trueExpr, falseExpr), emptyMap()) shouldBe false
            WhereRuleEvaluator.evaluate(WhereRuleExpression.And(falseExpr, trueExpr), emptyMap()) shouldBe false
            WhereRuleEvaluator.evaluate(WhereRuleExpression.And(falseExpr, falseExpr), emptyMap()) shouldBe false
        }

        "OR truth table" {
            val trueExpr =
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(1),
                    WhereRuleExpression.IntegerLiteral(1),
                )
            val falseExpr =
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(1),
                    WhereRuleExpression.IntegerLiteral(2),
                )

            WhereRuleEvaluator.evaluate(WhereRuleExpression.Or(trueExpr, trueExpr), emptyMap()) shouldBe true
            WhereRuleEvaluator.evaluate(WhereRuleExpression.Or(trueExpr, falseExpr), emptyMap()) shouldBe true
            WhereRuleEvaluator.evaluate(WhereRuleExpression.Or(falseExpr, trueExpr), emptyMap()) shouldBe true
            WhereRuleEvaluator.evaluate(WhereRuleExpression.Or(falseExpr, falseExpr), emptyMap()) shouldBe false
        }

        "NOT truth table" {
            val trueExpr =
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(1),
                    WhereRuleExpression.IntegerLiteral(1),
                )
            val falseExpr =
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(1),
                    WhereRuleExpression.IntegerLiteral(2),
                )

            WhereRuleEvaluator.evaluate(WhereRuleExpression.Not(trueExpr), emptyMap()) shouldBe false
            WhereRuleEvaluator.evaluate(WhereRuleExpression.Not(falseExpr), emptyMap()) shouldBe true
        }

        "INTEGER and REAL values compare numerically against each other" {
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.RealLiteral(0.0),
                ),
                mapOf("level" to WhereRuleValue.IntegerValue(5)),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.IntegerLiteral(2),
                    WhereRuleExpression.RealLiteral(2.0),
                ),
                emptyMap(),
            ) shouldBe true
        }

        "string values compare lexicographically" {
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.LESS_THAN,
                    WhereRuleExpression.StringLiteral("apple"),
                    WhereRuleExpression.StringLiteral("banana"),
                ),
                emptyMap(),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.StringLiteral("apple"),
                    WhereRuleExpression.StringLiteral("banana"),
                ),
                emptyMap(),
            ) shouldBe false
        }

        "a SELF attribute missing from the value bag throws WhereRuleEvaluationException naming the attribute" {
            val exception =
                shouldThrow<WhereRuleEvaluationException> {
                    WhereRuleEvaluator.evaluate(
                        comparison(
                            ComparisonOperator.EQUAL,
                            WhereRuleExpression.SelfAttribute("missing"),
                            WhereRuleExpression.IntegerLiteral(1),
                        ),
                        emptyMap(),
                    )
                }
            exception.message shouldBe "attribute 'missing' referenced by SELF is not present in the supplied value bag"
        }

        "a non-boolean top-level result throws WhereRuleEvaluationException" {
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluate(WhereRuleExpression.IntegerLiteral(5), emptyMap())
            }
        }

        "comparing incompatible value kinds throws WhereRuleEvaluationException" {
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluate(
                    comparison(
                        ComparisonOperator.EQUAL,
                        WhereRuleExpression.StringLiteral("a"),
                        WhereRuleExpression.IntegerLiteral(1),
                    ),
                    emptyMap(),
                )
            }
        }

        "evaluateToValue returns a raw non-boolean value that evaluate() itself would reject" {
            WhereRuleEvaluator.evaluateToValue(
                WhereRuleExpression.SelfAttribute("level"),
                mapOf("level" to WhereRuleValue.IntegerValue(7)),
            ) shouldBe WhereRuleValue.IntegerValue(7)
        }

        // kSTEP M2 Welle 10: EXISTS()/Unset — added specifically for the real person.WR1 rule.
        "EXISTS evaluates true for a present (non-Unset) attribute value, including an empty string" {
            WhereRuleEvaluator.evaluate(
                WhereRuleExpression.Exists("last_name"),
                mapOf("last_name" to WhereRuleValue.StringValue("")),
            ) shouldBe true
        }

        "EXISTS evaluates false for an Unset attribute value" {
            WhereRuleEvaluator.evaluate(
                WhereRuleExpression.Exists("last_name"),
                mapOf("last_name" to WhereRuleValue.Unset),
            ) shouldBe false
        }

        "EXISTS(last_name) OR EXISTS(first_name) — person.WR1 — evaluates correctly for all four combinations" {
            val rule =
                WhereRuleExpression.Or(
                    WhereRuleExpression.Exists("last_name"),
                    WhereRuleExpression.Exists("first_name"),
                )
            WhereRuleEvaluator.evaluate(
                rule,
                mapOf("last_name" to WhereRuleValue.Unset, "first_name" to WhereRuleValue.Unset),
            ) shouldBe false
            WhereRuleEvaluator.evaluate(
                rule,
                mapOf("last_name" to WhereRuleValue.StringValue("Doe"), "first_name" to WhereRuleValue.Unset),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                rule,
                mapOf("last_name" to WhereRuleValue.Unset, "first_name" to WhereRuleValue.StringValue("Jane")),
            ) shouldBe true
            WhereRuleEvaluator.evaluate(
                rule,
                mapOf(
                    "last_name" to WhereRuleValue.StringValue("Doe"),
                    "first_name" to WhereRuleValue.StringValue("Jane"),
                ),
            ) shouldBe true
        }

        "EXISTS on an attribute missing entirely from the value bag throws WhereRuleEvaluationException" {
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluate(WhereRuleExpression.Exists("missing"), emptyMap())
            }
        }

        "an Unset value used directly as a SELF.attribute (outside EXISTS) throws WhereRuleEvaluationException" {
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluate(
                    comparison(
                        ComparisonOperator.EQUAL,
                        WhereRuleExpression.SelfAttribute("last_name"),
                        WhereRuleExpression.StringLiteral(""),
                    ),
                    mapOf("last_name" to WhereRuleValue.Unset),
                )
            }
        }
    })
