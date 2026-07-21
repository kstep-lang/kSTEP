package dev.kstep.tests

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.STRING
import dev.kstep.express.codegen.CodeGenException
import dev.kstep.express.codegen.ExpressKotlinCodeGenerator
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.SemanticModelException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val PACKAGE_NAME = "dev.kstep.generated.ap242subset"

private fun loadFixture(name: String): String =
    requireNotNull(
        ExpressCodeGenTest::class.java.getResourceAsStream("/$name"),
    ) { "fixture $name not found on test classpath" }
        .bufferedReader()
        .use { it.readText() }

class ExpressCodeGenTest :
    StringSpec({
        "generates exactly six data classes with the expected Kotlin class names" {
            val schema = ExpressSemanticModelBuilder.build(loadFixture("ap242-subset.exp")).schemas.single()
            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            fileSpec.typeSpecs.map { it.name } shouldContainExactly
                listOf(
                    "PersonAndOrganization",
                    "Approval",
                    "Product",
                    "ProductDefinitionFormation",
                    "ProductDefinition",
                    "NextAssemblyUsageOccurrence",
                )
        }

        "Product's primary constructor has id, name, description in declaration order" {
            val schema = ExpressSemanticModelBuilder.build(loadFixture("ap242-subset.exp")).schemas.single()
            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            val product = fileSpec.typeSpecs.single { it.name == "Product" }
            val parameters = product.primaryConstructor?.parameters ?: error("Product has no primary constructor")
            parameters shouldHaveSize 3
            parameters.map { it.name } shouldContainExactly listOf("id", "name", "description")
            parameters.forEach { it.type shouldBe STRING }
        }

        "Approval's authorizedBy parameter resolves to the generated PersonAndOrganization class" {
            val schema = ExpressSemanticModelBuilder.build(loadFixture("ap242-subset.exp")).schemas.single()
            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            val approval = fileSpec.typeSpecs.single { it.name == "Approval" }
            val authorizedBy = approval.primaryConstructor!!.parameters.single { it.name == "authorizedBy" }
            authorizedBy.type shouldBe ClassName(PACKAGE_NAME, "PersonAndOrganization")
        }

        "NextAssemblyUsageOccurrence has two distinct ProductDefinition-typed parameters plus referenceDesignator" {
            val schema = ExpressSemanticModelBuilder.build(loadFixture("ap242-subset.exp")).schemas.single()
            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            val nauo = fileSpec.typeSpecs.single { it.name == "NextAssemblyUsageOccurrence" }
            val parameters = nauo.primaryConstructor!!.parameters
            val productDefinitionType = ClassName(PACKAGE_NAME, "ProductDefinition")

            parameters.single { it.name == "relatingProductDefinition" }.type shouldBe productDefinitionType
            parameters.single { it.name == "relatedProductDefinition" }.type shouldBe productDefinitionType
            parameters.single { it.name == "referenceDesignator" }.type shouldBe STRING
        }

        "generateFileSource produces a plain string containing the expected data class and package declarations" {
            val schema = ExpressSemanticModelBuilder.build(loadFixture("ap242-subset.exp")).schemas.single()
            val source = ExpressKotlinCodeGenerator.generateFileSource(schema, PACKAGE_NAME)

            source shouldContain "package dev.kstep.generated.ap242subset"
            source shouldContain "data class Product("
        }

        "OPTIONAL attribute generates a nullable, null-defaulted parameter" {
            val source =
                """
                SCHEMA optional_codegen_test;
                ENTITY widget;
                  id  : STRING;
                  tag : OPTIONAL STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val typeSpec = ExpressKotlinCodeGenerator.generateEntityType(schema.entities.single(), PACKAGE_NAME)
            val tagParameter = typeSpec.primaryConstructor!!.parameters.single { it.name == "tag" }

            tagParameter.type shouldBe STRING.copy(nullable = true)
            tagParameter.defaultValue?.toString() shouldBe "null"
        }

        "generateEntityType(ExpressEntity, ...) throws CodeGenException for a SUBTYPE-bearing entity" {
            val source =
                """
                SCHEMA subtype_codegen_test;
                ENTITY base;
                  id : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val derived = schema.entities.single { it.name == "derived" }

            val exception =
                shouldThrow<CodeGenException> {
                    ExpressKotlinCodeGenerator.generateEntityType(derived, PACKAGE_NAME)
                }
            exception.message shouldContain "SUBTYPE OF"
            exception.message shouldContain "InheritanceResolver"
        }

        "single-supertype entity generates a flattened data class with inherited-then-own params in order" {
            val source =
                """
                SCHEMA inheritance_codegen_test;
                ENTITY base;
                  id    : STRING;
                  label : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val resolvedEntities = InheritanceResolver.resolve(schema)
            val derived = resolvedEntities.getValue("derived")

            val typeSpec = ExpressKotlinCodeGenerator.generateEntityType(derived, PACKAGE_NAME)

            typeSpec.name shouldBe "Derived"
            val parameters = typeSpec.primaryConstructor!!.parameters
            parameters.map { it.name } shouldContainExactly listOf("id", "label", "extra")
            parameters.forEach { it.type shouldBe STRING }
        }

        "generateFile skips an ABSTRACT SUPERTYPE entity entirely -- it contributes attributes but no TypeSpec" {
            val source =
                """
                SCHEMA abstract_codegen_test;
                ENTITY base ABSTRACT SUPERTYPE;
                  id : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            fileSpec.typeSpecs.map { it.name } shouldContainExactly listOf("Derived")
            val parameters =
                fileSpec.typeSpecs
                    .single()
                    .primaryConstructor!!
                    .parameters
            parameters.map { it.name } shouldContainExactly listOf("id", "extra")
        }

        "generateEntityType throws CodeGenException when called directly on an ABSTRACT SUPERTYPE's ResolvedEntity" {
            val source =
                """
                SCHEMA abstract_direct_codegen_test;
                ENTITY base ABSTRACT SUPERTYPE;
                  id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val resolvedEntities = InheritanceResolver.resolve(schema)
            val base = resolvedEntities.getValue("base")

            val exception =
                shouldThrow<CodeGenException> {
                    ExpressKotlinCodeGenerator.generateEntityType(base, PACKAGE_NAME)
                }
            exception.message shouldContain "ABSTRACT SUPERTYPE"
        }

        "an entity with more than one SUBTYPE OF supertype throws SemanticModelException during resolution" {
            val source =
                """
                SCHEMA multiple_supertype_codegen_test;
                ENTITY base_a;
                  a : STRING;
                END_ENTITY;
                ENTITY base_b;
                  b : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base_a, base_b);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            shouldThrow<SemanticModelException> {
                InheritanceResolver.resolve(schema)
            }
        }

        "a redeclared inherited attribute throws SemanticModelException during resolution, not CodeGenException" {
            val source =
                """
                SCHEMA redeclared_inherited_codegen_test;
                ENTITY base;
                  id : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  SELF\base.id RENAMED base_id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            shouldThrow<SemanticModelException> {
                InheritanceResolver.resolve(schema)
            }
        }

        "TYPE-referencing attribute resolves to the underlying Kotlin type when it's a simple STRING alias" {
            val source =
                """
                SCHEMA type_ref_codegen_test;
                TYPE label = STRING;
                END_TYPE;
                ENTITY widget;
                  name : label;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            val widget = fileSpec.typeSpecs.single { it.name == "Widget" }
            val nameParameter = widget.primaryConstructor!!.parameters.single { it.name == "name" }
            nameParameter.type shouldBe STRING
        }

        "TYPE-referencing attribute throws CodeGenException when no defined-type context is supplied" {
            val source =
                """
                SCHEMA type_ref_no_context_codegen_test;
                TYPE label = STRING;
                END_TYPE;
                ENTITY widget;
                  name : label;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val widget = schema.entities.single { it.name == "widget" }

            // generateEntityType's definedTypes parameter defaults to emptyMap(): a caller
            // driving single-entity codegen directly (like every other test in this file) gets
            // a structured CodeGenException instead of an unresolved lookup crashing, even
            // though the SAME attribute resolves fine when generateFile supplies the schema's
            // own definedTypes (see the test above).
            shouldThrow<CodeGenException> {
                ExpressKotlinCodeGenerator.generateEntityType(widget, PACKAGE_NAME)
            }
        }

        "TYPE aliasing another TYPE (one level of indirection) throws CodeGenException, not resolved transitively" {
            val source =
                """
                SCHEMA transitive_type_codegen_test;
                TYPE inner = STRING;
                END_TYPE;
                TYPE outer = inner;
                END_TYPE;
                ENTITY widget;
                  name : outer;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception =
                shouldThrow<CodeGenException> {
                    ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)
                }
            exception.message shouldContain "outer"
        }

        "TYPE aliasing an ENUMERATION throws CodeGenException even with a defined-type context supplied" {
            val source =
                """
                SCHEMA enum_type_codegen_test;
                TYPE status = ENUMERATION OF (open, closed);
                END_TYPE;
                ENTITY widget;
                  state : status;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            shouldThrow<CodeGenException> {
                ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)
            }
        }

        "LOGICAL-typed attribute throws CodeGenException" {
            val source =
                """
                SCHEMA logical_codegen_test;
                ENTITY widget;
                  flag : LOGICAL;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val widget = schema.entities.single()

            shouldThrow<CodeGenException> {
                ExpressKotlinCodeGenerator.generateEntityType(widget, PACKAGE_NAME)
            }
        }

        "attribute names colliding after underscore-collapsing naming conversion throw CodeGenException" {
            val source =
                """
                SCHEMA duplicate_property_codegen_test;
                ENTITY widget;
                  the_name  : STRING;
                  the__name : INTEGER;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val widget = schema.entities.single()

            val exception =
                shouldThrow<CodeGenException> {
                    ExpressKotlinCodeGenerator.generateEntityType(widget, PACKAGE_NAME)
                }
            exception.message shouldContain "theName"
        }

        "zero-attribute entity throws CodeGenException requiring at least one constructor parameter" {
            val source =
                """
                SCHEMA empty_entity_codegen_test;
                ENTITY empty_thing;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val emptyThing = schema.entities.single()

            val exception =
                shouldThrow<CodeGenException> {
                    ExpressKotlinCodeGenerator.generateEntityType(emptyThing, PACKAGE_NAME)
                }
            exception.message shouldContain "at least"
        }
    })
