package dev.kstep.tests

import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.ExpressSyntaxException
import dev.kstep.express.validation.ComparisonOperator
import dev.kstep.express.validation.UnsupportedWhereExpressionException
import dev.kstep.express.validation.WhereRuleExpression
import dev.kstep.express.validation.WhereRuleExpressionBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun buildExpr(text: String): WhereRuleExpression =
    WhereRuleExpressionBuilder.build(ExpressParserFactory.parseExpression(text))

class WhereRuleExpressionBuilderTest :
    StringSpec({
        "SELF.attribute reference builds a SelfAttribute node" {
            buildExpr("SELF.level") shouldBe WhereRuleExpression.SelfAttribute("level")
        }

        "each of the six comparison operators builds the matching Comparison node" {
            buildExpr("SELF.level > 0") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(0),
                )
            buildExpr("SELF.level >= 0") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.GREATER_THAN_OR_EQUAL,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(0),
                )
            buildExpr("SELF.level < 0") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.LESS_THAN,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(0),
                )
            buildExpr("SELF.level <= 0") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.LESS_THAN_OR_EQUAL,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(0),
                )
            buildExpr("SELF.level = 0") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(0),
                )
            buildExpr("SELF.level <> 0") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.NOT_EQUAL,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(0),
                )
        }

        "AND combines two parenthesized comparisons into an And node" {
            buildExpr("(SELF.a > 0) AND (SELF.b > 0)") shouldBe
                WhereRuleExpression.And(
                    WhereRuleExpression.Comparison(
                        ComparisonOperator.GREATER_THAN,
                        WhereRuleExpression.SelfAttribute("a"),
                        WhereRuleExpression.IntegerLiteral(0),
                    ),
                    WhereRuleExpression.Comparison(
                        ComparisonOperator.GREATER_THAN,
                        WhereRuleExpression.SelfAttribute("b"),
                        WhereRuleExpression.IntegerLiteral(0),
                    ),
                )
        }

        "OR combines two parenthesized comparisons into an Or node" {
            buildExpr("(SELF.a > 0) OR (SELF.b > 0)") shouldBe
                WhereRuleExpression.Or(
                    WhereRuleExpression.Comparison(
                        ComparisonOperator.GREATER_THAN,
                        WhereRuleExpression.SelfAttribute("a"),
                        WhereRuleExpression.IntegerLiteral(0),
                    ),
                    WhereRuleExpression.Comparison(
                        ComparisonOperator.GREATER_THAN,
                        WhereRuleExpression.SelfAttribute("b"),
                        WhereRuleExpression.IntegerLiteral(0),
                    ),
                )
        }

        "NOT wraps a parenthesized comparison into a Not node" {
            buildExpr("NOT (SELF.a > 0)") shouldBe
                WhereRuleExpression.Not(
                    WhereRuleExpression.Comparison(
                        ComparisonOperator.GREATER_THAN,
                        WhereRuleExpression.SelfAttribute("a"),
                        WhereRuleExpression.IntegerLiteral(0),
                    ),
                )
        }

        "string/integer/real literals build the matching literal node" {
            buildExpr("SELF.a = 'hello'") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.SelfAttribute("a"),
                    WhereRuleExpression.StringLiteral("hello"),
                )
            buildExpr("SELF.a = 3.14") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.SelfAttribute("a"),
                    WhereRuleExpression.RealLiteral(3.14),
                )
            buildExpr("SELF.a = 42") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.EQUAL,
                    WhereRuleExpression.SelfAttribute("a"),
                    WhereRuleExpression.IntegerLiteral(42),
                )
        }

        "a bare attribute reference without the SELF. prefix parses identically to SELF.attr" {
            buildExpr("level > 0") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(0),
                )
        }

        "negative integer and real literals fold via unary minus" {
            buildExpr("SELF.level > -1") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(-1),
                )
            buildExpr("SELF.level > +1") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.IntegerLiteral(1),
                )
            buildExpr("SELF.level > -1.5") shouldBe
                WhereRuleExpression.Comparison(
                    ComparisonOperator.GREATER_THAN,
                    WhereRuleExpression.SelfAttribute("level"),
                    WhereRuleExpression.RealLiteral(-1.5),
                )
        }

        "unary minus on anything other than a directly-nested literal is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("-(SELF.a)") }
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("-(SELF.a + SELF.b)") }
        }

        // Empirically verified (see kSTEP-Welle-3 report): the vendored grammar's non-greedy
        // `SimpleStringLiteral: '\'' .*? '\''` lexer rule does NOT correctly implement EXPRESS's
        // doubled-single-quote escape convention. 'O''Brien' lexes as two adjacent
        // SimpleStringLiteral tokens ('O' then 'Brien'), not one token containing an escaped
        // quote — so re-parsing such text as a standalone expression fails with trailing input,
        // a known grammar limitation rather than an evaluator bug.
        "a doubled-single-quote-escaped string literal fails to re-parse due to a known grammar limitation" {
            shouldThrow<ExpressSyntaxException> { ExpressParserFactory.parseExpression("SELF.name = 'O''Brien'") }
        }

        "EXISTS/SIZEOF and user-defined function calls are unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("EXISTS(SELF.x)") }
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SIZEOF(SELF.tags)") }
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("some_func(SELF.a)") }
        }

        "IN and LIKE relational extensions are unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SELF.a IN [1, 2]") }
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SELF.a LIKE 'x%'") }
        }

        "the instance-equality operators :=: and :<>: are unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SELF.a :=: SELF.b") }
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SELF.a :<>: SELF.b") }
        }

        "arithmetic and set operators (+ - * / DIV MOD || **) are unsupported" {
            listOf(
                "SELF.a + SELF.b",
                "SELF.a - SELF.b",
                "SELF.a * SELF.b",
                "SELF.a / SELF.b",
                "SELF.a DIV SELF.b",
                "SELF.a MOD SELF.b",
                "SELF.a || SELF.b",
                "SELF.a ** SELF.b",
            ).forEach { expression ->
                shouldThrow<UnsupportedWhereExpressionException> { buildExpr(expression) }
            }
        }

        "XOR is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SELF.a XOR SELF.b") }
        }

        "bare SELF with no attribute qualifier is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SELF") }
        }

        "SELF group qualifier (SELF\\entity.attr) is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SELF\\base.attr") }
        }

        "an index qualifier (SELF.a[1]) is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("SELF.a[1]") }
        }

        "built-in constants PI, CONST_E, and ? are unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("PI") }
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("CONST_E") }
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("?") }
        }

        "an aggregate initializer is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("[1, 2, 3]") }
        }

        "an interval expression is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("{1 < x < 10}") }
        }

        "a QUERY expression is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("QUERY(x <* SELF.items | x > 0)") }
        }

        "a binary literal is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("%101") }
        }

        "an encoded string literal is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("\"000000C3\"") }
        }

        "the UNKNOWN logical literal is unsupported" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("UNKNOWN") }
        }

        "TRUE/FALSE logical literals are unsupported (no boolean-literal AST node exists)" {
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("TRUE") }
            shouldThrow<UnsupportedWhereExpressionException> { buildExpr("FALSE") }
        }

        "deeply nested parenthesized expressions throw instead of exhausting the stack" {
            val nestingDepth = 70
            val nested = "(".repeat(nestingDepth) + "SELF.a > 0" + ")".repeat(nestingDepth)

            shouldThrow<UnsupportedWhereExpressionException> { buildExpr(nested) }
        }
    })
