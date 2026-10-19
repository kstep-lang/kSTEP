package dev.kstep.tests

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import dev.kstep.express.codegen.CodeGenException
import dev.kstep.express.codegen.CodeGenOptions
import dev.kstep.express.codegen.DefinedTypeReachability
import dev.kstep.express.codegen.DefinedTypeScope
import dev.kstep.express.codegen.ExpressKotlinCodeGenerator
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

private const val PACKAGE_NAME = "dev.kstep.generated.ap242subset"

private fun loadFixture(name: String): String =
    requireNotNull(
        ExpressCodeGenTest::class.java.getResourceAsStream("/$name"),
    ) { "fixture $name not found on test classpath" }
        .bufferedReader()
        .use { it.readText() }

private fun schemaOf(body: String) =
    ExpressSemanticModelBuilder
        .build("SCHEMA select_codegen_test;\n$body\nEND_SCHEMA;")
        .schemas
        .single()

private fun entity(name: String) = "ENTITY $name;\n  v : STRING;\nEND_ENTITY;\n"

private fun generateTypes(
    body: String,
    options: CodeGenOptions = CodeGenOptions(),
) = ExpressKotlinCodeGenerator.generateFile(schemaOf(body), PACKAGE_NAME, options = options).typeSpecs

private fun cls(name: String) = ClassName(PACKAGE_NAME, name)

