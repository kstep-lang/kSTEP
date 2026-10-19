package dev.kstep.tests

import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import dev.kstep.express.codegen.CodeGenException
import dev.kstep.express.codegen.CodeGenOptions
import dev.kstep.express.codegen.DerivedAttributeMode
import dev.kstep.express.codegen.ExpressKotlinCodeGenerator
import dev.kstep.express.codegen.FileCodeGenResult
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.SemanticModelException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val PACKAGE = "dev.kstep.generated.derivetest"

private val STRICT = CodeGenOptions(derivedAttributeMode = DerivedAttributeMode.STRICT)
private val REPORT = CodeGenOptions(derivedAttributeMode = DerivedAttributeMode.GENERATE_SUPPORTED_REPORT_UNSUPPORTED)

private fun generate(
    body: String,
    options: CodeGenOptions = STRICT,
): FileCodeGenResult {
    val schema = ExpressSemanticModelBuilder.build("SCHEMA derive_test;\n$body\nEND_SCHEMA;").schemas.single()
    return ExpressKotlinCodeGenerator.generateFileWithReport(schema, PACKAGE, options = options)
}

private fun entityWith(
    derive: String,
    attributes: String =
        "  title : STRING;\n  count : INTEGER;\n  weight : REAL;\n  flag : BOOLEAN;\n  note : OPTIONAL STRING;",
) = "ENTITY thing;\n$attributes\nDERIVE\n$derive\nEND_ENTITY;"

private fun TypeSpec.constructorParameterNames() = primaryConstructor!!.parameters.map { it.name }

private fun strictFailure(
    derive: String,
    attributes: String? = null,
): String =
    shouldThrow<CodeGenException> {
        generate(if (attributes == null) entityWith(derive) else entityWith(derive, attributes))
    }.message!!

