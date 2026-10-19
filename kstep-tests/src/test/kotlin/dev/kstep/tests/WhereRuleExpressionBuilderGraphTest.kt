package dev.kstep.tests

import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.validation.ComparisonOperator
import dev.kstep.express.validation.UnsupportedWhereExpressionException
import dev.kstep.express.validation.WhereRuleExpression
import dev.kstep.express.validation.WhereRuleExpressionBuilder
import dev.kstep.express.validation.requiresInstanceGraph
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val GRAPH = WhereRuleExpressionBuilder.BuildOptions(allowInstanceGraphFunctions = true)

private fun graphExpr(text: String): WhereRuleExpression =
    WhereRuleExpressionBuilder.build(ExpressParserFactory.parseExpression(text), GRAPH)

private const val ROLE = "S.E.A"

/** Builder behaviour for SIZEOF(USEDIN(SELF, ...)) behind `allowInstanceGraphFunctions` (ADR-0022). */
class WhereRuleExpressionBuilderGraphTest :
    StringSpec({
        "with the flag, SIZEOF(USEDIN(SELF, role)) <= 1 builds the expected AST" {
            graphExpr("SIZEOF(USEDIN(SELF, '$ROLE')) <= 1") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.LESS_THAN_OR_EQUAL,
                    WhereRuleExpression.SizeOf(WhereRuleExpression.UsedIn(WhereRuleExpression.SelfInstance, ROLE)),
                    WhereRuleExpression.IntegerLiteral(1),
                )
        }

        "the empty role string is kept (any role)" {
            graphExpr("SIZEOF(USEDIN(SELF, '')) = 0") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.SizeOf(WhereRuleExpression.UsedIn(WhereRuleExpression.SelfInstance, "")),
                    WhereRuleExpression.IntegerLiteral(0),
                )
        }

        "SIZEOF and USEDIN are lexer keywords, so they are not parsed as entity constructors" {
            // A user-defined function with the same call shape lands in the entityConstructor branch.
            shouldThrow<UnsupportedWhereExpressionException> { graphExpr("get_name_value(SELF)") }.message shouldContain
                "function call or entity constructor"
        }

        "without the flag SIZEOF/USEDIN stay unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> {
                WhereRuleExpressionBuilder.build(
                    ExpressParserFactory.parseExpression("SIZEOF(USEDIN(SELF, '$ROLE')) <= 1"),
                )
            }
        }

        "unsupported shapes stay unsupported even with the flag" {
            val shapes =
                listOf(
                    "SIZEOF(SELF.tags) <= 1",
                    "SIZEOF(USEDIN(SELF.x, '$ROLE')) <= 1",
                    "SIZEOF(USEDIN(SELF, role_attr)) <= 1",
                    "SIZEOF(USEDIN(SELF)) <= 1",
                    "SIZEOF(USEDIN(SELF, '$ROLE', 'b')) <= 1",
                    "SIZEOF(USEDIN(SELF, '$ROLE')[1]) <= 1",
                    "SIZEOF(USEDIN(SELF, 'a' + 'b')) <= 1",
                    "SIZEOF(USEDIN((SELF), '$ROLE')) <= 1",
                    "SELF = 1",
                    "TYPEOF(SELF) = 1",
                    "get_name_value(SELF) = 'x'",
                    "HIBOUND(SELF) = 1",
                )
            for (shape in shapes) {
                shouldThrow<UnsupportedWhereExpressionException> { graphExpr(shape) }
            }
        }

        "QUERY expressions stay unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> {
                graphExpr("SIZEOF(QUERY(x <* SELF.things | x = 1)) = 0")
            }
        }

        "the unsupported message names the concrete argument" {
            shouldThrow<UnsupportedWhereExpressionException> {
                graphExpr(
                    "SIZEOF(SELF.tags) <= 1",
                )
            }.message shouldContain
                "SIZEOF"
        }

        "129 nested SIZEOF levels exceed the nesting limit" {
            val deep = "SIZEOF(".repeat(129) + "USEDIN(SELF, '$ROLE')" + ")".repeat(129) + " = 0"
            shouldThrow<UnsupportedWhereExpressionException> { graphExpr(deep) }
        }

        "requiresInstanceGraph distinguishes graph rules from local ones" {
            graphExpr("SIZEOF(USEDIN(SELF, '$ROLE')) <= 1").requiresInstanceGraph() shouldBe true
            graphExpr("EXISTS(a) OR EXISTS(b)").requiresInstanceGraph() shouldBe false
            graphExpr("NOT (SIZEOF(USEDIN(SELF, '$ROLE')) > 1)").requiresInstanceGraph() shouldBe true
        }
    })