private fun TypeSpec.superNames() = superinterfaces.keys.toList()

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

        "TYPE aliasing another TYPE resolves transitively to the underlying Kotlin type" {
            val source =
                """
                SCHEMA transitive_type_codegen_test;
                TYPE inner = STRING;
                END_TYPE;
                TYPE middle = inner;
                END_TYPE;
                TYPE outer = middle;
                END_TYPE;
                ENTITY widget;
                  name : outer;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            val widget = fileSpec.typeSpecs.single { it.name == "Widget" }
            val nameParameter = widget.primaryConstructor!!.parameters.single { it.name == "name" }
            nameParameter.type shouldBe STRING
        }

        "TYPE alias is case-insensitive along the chain" {
            val source =
                """
                SCHEMA case_alias_codegen_test;
                TYPE Inner = INTEGER;
                END_TYPE;
                TYPE outer = inner;
                END_TYPE;
                ENTITY widget;
                  n : outer;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            fileSpec.typeSpecs
                .single { it.name == "Widget" }
                .primaryConstructor!!
                .parameters
                .single()
                .type shouldBe INT
        }

        "cyclic TYPE alias chain throws CodeGenException instead of looping" {
            val source =
                """
                SCHEMA cyclic_alias_codegen_test;
                TYPE a = b;
                END_TYPE;
                TYPE b = a;
                END_TYPE;
                ENTITY widget;
                  x : a;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception =
                shouldThrow<CodeGenException> {
                    ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)
                }
            exception.message shouldContain "cyclic"
        }

        "TYPE alias chain ending in an aggregation or an unknown name throws CodeGenException" {
            val aggregation =
                """
                SCHEMA alias_to_aggregation_codegen_test;
                TYPE ids = LIST OF STRING;
                END_TYPE;
                TYPE outer = ids;
                END_TYPE;
                ENTITY widget;
                  x : outer;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            shouldThrow<CodeGenException> {
                ExpressKotlinCodeGenerator.generateFile(
                    ExpressSemanticModelBuilder.build(aggregation).schemas.single(),
                    PACKAGE_NAME,
                )
            }
        }

        "TYPE ENUMERATION generates a Kotlin enum class and attributes reference it" {
            val source =
                """
                SCHEMA enum_type_codegen_test;
                TYPE status = ENUMERATION OF (open, closed);
                END_TYPE;
                TYPE state = status;
                END_TYPE;
                ENTITY widget;
                  current : status;
                  alias_state : state;
                  previous : OPTIONAL status;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val fileSpec = ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)

            val statusEnum = fileSpec.typeSpecs.single { it.name == "Status" }
            statusEnum.isEnum shouldBe true
            statusEnum.enumConstants.keys.toList() shouldContainExactly listOf("OPEN", "CLOSED")

            val statusClass = ClassName(PACKAGE_NAME, "Status")
            val widget = fileSpec.typeSpecs.single { it.name == "Widget" }
            val parameters = widget.primaryConstructor!!.parameters
            parameters.single { it.name == "current" }.type shouldBe statusClass
            parameters.single { it.name == "aliasState" }.type shouldBe statusClass
            parameters.single { it.name == "previous" }.type shouldBe statusClass.copy(nullable = true)
        }

        "ENUMERATION items differing only in case throw CodeGenException" {
            val source =
                """
                SCHEMA enum_collision_codegen_test;
                TYPE status = ENUMERATION OF (open, OPEN);
                END_TYPE;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            shouldThrow<CodeGenException> {
                ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)
            }
        }

        "ENUMERATION class name colliding with an entity class throws CodeGenException" {
            val source =
                """
                SCHEMA enum_entity_collision_codegen_test;
                TYPE widget = ENUMERATION OF (a, b);
                END_TYPE;
                ENTITY widget;
                  x : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            shouldThrow<CodeGenException> {
                ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)
            }
        }

        "EXTENSIBLE ENUMERATION OF (...) is not emitted as a closed enum" {
            val source =
                """
                SCHEMA extensible_enum_items_codegen_test;
                TYPE status = EXTENSIBLE ENUMERATION OF (a, b);
                END_TYPE;
                ENTITY widget;
                  x : status;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            shouldThrow<CodeGenException> {
                ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)
            }
        }

        "two ENUMERATION types with colliding class names throw CodeGenException" {
            val source =
                """
                SCHEMA enum_enum_collision_codegen_test;
                TYPE my_enum = ENUMERATION OF (a);
                END_TYPE;
                TYPE my__enum = ENUMERATION OF (b);
                END_TYPE;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            shouldThrow<CodeGenException> {
                ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)
            }
        }

        "a non-cyclic alias chain longer than the depth limit throws CodeGenException" {
            val types = (1..40).joinToString("\n") { "TYPE t$it = t${it + 1};\nEND_TYPE;" }
            val source =
                """
                SCHEMA long_chain_codegen_test;
                $types
                TYPE t41 = STRING;
                END_TYPE;
                ENTITY widget;
                  x : t1;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception =
                shouldThrow<CodeGenException> {
                    ExpressKotlinCodeGenerator.generateFile(schema, PACKAGE_NAME)
                }
            exception.message shouldContain "over-long"
        }

        "EXTENSIBLE ENUMERATION without an item list stays unsupported" {
            val source =
                """
                SCHEMA extensible_enum_codegen_test;
                TYPE status = EXTENSIBLE ENUMERATION;
                END_TYPE;
                ENTITY widget;
                  x : status;
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
        // ---- SELECT types (ADR-0020) ----

        "SELECT of two entities becomes a sealed interface implemented by both, usable as an attribute type" {
            val types =
                generateTypes(
                    entity("a") + entity("b") +
                        """
                        TYPE pick = SELECT (a, b);
                        END_TYPE;
                        ENTITY holder;
                          one : pick;
                          maybe : OPTIONAL pick;
                          many : SET OF pick;
                        END_ENTITY;
                        """.trimIndent(),
                )

            val pick = types.single { it.name == "Pick" }
            pick.kind shouldBe TypeSpec.Kind.INTERFACE
            (KModifier.SEALED in pick.modifiers) shouldBe true
            types.single { it.name == "A" }.superNames() shouldBe listOf(cls("Pick"))
            types.single { it.name == "B" }.superNames() shouldBe listOf(cls("Pick"))
            val params = types.single { it.name == "Holder" }.primaryConstructor!!.parameters
            params.single { it.name == "one" }.type shouldBe cls("Pick")
            params.single { it.name == "maybe" }.type shouldBe cls("Pick").copy(nullable = true)
            params.single { it.name == "many" }.type.toString() shouldContain "Set<"
            params.single { it.name == "many" }.type.toString() shouldContain "Pick"
        }

        "an entity that is a member of two SELECTs implements both in declaration order" {
            val types =
                generateTypes(
                    entity("a") +
                        """
                        TYPE first = SELECT (a);
                        END_TYPE;
                        TYPE second = SELECT (a);
                        END_TYPE;
                        """.trimIndent(),
                )

            types.single { it.name == "A" }.superNames() shouldBe listOf(cls("First"), cls("Second"))
        }

        "an ENUMERATION member implements the SELECT" {
            val types =
                generateTypes(
                    entity("a") +
                        """
                        TYPE status = ENUMERATION OF (open, closed);
                        END_TYPE;
                        TYPE pick = SELECT (a, status);
                        END_TYPE;
                        """.trimIndent(),
                )

            val status = types.single { it.name == "Status" }
            status.isEnum shouldBe true
            status.superNames() shouldBe listOf(cls("Pick"))
        }

        "alias members get one shared top-level wrapper per TYPE, also through alias chains" {
            val types =
                generateTypes(
                    entity("a") +
                        """
                        TYPE label = STRING;
                        END_TYPE;
                        TYPE chained = inner;
                        END_TYPE;
                        TYPE inner = STRING;
                        END_TYPE;
                        TYPE s1 = SELECT (a, label);
                        END_TYPE;
                        TYPE s2 = SELECT (label, chained);
                        END_TYPE;
                        """.trimIndent(),
                )

            types.count { it.name == "Label" } shouldBe 1
            val label = types.single { it.name == "Label" }
            label.modifiers.contains(KModifier.DATA) shouldBe true
            label.primaryConstructor!!.parameters.single().let {
                it.name shouldBe "value"
                it.type shouldBe STRING
            }
            label.superNames() shouldBe listOf(cls("S1"), cls("S2"))
            val chained = types.single { it.name == "Chained" }
            chained.primaryConstructor!!
                .parameters
                .single()
                .type shouldBe STRING
            chained.superNames() shouldBe listOf(cls("S2"))
        }

        "a nested SELECT extends its parent interface and its entities implement only the inner one" {
            val types =
                generateTypes(
                    entity("e1") + entity("e2") +
                        """
                        TYPE inner = SELECT (e2);
                        END_TYPE;
                        TYPE outer = SELECT (inner, e1);
                        END_TYPE;
                        """.trimIndent(),
                )

            types.single { it.name == "Inner" }.superNames() shouldBe listOf(cls("Outer"))
            types.single { it.name == "E2" }.superNames() shouldBe listOf(cls("Inner"))
            types.single { it.name == "E1" }.superNames() shouldBe listOf(cls("Outer"))
        }

        "a SELECT of an abstract supertype is implemented by its instantiable subtypes" {
            val types =
                generateTypes(
                    """
                    ENTITY base ABSTRACT SUPERTYPE;
                      v : STRING;
                    END_ENTITY;
                    ENTITY child SUBTYPE OF (base);
                      w : STRING;
                    END_ENTITY;
                    TYPE pick = SELECT (base);
                    END_TYPE;
                    """.trimIndent(),
                )

            types.none { it.name == "Base" } shouldBe true
            types.single { it.name == "Child" }.superNames() shouldBe listOf(cls("Pick"))
        }

        "an attribute typed by an alias chain to a SELECT resolves to the sealed interface" {
            val types =
                generateTypes(
                    entity("a") +
                        """
                        TYPE pick = SELECT (a);
                        END_TYPE;
                        TYPE via = pick;
                        END_TYPE;
                        ENTITY holder;
                          x : via;
                        END_ENTITY;
                        """.trimIndent(),
                )

            types
                .single { it.name == "Holder" }
                .primaryConstructor!!
                .parameters
                .single()
                .type shouldBe cls("Pick")
        }

        "internal constructor and ConsistentCopyVisibility apply to wrappers and entities alike" {
            val types =
                generateTypes(
                    entity("a") +
                        """
                        TYPE label = STRING;
                        END_TYPE;
                        TYPE pick = SELECT (a, label);
                        END_TYPE;
                        """.trimIndent(),
                    CodeGenOptions(constructorVisibility = KModifier.INTERNAL, consistentCopyVisibility = true),
                )

            listOf("A", "Label").forEach { name ->
                val type = types.single { it.name == name }
                type.primaryConstructor!!.modifiers shouldBe setOf(KModifier.INTERNAL)
                type.annotations.map { it.typeName.toString() } shouldBe listOf("kotlin.ConsistentCopyVisibility")
            }
        }

        "a SELECT cycle is reported with the repeated link" {
            val exception =
                shouldThrow<CodeGenException> {
                    generateTypes(
                        """
                        TYPE a = SELECT (b);
                        END_TYPE;
                        TYPE b = SELECT (a);
                        END_TYPE;
                        """.trimIndent(),
                    )
                }
            exception.message shouldContain "a -> b -> a"
        }

        "SELECT nesting deeper than the limit reports the depth, not a cycle" {
            val nested = (1..33).joinToString("\n") { "TYPE s$it = SELECT (s${it + 1});\nEND_TYPE;" }
            val exception =
                shouldThrow<CodeGenException> {
                    generateTypes(entity("w") + nested.replace("SELECT (s34)", "SELECT (w)"))
                }
            exception.message shouldContain "MAX_SELECT_NESTING_DEPTH (32)"
            exception.message shouldNotContain "cyclic"
        }

        "a diamond-shaped SELECT DAG is not re-explored exponentially" {
            val levels =
                (1..30).joinToString("\n") { i ->
                    "TYPE l${i}a = SELECT (l${i + 1}a, l${i + 1}b);\nEND_TYPE;\n" +
                        "TYPE l${i}b = SELECT (l${i + 1}a, l${i + 1}b);\nEND_TYPE;"
                }
            val body = entity("w") + levels + "\nTYPE l31a = SELECT (w);\nEND_TYPE;\nTYPE l31b = SELECT (w);\nEND_TYPE;"
            generateTypes(body).any { it.name == "L1a" } shouldBe true
        }

        "the alias cycle message names the repeated link" {
            val exception =
                shouldThrow<CodeGenException> {
                    generateTypes(
                        """
                        TYPE a = b;
                        END_TYPE;
                        TYPE b = a;
                        END_TYPE;
                        ENTITY widget;
                          x : a;
                        END_ENTITY;
                        """.trimIndent(),
                    )
                }
            exception.message shouldContain "a -> b -> a"
        }

        "an alias chain ending in an EXTENSIBLE SELECT names the failing TYPE and the reason" {
            val exception =
                shouldThrow<CodeGenException> {
                    generateTypes(
                        entity("w") +
                            """
                            TYPE ext = EXTENSIBLE SELECT (w);
                            END_TYPE;
                            TYPE al = ext;
                            END_TYPE;
                            ENTITY holder;
                              x : al;
                            END_ENTITY;
                            """.trimIndent(),
                    )
                }
            exception.message shouldContain "'ext'"
            exception.message shouldContain "EXTENSIBLE"
        }

        "attributes typed by EXTENSIBLE or BASED_ON SELECTs throw and no interface is emitted" {
            val cases = mapOf("EXTENSIBLE SELECT (w)" to "EXTENSIBLE", "SELECT BASED_ON base WITH (w)" to "BASED_ON")
            cases.forEach { (decl, reason) ->
                val exception =
                    shouldThrow<CodeGenException> {
                        generateTypes(
                            entity("w") +
                                """
                                TYPE base = SELECT (w);
                                END_TYPE;
                                TYPE bad = $decl;
                                END_TYPE;
                                ENTITY holder;
                                  x : bad;
                                END_ENTITY;
                                """.trimIndent(),
                        )
                    }
                exception.message shouldContain reason
            }
        }

        "unsupported SELECT members throw naming SELECT, member and reason" {
            val cases =
                mapOf(
                    "TYPE m = EXTENSIBLE SELECT (w);\nEND_TYPE;" to "EXTENSIBLE",
                    "TYPE m = LIST OF STRING;\nEND_TYPE;" to "aggregation",
                    "TYPE m = w;\nEND_TYPE;" to "entity",
                )
            cases.forEach { (memberDecl, reason) ->
                val exception =
                    shouldThrow<CodeGenException> {
                        generateTypes(
                            entity("w") + memberDecl + "\nTYPE pick = SELECT (w, m);\nEND_TYPE;",
                        )
                    }
                exception.message shouldContain "'pick'"
                exception.message shouldContain "'m'"
                exception.message!!.lowercase() shouldContain reason.lowercase()
            }
        }

        "a duplicate SELECT member (case-insensitive) throws" {
            shouldThrow<CodeGenException> {
                generateTypes(entity("w") + "TYPE pick = SELECT (w, W);\nEND_TYPE;")
            }
        }

        "an abstract SELECT member without an instantiable subtype has no implementor" {
            val exception =
                shouldThrow<CodeGenException> {
                    generateTypes(
                        """
                        ENTITY base ABSTRACT SUPERTYPE;
                          v : STRING;
                        END_ENTITY;
                        TYPE pick = SELECT (base);
                        END_TYPE;
                        """.trimIndent(),
                    )
                }
            exception.message shouldContain "no emitted Kotlin implementor"
        }

        "class-name collisions between SELECT, ENTITY, ENUMERATION and wrapper name both sides" {
            val selectVsEntity =
                shouldThrow<CodeGenException> {
                    generateTypes(
                        entity("foo_bar") + entity("other") + "TYPE foo__bar = SELECT (other);\nEND_TYPE;",
                    )
                }
            selectVsEntity.message shouldContain "SELECT TYPE 'foo__bar'"
            selectVsEntity.message shouldContain "ENTITY 'foo_bar'"

            val selectVsEnum =
                shouldThrow<CodeGenException> {
                    generateTypes(
                        entity("other") +
                            "TYPE foo_bar = SELECT (other);\nEND_TYPE;\nTYPE foo__bar = ENUMERATION OF (a);\nEND_TYPE;",
                    )
                }
            selectVsEnum.message shouldContain "ENUMERATION TYPE 'foo__bar'"
            selectVsEnum.message shouldContain "SELECT TYPE 'foo_bar'"

            val wrapperVsEntity =
                shouldThrow<CodeGenException> {
                    generateTypes(
                        entity("lab_el") +
                            "TYPE lab__el = STRING;\nEND_TYPE;\nTYPE pick = SELECT (lab_el, lab__el);\nEND_TYPE;",
                    )
                }
            wrapperVsEntity.message shouldContain "wrapper"
            wrapperVsEntity.message shouldContain "ENTITY 'lab_el'"
        }

        "DefinedTypeScope.ALL still emits an unreferenced ENUMERATION" {
            val types =
                generateTypes(
                    entity("a") + "TYPE unused = ENUMERATION OF (x);\nEND_TYPE;",
                    CodeGenOptions(definedTypeScope = DefinedTypeScope.ALL),
                )
            types.any { it.name == "Unused" } shouldBe true
        }

        "REACHABLE scope drops unreferenced TYPEs and keeps those reachable through lists, aliases and SELECTs" {
            val body =
                entity("w") +
                    """
                    TYPE unused = ENUMERATION OF (a);
                    END_TYPE;
                    TYPE unused_sel = SELECT (w);
                    END_TYPE;
                    TYPE st = ENUMERATION OF (x);
                    END_TYPE;
                    TYPE ast = st;
                    END_TYPE;
                    TYPE chosen = SELECT (w, ast);
                    END_TYPE;
                    TYPE alias_to_sel = chosen;
                    END_TYPE;
                    ENTITY holder;
                      xs : LIST OF alias_to_sel;
                    END_ENTITY;
                    """.trimIndent()
            val names =
                generateTypes(body, CodeGenOptions(definedTypeScope = DefinedTypeScope.REACHABLE_FROM_EMITTED_ENTITIES))
                    .map { it.name }

            names.toSet() shouldBe setOf("W", "Holder", "Chosen", "St", "Ast")
        }

        "DefinedTypeReachability terminates on a cyclic alias graph" {
            val schema =
                schemaOf(
                    """
                    TYPE a = b;
                    END_TYPE;
                    TYPE b = a;
                    END_TYPE;
                    ENTITY x;
                      v : a;
                    END_ENTITY;
                    """.trimIndent(),
                )
            val resolved = InheritanceResolver.resolve(schema).values
            DefinedTypeReachability
                .reachableEmittableTypes(resolved, schema.definedTypes.associateBy { it.name.lowercase() })
                .shouldBeEmpty()
        }
    })
