package dev.kstep.tests

import com.squareup.kotlinpoet.STRING
import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.codegen.CodeGenException
import dev.kstep.express.codegen.CodeGenOptions
import dev.kstep.express.codegen.DerivedAttributeMode
import dev.kstep.express.codegen.ExpressKotlinCodeGenerator
import dev.kstep.express.codegen.FileCodeGenResult
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.validation.SchemaFunctionIntrinsicSet
import dev.kstep.express.validation.UnsupportedWhereExpressionException
import dev.kstep.express.validation.WhereRuleExpression
import dev.kstep.express.validation.WhereRuleExpressionBuilder
import dev.kstep.express.validation.requiresInstanceGraph
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val SCHEMA_ALIAS = "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"
private const val ACYCLIC_FORM =
    "[SELF\\product_definition_relationship.related_product_definition], '$SCHEMA_ALIAS.PRODUCT_DEFINITION_USAGE'"

private val AP242 =
    WhereRuleExpressionBuilder.BuildOptions(
        allowGroupQualifiedSelf = true,
        allowInstanceGraphFunctions = true,
        schemaFunctionIntrinsics = SchemaFunctionIntrinsicSet.AP242,
    )

private fun build(
    text: String,
    options: WhereRuleExpressionBuilder.BuildOptions = AP242,
): WhereRuleExpression = WhereRuleExpressionBuilder.build(ExpressParserFactory.parseExpression(text), options)

private fun unsupported(text: String): String =
    shouldThrow<UnsupportedWhereExpressionException> {
        build(text)
    }.message!!

private const val PACKAGE = "dev.kstep.generated.intrinsictest"
private val WITH_INTRINSICS =
    CodeGenOptions(
        derivedAttributeMode = DerivedAttributeMode.GENERATE_SUPPORTED_REPORT_UNSUPPORTED,
        schemaFunctionIntrinsics = SchemaFunctionIntrinsicSet.AP242,
    )

private fun generate(
    body: String,
    options: CodeGenOptions = WITH_INTRINSICS,
): FileCodeGenResult {
    val schema = ExpressSemanticModelBuilder.build("SCHEMA intrinsic_test;\n$body\nEND_SCHEMA;").schemas.single()
    return ExpressKotlinCodeGenerator.generateFileWithReport(schema, PACKAGE, options = options)
}

private const val SELECT_SCHEMA = """
TYPE target_or_other = SELECT (a_part, b_part);
END_TYPE;
ENTITY a_part;
  id : STRING;
END_ENTITY;
ENTITY b_part;
  id : STRING;
  rank : INTEGER;
END_ENTITY;
ENTITY holder;
  ref : target_or_other;
  maybe : OPTIONAL target_or_other;
DERIVE
  CHAIN_DERIVE
END_ENTITY;
"""

private fun holder(derive: String) = SELECT_SCHEMA.replace("CHAIN_DERIVE", derive)

