package dev.kstep.tests

import dev.kstep.express.semantic.AggregationKind
import dev.kstep.express.semantic.AggregationType
import dev.kstep.express.semantic.EntityTypeRef
import dev.kstep.express.semantic.ExpressAttribute
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.IntegerType
import dev.kstep.express.semantic.SemanticModelException
import dev.kstep.express.semantic.StringType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun loadFixture(name: String): String =
    requireNotNull(
        ExpressSemanticModelTest::class.java.getResourceAsStream("/$name"),
    ) { "fixture $name not found on test classpath" }
        .bufferedReader()
        .use { it.readText() }

class ExpressSemanticModelTest :
    StringSpec({
        "AP242 subset fixture builds exactly one schema with all six entities and correct attributes" {
            val model = ExpressSemanticModelBuilder.build(loadFixture("ap242-subset.exp"))

            model.schemas shouldHaveSize 1
            val schema = model.schemas.single()
            schema.name shouldBe "ap242_subset"

            val entitiesByName = schema.entities.associateBy { it.name }
            entitiesByName.keys shouldContainExactly
                setOf(
                    "person_and_organization",
                    "approval",
                    "product",
                    "product_definition_formation",
                    "product_definition",
                    "next_assembly_usage_occurrence",
                )

            val personAndOrganization = entitiesByName.getValue("person_and_organization")
            personAndOrganization.attributes shouldHaveSize 2
            (personAndOrganization.attributes[0] as ExpressAttribute.Explicit).let {
                it.name shouldBe "the_person"
                it.declaredType shouldBe StringType(widthText = null, fixed = false)
                it.isOptional shouldBe false
            }
            (personAndOrganization.attributes[1] as ExpressAttribute.Explicit).let {
                it.name shouldBe "the_organization"
                it.declaredType shouldBe StringType(widthText = null, fixed = false)
            }

            val approval = entitiesByName.getValue("approval")
            approval.attributes.map { (it as ExpressAttribute.Explicit).name to it.declaredType } shouldContainExactly
                listOf(
                    "status" to StringType(widthText = null, fixed = false),
                    "level" to IntegerType,
                    "authorized_by" to EntityTypeRef("person_and_organization"),
                )

            val nextAssemblyUsageOccurrence = entitiesByName.getValue("next_assembly_usage_occurrence")
            val relatingAttribute =
                nextAssemblyUsageOccurrence.attributes
                    .filterIsInstance<ExpressAttribute.Explicit>()
                    .single { it.name == "relating_product_definition" }
            val relatedAttribute =
                nextAssemblyUsageOccurrence.attributes
                    .filterIsInstance<ExpressAttribute.Explicit>()
                    .single { it.name == "related_product_definition" }
            relatingAttribute.declaredType shouldBe EntityTypeRef("product_definition")
            relatedAttribute.declaredType shouldBe EntityTypeRef("product_definition")

            schema.entities.forEach { entity ->
                entity.isAbstract shouldBe false
                entity.supertypes shouldBe emptyList()
            }

            // product_definition_formation is the fixture's deliberate "entity legitimately
            // has zero WHERE rules" case; the other five each carry exactly one.
            entitiesByName.getValue("product_definition_formation").whereRules shouldBe emptyList()
            entitiesByName.getValue("person_and_organization").whereRules.single().let {
                it.label shouldBe "wr1"
                it.expressionText shouldBe "NOT ((SELF.the_person = '') AND (SELF.the_organization = ''))"
            }
            entitiesByName.getValue("approval").whereRules.single().let {
                it.label shouldBe "wr1"
                it.expressionText shouldBe "SELF.level >= 0"
            }
            entitiesByName.getValue("product").whereRules.single().let {
                it.label shouldBe "wr1"
                it.expressionText shouldBe "SELF.id <> ''"
            }
            entitiesByName.getValue("product_definition").whereRules.single().let {
                it.label shouldBe "wr1"
                it.expressionText shouldBe "SELF.id <> ''"
            }
            entitiesByName.getValue("next_assembly_usage_occurrence").whereRules.single().let {
                it.label shouldBe "wr1"
                it.expressionText shouldBe "(SELF.id <> '') AND (SELF.reference_designator <> '')"
            }
        }

        "OPTIONAL attribute is marked optional" {
            val source =
                """
                SCHEMA optional_test;
                ENTITY widget;
                  tag : OPTIONAL STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val attribute =
                schema.entities
                    .single()
                    .attributes
                    .single() as ExpressAttribute.Explicit
            attribute.isOptional shouldBe true
        }

        "forward reference to an entity declared later in the same schema resolves without error" {
            val source =
                """
                SCHEMA forward_ref_test;
                ENTITY earlier;
                  later_ref : later;
                END_ENTITY;
                ENTITY later;
                  id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val earlier = schema.entities.first { it.name == "earlier" }
            val attribute = earlier.attributes.single() as ExpressAttribute.Explicit
            attribute.declaredType shouldBe EntityTypeRef("later")
        }

        "unresolvable named type reference throws SemanticModelException naming the offending type" {
            val source =
                """
                SCHEMA unresolvable_test;
                ENTITY widget;
                  gizmo : nonexistent_type;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val exception =
                shouldThrow<SemanticModelException> {
                    ExpressSemanticModelBuilder.build(source)
                }
            exception.message shouldContain "nonexistent_type"
        }

        "ABSTRACT SUPERTYPE and SUBTYPE OF are captured on base and derived entity" {
            val source =
                """
                SCHEMA supertype_test;
                ENTITY base ABSTRACT SUPERTYPE;
                  id : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val base = schema.entities.single { it.name == "base" }
            val derived = schema.entities.single { it.name == "derived" }

            base.isAbstract shouldBe true
            base.supertypes shouldBe emptyList()
            derived.isAbstract shouldBe false
            derived.supertypes shouldBe listOf("base")
        }

        "WHERE rule is captured with exact verbatim expression text" {
            val source =
                """
                SCHEMA where_test;
                ENTITY widget;
                  level : INTEGER;
                WHERE
                  wr1: SELF.level > 0;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val rule =
                schema.entities
                    .single()
                    .whereRules
                    .single()
            rule.label shouldBe "wr1"
            rule.expressionText shouldBe "SELF.level > 0"
        }

        "comma-shared attribute declaration yields one attribute per id, all sharing the type" {
            val source =
                """
                SCHEMA shared_type_test;
                ENTITY widget;
                  a, b, c : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val attributes =
                schema.entities
                    .single()
                    .attributes
                    .filterIsInstance<ExpressAttribute.Explicit>()
            attributes.map { it.name } shouldContainExactly listOf("a", "b", "c")
            attributes.forEach { it.declaredType shouldBe StringType(widthText = null, fixed = false) }
        }

        "redeclared attribute via SELF group qualifier is captured as Redeclared, not Explicit" {
            val source =
                """
                SCHEMA redeclared_test;
                ENTITY base;
                  x : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  SELF\base.x RENAMED y : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val derived = schema.entities.single { it.name == "derived" }
            derived.attributes.single().shouldBeInstanceOfRedeclared()
        }

        "SET OF STRING is captured as an AggregationType" {
            val source =
                """
                SCHEMA aggregation_test;
                ENTITY widget;
                  tags : SET OF STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val attribute =
                schema.entities
                    .single()
                    .attributes
                    .single() as ExpressAttribute.Explicit
            attribute.declaredType shouldBe
                AggregationType(
                    kind = AggregationKind.SET,
                    elementType = StringType(widthText = null, fixed = false),
                    boundsRawText = null,
                    unique = false,
                )
        }

        "deeply nested SET OF SET OF ... throws SemanticModelException instead of stack-overflowing" {
            val nestingDepth = 200
            val nestedType = "SET OF ".repeat(nestingDepth) + "STRING"
            val source =
                """
                SCHEMA deep_nesting_test;
                ENTITY widget;
                  tags : $nestedType;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            shouldThrow<SemanticModelException> {
                ExpressSemanticModelBuilder.build(source)
            }
        }
    })

private fun ExpressAttribute.shouldBeInstanceOfRedeclared() {
    this as? ExpressAttribute.Redeclared
        ?: throw AssertionError("expected ExpressAttribute.Redeclared but was $this")
}
