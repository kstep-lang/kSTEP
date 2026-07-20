package dev.kstep.tests

import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.semantic.ExpressDerivedAttribute
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.validation.DerivedAttributeEvaluator
import dev.kstep.express.validation.UnsupportedWhereExpressionException
import dev.kstep.express.validation.WhereRuleEvaluationException
import dev.kstep.express.validation.WhereRuleValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DerivedAttributeEvaluatorTest :
    StringSpec({
        "product_definition's real DERIVE (a function call) correctly throws UnsupportedWhereExpressionException" {
            val schema = Ap242V1CodeGen.loadSchema()
            val productDefinition = schema.entities.single { it.name == "product_definition" }
            val derived = productDefinition.derivedAttributes.single()

            shouldThrow<UnsupportedWhereExpressionException> {
                DerivedAttributeEvaluator.evaluate(derived, emptyMap())
            }
        }

        "person_and_organization's two real DERIVE attributes (both function calls) correctly throw" {
            val schema = Ap242V1CodeGen.loadSchema()
            val personAndOrganization = schema.entities.single { it.name == "person_and_organization" }
            personAndOrganization.derivedAttributes.forEach { derived ->
                shouldThrow<UnsupportedWhereExpressionException> {
                    DerivedAttributeEvaluator.evaluate(derived, emptyMap())
                }
            }
        }

        "next_assembly_usage_occurrence's real DERIVE (a two-hop SELF chain) correctly throws" {
            val schema = Ap242V1CodeGen.loadSchema()
            val nauo = schema.entities.single { it.name == "next_assembly_usage_occurrence" }
            val derived = nauo.derivedAttributes.single()

            derived.shouldBeInstanceOf<ExpressDerivedAttribute.Explicit>().expressionText shouldBe
                "SELF\\product_definition_relationship.related_product_definition" +
                "\\product_definition_occurrence.id"

            shouldThrow<UnsupportedWhereExpressionException> {
                DerivedAttributeEvaluator.evaluate(derived, emptyMap())
            }
        }

        "a synthetic DERIVE using a SELF.attribute reference (within the supported subset) evaluates correctly" {
            val source =
                """
                SCHEMA derive_eval_synthetic_test;
                ENTITY widget;
                  id : STRING;
                  level : INTEGER;
                DERIVE
                  canonical_id : STRING := SELF.id;
                  doubled_constant : INTEGER := 42;
                  is_positive : BOOLEAN := SELF.level > 0;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val widget = schema.entities.single { it.name == "widget" }
            val derivedByName = widget.derivedAttributes.associateBy { (it as ExpressDerivedAttribute.Explicit).name }

            DerivedAttributeEvaluator.evaluate(
                derivedByName.getValue("canonical_id"),
                mapOf("id" to WhereRuleValue.StringValue("W-1")),
            ) shouldBe WhereRuleValue.StringValue("W-1")

            DerivedAttributeEvaluator.evaluate(
                derivedByName.getValue("doubled_constant"),
                emptyMap(),
            ) shouldBe WhereRuleValue.IntegerValue(42)

            // Proves evaluateToValue doesn't inherit evaluate()'s boolean-only top-level restriction —
            // a comparison-shaped DERIVE evaluates to a WhereRuleValue.BooleanValue just fine.
            DerivedAttributeEvaluator.evaluate(
                derivedByName.getValue("is_positive"),
                mapOf("level" to WhereRuleValue.IntegerValue(5)),
            ) shouldBe WhereRuleValue.BooleanValue(true)
        }

        "a DERIVE expression referencing a missing attribute throws WhereRuleEvaluationException, same as WHERE" {
            shouldThrow<WhereRuleEvaluationException> {
                DerivedAttributeEvaluator.evaluate("SELF.missing", emptyMap())
            }
        }
    })