class SchemaFunctionIntrinsicsTest :
    StringSpec({
        // ------------------------------------------------------------------ expression builder

        "get_*_value(SELF) builds only with the AP242 intrinsics, case-insensitively" {
            build("get_name_value(SELF)") shouldBe WhereRuleExpression.GetAttributeValue("get_name_value")
            build("GET_ID_VALUE(SELF)") shouldBe WhereRuleExpression.GetAttributeValue("get_id_value")
            build("get_description_value(SELF)") shouldBe WhereRuleExpression.GetAttributeValue("get_description_value")
            shouldThrow<UnsupportedWhereExpressionException> {
                build("get_name_value(SELF)", WhereRuleExpressionBuilder.BuildOptions(allowGroupQualifiedSelf = true))
            }.message!! shouldContain "function call or entity constructor"
        }

        "get_*_value rejects every other argument shape and names what is recognised for unknown functions" {
            unsupported("get_name_value(SELF.x)") shouldContain "bare SELF"
            unsupported("get_name_value(SELF, 1)") shouldContain "2 arguments"
            unsupported("get_name_value()") shouldContain "0 arguments"
            unsupported("not_a_function(SELF)") shouldContain "recognised schema functions"
            unsupported("not_a_function(SELF)") shouldContain "get_name_value"
        }

        "the acyclic check builds only in its exact AP242 form" {
            build("acyclic_product_definition_relationship(SELF, $ACYCLIC_FORM)") shouldBe
                WhereRuleExpression.AcyclicCall("$SCHEMA_ALIAS.PRODUCT_DEFINITION_USAGE")
        }

        "every deviation of the acyclic form is unsupported and names the expected form" {
            val deviations =
                listOf(
                    "acyclic_product_definition_relationship(SELF)",
                    "acyclic_product_definition_relationship(SELF.x, $ACYCLIC_FORM)",
                    "acyclic_product_definition_relationship(SELF, SELF, 'X')",
                    "acyclic_product_definition_relationship(SELF, [], 'X.Y')",
                    "acyclic_product_definition_relationship(SELF, [SELF\\product_definition_relationship.related_product_definition, SELF\\product_definition_relationship.related_product_definition], 'X.Y')",
                    "acyclic_product_definition_relationship(SELF, [SELF\\product_definition_relationship.relating_product_definition], 'X.Y')",
                    "acyclic_product_definition_relationship(SELF, [SELF\\other.related_product_definition], 'X.Y')",
                    "acyclic_product_definition_relationship(SELF, [SELF.related_product_definition], 'X.Y')",
                    "acyclic_product_definition_relationship(SELF, [SELF\\product_definition_relationship.related_product_definition], SELF.name)",
                )
            for (deviation in deviations) {
                unsupported(deviation) shouldContain "acyclic_product_definition_relationship"
            }
        }

        "the new nodes need an instance graph" {
            build("get_name_value(SELF)").requiresInstanceGraph() shouldBe true
            build("acyclic_product_definition_relationship(SELF, $ACYCLIC_FORM)").requiresInstanceGraph() shouldBe true
            build("SELF\\a.b\\c.d").requiresInstanceGraph() shouldBe true
        }

        "a chain of several links builds in order and a chain over the limit is unsupported" {
            build("SELF\\a.b\\c.d\\e.f") shouldBe
                WhereRuleExpression.QualifiedChain(
                    listOf(
                        WhereRuleExpression.ChainLink("a", "b"),
                        WhereRuleExpression.ChainLink("c", "d"),
                        WhereRuleExpression.ChainLink("e", "f"),
                    ),
                )
            val tooLong = "SELF" + (1..9).joinToString("") { "\\g$it.a$it" }
            unsupported(tooLong) shouldContain "more than 8 links"
        }

        // ------------------------------------------------------------------ codegen: intrinsics

        "with the AP242 intrinsics a get_name_value DERIVE becomes a member function taking a scope" {
            val result =
                generate(
                    """
                    ENTITY thing;
                      id : STRING;
                    DERIVE
                      n : STRING := get_name_value(SELF);
                    END_ENTITY;
                    """.trimIndent(),
                )
            result.populationDerived.map { "${it.entity}.${it.attribute}" } shouldBe listOf("thing.n")
            val thing = result.fileSpec.typeSpecs.single { it.name == "Thing" }
            val function = thing.funSpecs.single { it.name == "n" }
            function.returnType shouldBe STRING.copy(nullable = true)
            function.toString() shouldContain "getAttributeValue(scope, this, \"name_attribute\", \"named_item\")"
            thing.propertySpecs.map { it.name } shouldBe listOf("id")
        }

        "without the intrinsics the same DERIVE stays an unsupported, reported limitation" {
            val result =
                generate(
                    "ENTITY thing;\n  id : STRING;\nDERIVE\n  n : STRING := get_name_value(SELF);\nEND_ENTITY;",
                    CodeGenOptions(derivedAttributeMode = DerivedAttributeMode.GENERATE_SUPPORTED_REPORT_UNSUPPORTED),
                )
            result.populationDerived.size shouldBe 0
            result.unsupportedDerived.single().reason shouldContain "get_name_value"
        }

        "a get_*_value DERIVE whose declared type is not STRING is unsupported, naming the mismatch" {
            val result =
                generate("ENTITY thing;\n  id : STRING;\nDERIVE\n  n : INTEGER := get_name_value(SELF);\nEND_ENTITY;")
            result.unsupportedDerived.single().reason shouldContain "returns STRING"
        }

        "a DERIVE that depends on a population-dependent DERIVE is unsupported, not silently wrong" {
            val result =
                generate(
                    """
                    ENTITY thing;
                      id : STRING;
                    DERIVE
                      n : STRING := get_name_value(SELF);
                      is_x : BOOLEAN := n = 'x';
                    END_ENTITY;
                    """.trimIndent(),
                )
            result.unsupportedDerived.single { it.attribute == "is_x" }.reason shouldContain "population-dependent"
        }

        "a population-dependent DERIVE may not share its Kotlin name with an attribute of the entity" {
            // Rejected already when the entity is resolved, before any code is generated.
            shouldThrow<dev.kstep.express.semantic.SemanticModelException> {
                generate("ENTITY thing;\n  n : STRING;\nDERIVE\n  n : STRING := get_name_value(SELF);\nEND_ENTITY;")
            }.message!! shouldContain "collides"
        }

        "a Kotlin keyword as DERIVE name is escaped once in the generated function and chain getter" {
            val function =
                generate(
                    "ENTITY thing;\n  id : STRING;\nDERIVE\n  object : STRING := get_name_value(SELF);\nEND_ENTITY;",
                ).fileSpec.typeSpecs
                    .single()
                    .funSpecs
                    .single()
            function.toString() shouldContain "fun `object`(scope:"
            function.toString() shouldNotContain "``"
        }

        "a schema that declares a FUNCTION named like an intrinsic is refused when intrinsics are on" {
            val body =
                """
                FUNCTION get_name_value(v : STRING) : STRING;
                  RETURN ('mine');
                END_FUNCTION;
                ENTITY thing;
                  id : STRING;
                END_ENTITY;
                """.trimIndent()
            shouldThrow<CodeGenException> { generate(body) }.message!! shouldContain "get_name_value"
            // ...and is harmless when intrinsics are off, where no function call is interpreted at all
            generate(body, CodeGenOptions())
                .fileSpec.typeSpecs
                .single()
                .name shouldBe "Thing"
        }

        "text from the schema cannot break out of the generated KDoc or the string literals" {
            val result =
                generate(
                    """
                    ENTITY thing;
                      id : STRING;
                    DERIVE
                      evil : STRING := 'a*/b';
                    END_ENTITY;
                    """.trimIndent(),
                )
            val property =
                result.fileSpec.typeSpecs
                    .single()
                    .propertySpecs
                    .single { it.name == "evil" }
            property.kdoc.toString() shouldContain "a*&#47;b"
            property.kdoc.toString() shouldNotContain "*/"
            // inside the string literal it is plain data and cannot end the (already closed) comment
            property.getter.toString() shouldContain "\"a*/b\""
        }

        // ------------------------------------------------------------------ codegen: SELECT-cast chains

        "a SELECT chain DERIVE becomes a nullable as? getter" {
            val result = generate(holder("b_id : STRING := SELF\\holder.ref\\b_part.id;"))
            result.chainDerived.map { "${it.entity}.${it.attribute}" } shouldBe listOf("holder.b_id")
            val holder = result.fileSpec.typeSpecs.single { it.name == "Holder" }
            val property = holder.propertySpecs.single { it.name == "bId" }
            property.type shouldBe STRING.copy(nullable = true)
            property.getter.toString() shouldContain "(ref as? dev.kstep.generated.intrinsictest.BPart)?.id"
        }

        "an optional first attribute is handled by the same null-safe cast" {
            val result = generate(holder("b_id : STRING := SELF\\holder.maybe\\b_part.id;"))
            val holder = result.fileSpec.typeSpecs.single { it.name == "Holder" }
            holder.propertySpecs
                .single { it.name == "bId" }
                .getter
                .toString() shouldContain
                "(maybe as? dev.kstep.generated.intrinsictest.BPart)?.id"
        }

        "a chain through an entity that the SELECT does not admit is unsupported" {
            val result = generate(holder("x : STRING := SELF\\holder.ref\\holder.ref;"))
            result.unsupportedDerived.single().reason shouldContain "not admitted"
        }

        "a chain whose last attribute does not exist or has another type is unsupported" {
            generate(
                holder("x : STRING := SELF\\holder.ref\\b_part.nope;"),
            ).unsupportedDerived.single().reason shouldContain
                "not an explicit attribute"
            generate(
                holder("x : STRING := SELF\\holder.ref\\b_part.rank;"),
            ).unsupportedDerived.single().reason shouldContain
                "does not match the declared type"
        }
    })
