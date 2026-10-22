package dev.kstep.tests

import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.validation.AppliedAssumption
import dev.kstep.express.validation.ComparisonOperator
import dev.kstep.express.validation.WhereRuleEvaluationException
import dev.kstep.express.validation.WhereRuleEvaluator
import dev.kstep.express.validation.WhereRuleExpression
import dev.kstep.express.validation.WhereRuleExpressionBuilder
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun expr(
    text: String,
    graph: Boolean = true,
): WhereRuleExpression =
    WhereRuleExpressionBuilder.build(
        ExpressParserFactory.parseExpression(text),
        WhereRuleExpressionBuilder.BuildOptions(allowInstanceGraphFunctions = graph),
    )

private const val PARENT_ROLE = "usedin_test.base_item.parent"

class WhereRuleEvaluatorGraphTest :
    StringSpec({
        "SIZEOF(USEDIN) counts zero, one and two referrers" {
            val rule = expr("SIZEOF(USEDIN(SELF, '$PARENT_ROLE')) <= 1")
            val owner = GNode("owner_e")
            WhereRuleEvaluator.evaluate(rule, emptyMap(), usedInContext(owner), "WR").value shouldBe true

            val one = GNode("item").ref("base_item", "parent", owner)
            WhereRuleEvaluator.evaluate(rule, emptyMap(), usedInContext(owner, one), "WR").value shouldBe true

            val two = GNode("other_item").ref("base_item", "parent", owner)
            WhereRuleEvaluator.evaluate(rule, emptyMap(), usedInContext(owner, one, two), "WR").value shouldBe false
        }

        "the empty role counts every incoming reference" {
            val owner = GNode("owner_e")
            val a = GNode("item").ref("base_item", "parent", owner)
            val b = GNode("other_item").ref("other_attribute", "x", owner)
            WhereRuleEvaluator
                .evaluate(
                    expr("SIZEOF(USEDIN(SELF, '')) = 2"),
                    emptyMap(),
                    usedInContext(owner, a, b),
                ).value shouldBe
                true
        }

        "an unmodelled role is evaluated as the empty set and reported as an assumption" {
            val owner = GNode("owner_e")
            val result =
                WhereRuleEvaluator.evaluate(
                    expr("SIZEOF(USEDIN(SELF, 'ALIAS_SCHEMA.GHOST.THING')) <= 1"),
                    emptyMap(),
                    usedInContext(owner),
                    "WR9",
                )
            result.value shouldBe true
            result.assumptions shouldHaveSize 1
            val assumption: AppliedAssumption = result.assumptions.single()
            assumption.entityName shouldBe "owner_e"
            assumption.ruleLabel shouldBe "WR9"
            assumption.reason shouldContain "not modelled"
        }

        "a typo in a role fails loudly instead of producing an empty set" {
            val owner = GNode("owner_e")
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluate(
                    expr("SIZEOF(USEDIN(SELF, 'ALIAS_SCHEMA.GHOST.THNG')) <= 1"),
                    emptyMap(),
                    usedInContext(owner),
                )
            }
        }

        "USEDIN without a graph context fails loudly and names the role" {
            val e =
                shouldThrow<WhereRuleEvaluationException> {
                    WhereRuleEvaluator.evaluate(expr("SIZEOF(USEDIN(SELF, '$PARENT_ROLE')) <= 1"), emptyMap())
                }
            e.message shouldContain "requires an instance-graph context"
            e.message shouldContain PARENT_ROLE
        }

        "an aggregate cannot be compared or used as a boolean" {
            val owner = GNode("owner_e")
            val usedIn = WhereRuleExpression.UsedIn(WhereRuleExpression.SelfInstance, PARENT_ROLE)
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluate(
                    WhereRuleExpression.Comparison(
                        ComparisonOperator.EQUAL,
                        usedIn,
                        WhereRuleExpression.IntegerLiteral(1),
                    ),
                    emptyMap(),
                    usedInContext(owner),
                )
            }.message shouldContain "aggregate"
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluate(
                    WhereRuleExpression.And(WhereRuleExpression.SizeOf(usedIn), WhereRuleExpression.SizeOf(usedIn)),
                    emptyMap(),
                    usedInContext(owner),
                )
            }
        }

        "SIZEOF of a non-aggregate and a bare SELF instance are evaluation errors" {
            val owner = GNode("owner_e")
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluateToValue(
                    WhereRuleExpression.SizeOf(WhereRuleExpression.IntegerLiteral(1)),
                    emptyMap(),
                    usedInContext(owner),
                )
            }
            shouldThrow<WhereRuleEvaluationException> {
                WhereRuleEvaluator.evaluateToValue(WhereRuleExpression.SelfInstance, emptyMap(), usedInContext(owner))
            }
        }

        "a context whose SELF is not part of the index is rejected" {
            val owner = GNode("owner_e")
            val stranger = GNode("owner_e")
            val index =
                dev.kstep.express.validation.ReverseReferenceIndex
                    .build(listOf(owner), gNodeDescriber)
            shouldThrow<IllegalArgumentException> {
                dev.kstep.express.validation.InstanceEvaluationContext(
                    stranger,
                    "owner_e",
                    index,
                    usedInTestRoleResolver,
                )
            }.message shouldContain "not part of"
        }

        "legacy expressions evaluate identically with no context and with an unrelated context" {
            val values =
                mapOf(
                    "a" to WhereRuleValue.StringValue("x"),
                    "b" to WhereRuleValue.Unset,
                    "n" to WhereRuleValue.IntegerValue(3),
                )
            val owner = GNode("owner_e")
            val context = usedInContext(owner)
            val texts =
                listOf(
                    "n > 2",
                    "n >= 4",
                    "a = 'x'",
                    "a <> 'x'",
                    "EXISTS(b)",
                    "EXISTS(a) AND (n < 10)",
                    "NOT EXISTS(b)",
                    "(n = 3) OR (a = 'y')",
                )
            for (text in texts) {
                val expression = expr(text, graph = false)
                val legacy = WhereRuleEvaluator.evaluate(expression, values)
                WhereRuleEvaluator.evaluate(expression, values, null).value shouldBe legacy
                WhereRuleEvaluator.evaluate(expression, values, context).value shouldBe legacy
                WhereRuleEvaluator.evaluateToValue(expression, values) shouldBe
                    WhereRuleEvaluator.evaluateToValue(expression, values, context)
            }
        }

        "the legacy validator overload still rejects SIZEOF/USEDIN" {
            shouldThrow<dev.kstep.express.validation.UnsupportedWhereExpressionException> {
                WhereRuleValidator.validate(
                    "owner_e",
                    listOf(WhereRuleSpec("WR1", "SIZEOF(USEDIN(SELF, '$PARENT_ROLE')) <= 1")),
                    emptyMap(),
                )
            }
        }

        "the graph validator overload reports violations together with assumptions" {
            val owner = GNode("owner_e")
            val a = GNode("item").ref("base_item", "parent", owner)
            val b = GNode("other_item").ref("base_item", "parent", owner)
            val report =
                WhereRuleValidator.validate(
                    "owner_e",
                    listOf(
                        WhereRuleSpec("WR1", "SIZEOF(USEDIN(SELF, '$PARENT_ROLE')) <= 1", 4),
                        WhereRuleSpec("WR2", "SIZEOF(USEDIN(SELF, 'ALIAS_SCHEMA.GHOST.THING')) <= 1", 5),
                    ),
                    emptyMap(),
                    usedInContext(owner, a, b),
                )
            report.violations.map { it.ruleLabel } shouldBe listOf("WR1")
            report.assumptions.map { it.ruleLabel } shouldBe listOf("WR2")
        }

        "the graph validator overload rejects a rule outside the subset" {
            val owner = GNode("owner_e")
            shouldThrow<dev.kstep.express.validation.UnsupportedWhereExpressionException> {
                WhereRuleValidator.validate(
                    "owner_e",
                    listOf(WhereRuleSpec("WR1", "get_name_value(SELF) = 'x'")),
                    emptyMap(),
                    usedInContext(owner),
                )
            }
        }
    })
