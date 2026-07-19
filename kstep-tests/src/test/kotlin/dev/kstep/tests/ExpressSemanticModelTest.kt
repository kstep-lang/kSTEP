package dev.kstep.tests

import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.semantic.AggregationKind
import dev.kstep.express.semantic.AggregationType
import dev.kstep.express.semantic.BinaryType
import dev.kstep.express.semantic.BooleanType
import dev.kstep.express.semantic.DefinedTypeRef
import dev.kstep.express.semantic.EntityTypeRef
import dev.kstep.express.semantic.ExpressAttribute
import dev.kstep.express.semantic.ExpressDerivedAttribute
import dev.kstep.express.semantic.ExpressInverseAttribute
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.IntegerType
import dev.kstep.express.semantic.InverseAggregationKind
import dev.kstep.express.semantic.NumberType
import dev.kstep.express.semantic.RealType
import dev.kstep.express.semantic.SemanticModelException
import dev.kstep.express.semantic.StringType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

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
                    "level" to StringType(widthText = null, fixed = false),
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
                it.expressionText shouldBe "SELF.level <> ''"
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

            schema.entities.forEach { entity ->
                entity.derivedAttributes shouldBe emptyList()
                entity.inverseAttributes shouldBe emptyList()
                entity.uniqueRules shouldBe emptyList()
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

        "TYPE aliasing each simple type captures its matching underlyingSimpleType" {
            val source =
                """
                SCHEMA simple_alias_test;
                TYPE a_string = STRING;
                END_TYPE;
                TYPE an_integer = INTEGER;
                END_TYPE;
                TYPE a_real = REAL;
                END_TYPE;
                TYPE a_boolean = BOOLEAN;
                END_TYPE;
                TYPE a_number = NUMBER;
                END_TYPE;
                TYPE a_binary = BINARY;
                END_TYPE;
                ENTITY widget;
                  id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val definedTypesByName = schema.definedTypes.associateBy { it.name }

            definedTypesByName.getValue("a_string").underlyingSimpleType shouldBe
                StringType(widthText = null, fixed = false)
            definedTypesByName.getValue("an_integer").underlyingSimpleType shouldBe IntegerType
            definedTypesByName.getValue("a_real").underlyingSimpleType shouldBe RealType(precisionText = null)
            definedTypesByName.getValue("a_boolean").underlyingSimpleType shouldBe BooleanType
            definedTypesByName.getValue("a_number").underlyingSimpleType shouldBe NumberType
            definedTypesByName.getValue("a_binary").underlyingSimpleType shouldBe
                BinaryType(widthText = null, fixed = false)
        }

        "TYPE aliasing another TYPE (one level of indirection) captures a null underlyingSimpleType" {
            val source =
                """
                SCHEMA transitive_alias_test;
                TYPE inner = STRING;
                END_TYPE;
                TYPE outer = inner;
                END_TYPE;
                ENTITY widget;
                  id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            schema.definedTypes.single { it.name == "inner" }.underlyingSimpleType shouldBe
                StringType(widthText = null, fixed = false)
            schema.definedTypes.single { it.name == "outer" }.underlyingSimpleType shouldBe null
        }

        "TYPE aliasing SET OF STRING (an aggregation) captures a null underlyingSimpleType" {
            val source =
                """
                SCHEMA aggregation_alias_test;
                TYPE tags = SET OF STRING;
                END_TYPE;
                ENTITY widget;
                  id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            schema.definedTypes.single { it.name == "tags" }.underlyingSimpleType shouldBe null
        }

        "TYPE aliasing an ENUMERATION captures a null underlyingSimpleType" {
            val source =
                """
                SCHEMA enumeration_alias_test;
                TYPE status = ENUMERATION OF (open, closed);
                END_TYPE;
                ENTITY widget;
                  id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            schema.definedTypes.single { it.name == "status" }.underlyingSimpleType shouldBe null
        }

        "TYPE aliasing a SELECT captures a null underlyingSimpleType" {
            val source =
                """
                SCHEMA select_alias_test;
                ENTITY widget;
                  id : STRING;
                END_ENTITY;
                TYPE thing = SELECT (widget);
                END_TYPE;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            schema.definedTypes.single { it.name == "thing" }.underlyingSimpleType shouldBe null
        }

        "real AP242 ground truth captures DERIVE and UNIQUE clauses without disturbing WHERE-rule capture" {
            val schema = Ap242V1CodeGen.loadSchema()
            val entitiesByName = schema.entities.associateBy { it.name }

            val productDefinition = entitiesByName.getValue("product_definition")
            productDefinition.derivedAttributes shouldHaveSize 1
            productDefinition.derivedAttributes.single().shouldBeInstanceOf<ExpressDerivedAttribute.Explicit>().let {
                it.name shouldBe "name"
                it.declaredType shouldBe DefinedTypeRef("label")
                it.expressionText shouldBe "get_name_value(SELF)"
            }
            productDefinition.uniqueRules shouldBe emptyList()
            productDefinition.whereRules.single().label shouldBe "WR1"

            val productDefinitionFormation = entitiesByName.getValue("product_definition_formation")
            productDefinitionFormation.derivedAttributes shouldBe emptyList()
            productDefinitionFormation.uniqueRules shouldHaveSize 1
            productDefinitionFormation.uniqueRules.single().let {
                it.label shouldBe "UR1"
                it.referencedAttributes shouldContainExactly listOf("id", "of_product")
            }

            val nauo = entitiesByName.getValue("next_assembly_usage_occurrence")
            nauo.derivedAttributes.single().shouldBeInstanceOf<ExpressDerivedAttribute.Explicit>().let {
                it.name shouldBe "product_definition_occurrence_id"
                it.declaredType shouldBe DefinedTypeRef("identifier")
                it.expressionText shouldBe
                    "SELF\\product_definition_relationship.related_product_definition" +
                    "\\product_definition_occurrence.id"
            }
            nauo.uniqueRules shouldHaveSize 2
            nauo.uniqueRules[0].label shouldBe "UR1"
            nauo.uniqueRules[0].referencedAttributes shouldContainExactly
                listOf(
                    "SELF\\assembly_component_usage.reference_designator",
                    "SELF\\product_definition_relationship.relating_product_definition",
                )
            nauo.uniqueRules[1].label shouldBe "UR2"
            nauo.uniqueRules[1].referencedAttributes shouldContainExactly
                listOf(
                    "product_definition_occurrence_id",
                    "SELF\\product_definition_relationship.relating_product_definition",
                )
            nauo.inverseAttributes shouldBe emptyList()

            val personAndOrganization = entitiesByName.getValue("person_and_organization")
            personAndOrganization.derivedAttributes shouldHaveSize 2
            personAndOrganization.derivedAttributes[0].shouldBeInstanceOf<ExpressDerivedAttribute.Explicit>().let {
                it.name shouldBe "name"
                it.declaredType shouldBe DefinedTypeRef("label")
                it.expressionText shouldBe "get_name_value(SELF)"
            }
            personAndOrganization.derivedAttributes[1].shouldBeInstanceOf<ExpressDerivedAttribute.Explicit>().let {
                it.name shouldBe "description"
                it.declaredType shouldBe DefinedTypeRef("text")
                it.expressionText shouldBe "get_description_value(SELF)"
            }
            personAndOrganization.whereRules shouldHaveSize 2
            personAndOrganization.whereRules[0].label shouldBe "WR1"
            personAndOrganization.whereRules[1].label shouldBe "WR2"
        }

        "INVERSE clause is captured structurally, including a SET-of-bounded and an entityRef-qualified FOR form" {
            val source =
                """
                SCHEMA inverse_test;
                ENTITY gadget;
                  owner : widget;
                END_ENTITY;
                ENTITY widget;
                  id : STRING;
                INVERSE
                  owned_gadgets : SET [0:?] OF gadget FOR owner;
                  primary_gadget : gadget FOR gadget.owner;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val widget = schema.entities.single { it.name == "widget" }
            widget.inverseAttributes shouldHaveSize 2

            widget.inverseAttributes[0].shouldBeInstanceOf<ExpressInverseAttribute.Explicit>().let {
                it.name shouldBe "owned_gadgets"
                it.kind shouldBe InverseAggregationKind.SET
                it.boundsRawText shouldBe "[0:?]"
                it.targetEntity shouldBe "gadget"
                it.forEntity shouldBe null
                it.forAttribute shouldBe "owner"
            }
            widget.inverseAttributes[1].shouldBeInstanceOf<ExpressInverseAttribute.Explicit>().let {
                it.name shouldBe "primary_gadget"
                it.kind shouldBe null
                it.boundsRawText shouldBe null
                it.targetEntity shouldBe "gadget"
                it.forEntity shouldBe "gadget"
                it.forAttribute shouldBe "owner"
            }

            val gadget = schema.entities.single { it.name == "gadget" }
            gadget.inverseAttributes shouldBe emptyList()
            gadget.derivedAttributes shouldBe emptyList()
            gadget.uniqueRules shouldBe emptyList()
        }

        "DERIVE attribute using a redeclared (SELF\\entity.attr) name is captured as Redeclared, not thrown" {
            val source =
                """
                SCHEMA derive_redeclared_test;
                ENTITY base;
                  level : INTEGER;
                DERIVE
                  computed : INTEGER := 1;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                DERIVE
                  SELF\base.computed : INTEGER := 2;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val base = schema.entities.single { it.name == "base" }
            base.derivedAttributes.single().shouldBeInstanceOf<ExpressDerivedAttribute.Explicit>().let {
                it.name shouldBe "computed"
                it.declaredType shouldBe IntegerType
                it.expressionText shouldBe "1"
            }

            val derived = schema.entities.single { it.name == "derived" }
            derived.derivedAttributes.single().shouldBeInstanceOf<ExpressDerivedAttribute.Redeclared>().let {
                it.rawText shouldBe "SELF\\base.computed"
                it.declaredType shouldBe IntegerType
                it.expressionText shouldBe "2"
            }
        }

        "INVERSE attribute using a redeclared (SELF\\entity.attr) name is captured as Redeclared, not thrown" {
            val source =
                """
                SCHEMA inverse_redeclared_test;
                ENTITY gadget;
                  owner : widget;
                END_ENTITY;
                ENTITY widget;
                  id : STRING;
                INVERSE
                  owned_gadgets : SET OF gadget FOR owner;
                END_ENTITY;
                ENTITY special_widget SUBTYPE OF (widget);
                INVERSE
                  SELF\widget.owned_gadgets : SET OF gadget FOR owner;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()

            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val specialWidget = schema.entities.single { it.name == "special_widget" }
            specialWidget.inverseAttributes.single().shouldBeInstanceOf<ExpressInverseAttribute.Redeclared>().let {
                it.rawText shouldBe "SELF\\widget.owned_gadgets"
                it.kind shouldBe InverseAggregationKind.SET
                it.targetEntity shouldBe "gadget"
                it.forAttribute shouldBe "owner"
            }
        }
    })

private fun ExpressAttribute.shouldBeInstanceOfRedeclared() {
    this as? ExpressAttribute.Redeclared
        ?: throw AssertionError("expected ExpressAttribute.Redeclared but was $this")
}