class DerivedAttributeCodeGenTest :
    StringSpec({
        "supported DERIVEs become getter properties outside the constructor with the declared Kotlin types" {
            val type =
                generate(
                    entityWith(
                        """
                        label_alias : STRING := title;
                        big : BOOLEAN := count > 10;
                        scaled : REAL := count;
                        ratio : REAL := weight;
                        opt_alias : STRING := note;
                        has_note : BOOLEAN := EXISTS(note);
                        """.trimIndent(),
                    ),
                ).fileSpec.typeSpecs.single()

            type.constructorParameterNames() shouldContainExactly listOf("title", "count", "weight", "flag", "note")
            val derived = type.propertySpecs.filter { it.getter != null }
            derived.map { it.name } shouldContainExactly
                listOf("labelAlias", "big", "scaled", "ratio", "optAlias", "hasNote")
            derived.map { it.type.toString() } shouldContainExactly
                listOf(
                    "kotlin.String",
                    "kotlin.Boolean",
                    "kotlin.Double",
                    "kotlin.Double",
                    "kotlin.String?",
                    "kotlin.Boolean",
                )
            // A getter without a backing field: no initializer.
            derived.forEach { it.initializer shouldBe null }
            derived
                .first { it.name == "big" }
                .getter!!
                .body
                .toString() shouldContain "compareTo"
            derived.first { it.name == "big" }.kdoc.toString() shouldContain "DERIVE big := count > 10"
            derived.first { it.name == "big" }.kdoc.toString() shouldContain "not part of equals/hashCode/copy/toString"
        }

        "internal constructor and ConsistentCopyVisibility stay in effect next to derived properties" {
            val options =
                CodeGenOptions(
                    constructorVisibility = KModifier.INTERNAL,
                    consistentCopyVisibility = true,
                    derivedAttributeMode = DerivedAttributeMode.STRICT,
                )
            val source =
                generate(entityWith("big : BOOLEAN := count > 10;"), options)
                    .fileSpec
                    .toString()
            source shouldContain "ConsistentCopyVisibility"
            source shouldContain "internal constructor"
            source shouldContain "val big: Boolean"
        }

        "STRICT rejects a function call and names it" {
            strictFailure("n : STRING := get_name_value(SELF);") shouldContain "get_name_value"
            strictFailure("n : INTEGER := SIZEOF(USEDIN(SELF, 'X.Y.Z'));") shouldContain "SIZEOF"
        }

        "STRICT rejects a multi-hop chain and names the first unsupported link" {
            val message = strictFailure("n : STRING := SELF\\a.b\\c.id;")
            message shouldContain "unsupported link '\\c'"
            message shouldContain "after 'SELF\\a.b'"
        }

        "STRICT rejects an unknown attribute, an unknown qualifier and an attribute missing on the qualifier" {
            strictFailure("n : STRING := missing;") shouldContain "does not resolve"
            strictFailure("n : INTEGER := SELF\\notASupertype.count;") shouldContain "'notASupertype' is neither"
        }

        "a qualifier naming a supertype that does not declare the attribute fails on that link" {
            val body =
                """
                ENTITY base;
                  a : INTEGER;
                END_ENTITY;
                ENTITY mid SUBTYPE OF (base);
                  b : INTEGER;
                END_ENTITY;
                ENTITY leaf SUBTYPE OF (mid);
                DERIVE
                  n : INTEGER := SELF\base.b;
                END_ENTITY;
                """.trimIndent()
            shouldThrow<CodeGenException> { generate(body) }.message!! shouldContain
                "link '.b': 'b' is not an attribute of 'base'"
        }

        "STRICT rejects type conflicts, optional use outside EXISTS, huge integers and opaque comparisons" {
            strictFailure("n : BOOLEAN := title = 1;") shouldContain "type mismatch"
            strictFailure("n : BOOLEAN := note = 'x';") shouldContain "three-valued logic"
            strictFailure("n : BOOLEAN := count > 3000000000;") shouldContain "Int range"
            strictFailure("n : STRING := count;") shouldContain "does not match the declared type"
            shouldThrow<CodeGenException> {
                generate(
                    "ENTITY other;\n  v : STRING;\nEND_ENTITY;\n" +
                        "ENTITY holder;\n  owner : other;\nDERIVE\n  n : BOOLEAN := owner = owner;\nEND_ENTITY;",
                )
            }.message!! shouldContain "type mismatch"
        }

        "a DERIVE named like an explicit attribute is a schema error naming both declarers" {
            shouldThrow<SemanticModelException> {
                generate(entityWith("count : INTEGER := 3;"))
            }.message!! shouldContain "'thing'"
        }

        "RENAMED in a DERIVE redeclaration is rejected by the resolver" {
            val body =
                """
                ENTITY base;
                  a : INTEGER;
                END_ENTITY;
                ENTITY leaf SUBTYPE OF (base);
                DERIVE
                  SELF\base.a RENAMED b : INTEGER := 1;
                END_ENTITY;
                """.trimIndent()
            shouldThrow<SemanticModelException> { generate(body) }.message!! shouldContain "RENAMED"
        }

        "report mode keeps supported DERIVEs and lists unsupported ones in report and KDoc, never as property" {
            val result =
                generate(
                    entityWith(
                        """
                        good : BOOLEAN := count > 1;
                        bad : STRING := get_name_value(SELF);
                        uses_bad : BOOLEAN := bad = 'x';
                        """.trimIndent(),
                    ),
                    REPORT,
                )
            val type = result.fileSpec.typeSpecs.single()
            type.propertySpecs.filter { it.getter != null }.map { it.name } shouldContainExactly listOf("good")
            result.unsupportedDerived.map { it.attribute } shouldContainExactly listOf("bad", "uses_bad")
            result.unsupportedDerived.first { it.attribute == "uses_bad" }.reason shouldContain
                "depends on unsupported DERIVE 'bad'"
            type.kdoc.toString() shouldContain "thing.bad"
            type.kdoc.toString() shouldContain "get_name_value"
        }

        "report mode still rejects an unsupported redeclaration of an inherited explicit attribute" {
            val body =
                """
                ENTITY base;
                  a : INTEGER;
                  b : INTEGER;
                END_ENTITY;
                ENTITY leaf SUBTYPE OF (base);
                DERIVE
                  SELF\base.b : INTEGER := get_b_value(SELF);
                END_ENTITY;
                """.trimIndent()
            shouldThrow<CodeGenException> { generate(body, REPORT) }.message!! shouldContain "get_b_value"
        }

        "an inherited explicit attribute redeclared as DERIVE leaves the constructor but keeps its Part 21 position" {
            val body =
                """
                ENTITY base;
                  a : INTEGER;
                  b : INTEGER;
                  c : INTEGER;
                END_ENTITY;
                ENTITY leaf SUBTYPE OF (base);
                DERIVE
                  SELF\base.b : INTEGER := 42;
                END_ENTITY;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build("SCHEMA r;\n$body\nEND_SCHEMA;").schemas.single()
            val leaf = InheritanceResolver.resolve(schema).getValue("leaf")
            leaf.flattenedAttributes.map { it.attribute.name } shouldContainExactly listOf("a", "b", "c")
            leaf.flattenedAttributes.map { it.redeclaredAsDerived != null } shouldContainExactly
                listOf(false, true, false)
            val type =
                ExpressKotlinCodeGenerator
                    .generateFileWithReport(schema, PACKAGE, options = STRICT)
                    .fileSpec.typeSpecs
                    .first { it.name == "Leaf" }
            type.constructorParameterNames() shouldContainExactly listOf("a", "c")
            type.propertySpecs
                .first { it.name == "b" }
                .kdoc
                .toString() shouldContain "Part 21 position is '*'"
        }

        "DERIVE cycles are schema errors with the full path, also in report mode" {
            val twoCycle = entityWith("a : BOOLEAN := b;\nb : BOOLEAN := a;")
            shouldThrow<CodeGenException> { generate(twoCycle) }.message!! shouldContain "a -> b -> a"
            shouldThrow<CodeGenException> { generate(twoCycle, REPORT) }.message!! shouldContain "a -> b -> a"
            shouldThrow<CodeGenException> {
                generate(entityWith("a : BOOLEAN := a;"))
            }.message!! shouldContain "a -> a"
            shouldThrow<CodeGenException> {
                generate(entityWith("a : BOOLEAN := b;\nb : BOOLEAN := c;\nc : BOOLEAN := a;"))
            }.message!! shouldContain "a -> b -> c -> a"
        }

        "a DERIVE chain of 32 attributes is accepted, 33 is rejected" {
            fun chain(length: Int): String =
                (1..length).joinToString("\n") { i ->
                    if (i == length) "d$i : BOOLEAN := flag;" else "d$i : BOOLEAN := d${i + 1};"
                }
            generate(
                entityWith(chain(32)),
            ).fileSpec.typeSpecs.single().propertySpecs.count { it.getter != null } shouldBe
                32
            shouldThrow<CodeGenException> { generate(entityWith(chain(33))) }.message!! shouldContain "exceeds 32"
        }

        "more than 256 DERIVE attributes per entity are rejected" {
            val many = (1..257).joinToString("\n") { "d$it : STRING := title;" }
            shouldThrow<CodeGenException> { generate(entityWith(many)) }.message!! shouldContain "257 DERIVE attributes"
        }

        "an oversized initializer is not parsed and is reported" {
            val huge = "t : BOOLEAN := title = '" + "a".repeat(9_000) + "';"
            shouldThrow<CodeGenException> { generate(entityWith(huge)) }.message!! shouldContain "not parsed"
            generate(entityWith(huge), REPORT).unsupportedDerived.single().reason shouldContain "not parsed"
        }

        "deeply nested parentheses end in a structured error, not a StackOverflowError" {
            val nested = "t : BOOLEAN := " + "(".repeat(200) + "count > 1" + ")".repeat(200) + ";"
            shouldThrow<CodeGenException> { generate(entityWith(nested)) }.message!! shouldContain "nesting"
        }

        "string literals are escaped in the generated code and cannot inject Kotlin" {
            val derive = "t : STRING := '\${System.exit(1)}';\nq : STRING := '\"\"\"';"
            val source = generate(entityWith(derive)).fileSpec.toString()
            val codeLines = source.lines().filter { it.trim().startsWith("get() =") }
            codeLines shouldHaveSize 2
            codeLines[0] shouldContain "\${'$'}{System.exit(1)}"
            codeLines[1] shouldContain "\\\"\\\"\\\""
        }

        "comment terminators in DERIVE and WHERE text are neutralized in the KDoc" {
            val body =
                """
                ENTITY thing;
                  title : STRING;
                DERIVE
                  t : BOOLEAN := title = 'a*/b/*c';
                WHERE
                  WR1: title <> 'x*/y';
                END_ENTITY;
                """.trimIndent()
            val type = generate(body).fileSpec.typeSpecs.single()
            val kdoc = type.kdoc.toString() + type.propertySpecs.joinToString { it.kdoc.toString() }
            kdoc shouldNotContain "a*/b"
            kdoc shouldNotContain "x*/y"
            kdoc shouldContain "*&#47;"
            kdoc.contains("*/") shouldBe false
            kdoc.contains("/*") shouldBe false
            // The string literal inside the generated *code* is a normal Kotlin string, comment-neutral.
            type.propertySpecs
                .single { it.name == "t" }
                .getter!!
                .body
                .toString() shouldContain "\"a*/b/*c\""
        }

        "entities without DERIVE report nothing" {
            generate("ENTITY plain;\n  v : STRING;\nEND_ENTITY;", REPORT).unsupportedDerived.shouldBeEmpty()
        }
    })
