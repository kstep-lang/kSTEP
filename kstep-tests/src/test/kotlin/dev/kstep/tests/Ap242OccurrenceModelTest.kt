package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.core.ap242.Ap242ModelValidator
import dev.kstep.core.ap242.Ap242Population
import dev.kstep.core.ap242.descriptionAttribute
import dev.kstep.core.ap242.idAttribute
import dev.kstep.core.ap242.nameAttribute
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.validation.DerivedAttributeEvaluator
import dev.kstep.express.validation.IndeterminateDerivedValueException
import dev.kstep.express.validation.InstanceDescriber
import dev.kstep.express.validation.InstanceEvaluationContext
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.express.validation.SchemaFunctionIntrinsicSet
import dev.kstep.express.validation.UsedInRoleResolver
import dev.kstep.express.validation.WhereRuleEvaluationException
import dev.kstep.express.validation.WhereRuleValue
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionOccurrence
import dev.kstep.generated.ap242v1.ProductDefinitionOrReference
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun codesOf(result: dev.kstep.core.ap242.ModelValidationResult) =
    result.findings.flatMap { it.violations }.map { it.code }

private fun describerOrFail(): InstanceDescriber {
    val clazz = Class.forName("dev.kstep.core.ap242.Ap242InstanceDescriber")
    return clazz.getField("INSTANCE").get(null) as InstanceDescriber
}

private const val ALIAS = "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"

class Ap242OccurrenceModelTest :
    StringSpec({
        val schema = Ap242V1CodeGen.loadSchema()
        val resolved = InheritanceResolver.resolve(schema)
        val roleResolver =
            UsedInRoleResolver(
                resolved.values.toList(),
                schema.name,
                setOf(ALIAS),
                mapOf("$ALIAS.CONFIGURATION_DESIGN.DESIGN" to "not modelled"),
            )

        fun contextFor(
            instance: Any,
            roots: List<Any>,
            incomplete: List<Any> = emptyList(),
        ): Pair<InstanceEvaluationContext, WhereRuleValue?> {
            val index = ReverseReferenceIndex.build(roots, describerOrFail())
            val entity = index.entityNameOf(instance)!!
            return InstanceEvaluationContext(instance, entity, index, roleResolver, incomplete) to null
        }

        // ---------------------------------------------------------------- population-dependent derived values

        "a product_definition's derived name is the attribute_value of its single name_attribute, else null" {
            val m = AssemblyTestModel()
            val pd = m.pd("D1")

            Ap242Population.of(listOf(pd)).derivedScope.let { pd.name(it) } shouldBe null

            val one = m.nameOf(pd, "Housing")
            pd.name(Ap242Population.of(listOf(pd, one)).derivedScope) shouldBe "Housing"

            val two = m.nameOf(pd, "Casing")
            pd.name(Ap242Population.of(listOf(pd, one, two)).derivedScope) shouldBe null
        }

        "two name_attributes on one product_definition violate WR1 and the derived name is null" {
            val m = AssemblyTestModel()
            val pd = m.pd("D1")
            val result = Ap242ModelValidator.validate(listOf(m.nameOf(pd, "A"), m.nameOf(pd, "B")))

            val pdFinding = result.findings.single { it.entityName == "product_definition" }
            pdFinding.violations.single().ruleLabel shouldBe "WR1"
            pdFinding.violations.single().code shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "person_and_organization and application_context read their derived values the same way" {
            val m = AssemblyTestModel()
            val po =
                personAndOrganization {
                    thePerson = person("x") { lastName = "Doe" }.getOrThrow()
                    theOrganization = organization { name = "ACME" }.getOrThrow()
                }.getOrThrow()
            val description =
                descriptionAttribute {
                    attributeValue = "Lead engineer"
                    describedItem = po
                }.getOrThrow()
            val poName =
                nameAttribute {
                    attributeValue = "Doe, ACME"
                    namedItem = po
                }.getOrThrow()
            val acId =
                idAttribute {
                    attributeValue = "ctx-7"
                    identifiedItem = m.ac
                }.getOrThrow()
            val acDescription =
                descriptionAttribute {
                    attributeValue = "AP242 context"
                    describedItem = m.ac
                }.getOrThrow()
            val scope = Ap242Population.of(listOf(po, description, poName, acId, acDescription)).derivedScope

            po.name(scope) shouldBe "Doe, ACME"
            po.description(scope) shouldBe "Lead engineer"
            m.ac.id(scope) shouldBe "ctx-7"
            m.ac.description(scope) shouldBe "AP242 context"
        }

        "an incomplete population makes the read throw instead of answering null" {
            val m = AssemblyTestModel()
            val pd = m.pd("D1")
            val population = Ap242Population.of(listOf(pd, m.nameOf(pd, "A")), incomplete = listOf(pd))

            val exception = shouldThrow<IndeterminateDerivedValueException> { pd.name(population.derivedScope) }
            exception.entity shouldBe "name_attribute"
            exception.attribute shouldBe "named_item"
            exception.reason shouldContain "could not be reconstructed"
        }

        "reading a derived value for an instance outside the population is a loud error" {
            val m = AssemblyTestModel()
            val inside = m.pd("IN")
            val outside = m.pd("OUT")
            shouldThrow<WhereRuleEvaluationException> {
                outside.name(Ap242Population.of(listOf(inside)).derivedScope)
            }.message!! shouldContain "not part of the population"
        }

        // ---------------------------------------------------------------- parity: generated code vs evaluator

        "the generated name(scope) agrees with the DERIVE evaluator for zero, one and two name_attributes" {
            val m = AssemblyTestModel()
            val derived = resolved.getValue("product_definition").flattenedDerivedAttributes.single()
            for (count in 0..2) {
                val pd = m.pd("D$count")
                val attributes = (1..count).map { m.nameOf(pd, "v$it") }
                val roots = listOf<Any>(pd) + attributes
                val (context, _) = contextFor(pd, roots)
                val expected =
                    DerivedAttributeEvaluator.evaluate(
                        derived.expressionText,
                        context.index.describe(pd)!!.attributeValues,
                        context,
                        SchemaFunctionIntrinsicSet.AP242,
                    )
                val actual = pd.name(Ap242Population.of(roots).derivedScope)
                when (expected) {
                    WhereRuleValue.Unset -> actual shouldBe null
                    is WhereRuleValue.StringValue -> actual shouldBe expected.value
                    else -> error("unexpected derived value $expected")
                }
            }
        }

        "the evaluator throws the same IndeterminateDerivedValueException as the generated function" {
            val m = AssemblyTestModel()
            val pd = m.pd("D1")
            val derived = resolved.getValue("product_definition").flattenedDerivedAttributes.single()
            val (context, _) = contextFor(pd, listOf(pd), incomplete = listOf(pd))
            shouldThrow<IndeterminateDerivedValueException> {
                DerivedAttributeEvaluator.evaluate(
                    derived.expressionText,
                    context.index.describe(pd)!!.attributeValues,
                    context,
                    SchemaFunctionIntrinsicSet.AP242,
                )
            }
        }

        "the generated chain getter productDefinitionOccurrenceId agrees with the evaluator (PD, PDO, undetermined)" {
            val m = AssemblyTestModel()
            val top = m.pd("TOP")
            val child = m.pd("CHILD")
            val occurrence = m.pdo("OCC-1", child)
            val derived = resolved.getValue("next_assembly_usage_occurrence").flattenedDerivedAttributes.single()
            for (related in listOf<ProductDefinitionOrReference>(child, occurrence)) {
                val usage = m.usage("U-${related::class.simpleName}", top, related)
                val (context, _) = contextFor(usage, listOf(usage))
                val expected =
                    DerivedAttributeEvaluator.evaluate(
                        derived.expressionText,
                        context.index.describe(usage)!!.attributeValues,
                        context,
                        SchemaFunctionIntrinsicSet.AP242,
                    )
                when (related) {
                    is ProductDefinition -> {
                        expected shouldBe WhereRuleValue.Unset
                        usage.productDefinitionOccurrenceId shouldBe null
                    }
                    is ProductDefinitionOccurrence -> {
                        expected shouldBe WhereRuleValue.StringValue("OCC-1")
                        usage.productDefinitionOccurrenceId shouldBe "OCC-1"
                    }
                }
            }
        }

        // ---------------------------------------------------------------- UNIQUE

        "UR2 flags two usages of the same relating definition whose related occurrences share an id" {
            val m = AssemblyTestModel()
            val top = m.pd("TOP")
            val a = m.pdo("SAME")
            val b = m.pdo("SAME") // a distinct instance with an equal id: the tuple compares by value
            val result = Ap242ModelValidator.validate(listOf(m.usage("U1", top, a), m.usage("U2", top, b)))

            val violations =
                result.findings.flatMap { it.violations }.filter {
                    it.code ==
                        DslViolationCodes.UNIQUE_RULE_VIOLATED
                }
            violations shouldHaveSize 2
            violations.map { it.ruleLabel }.toSet() shouldBe setOf("UR2")
            violations.first().message shouldContain "next_assembly_usage_occurrence.UR2"
            violations.first().message shouldContain "product_definition_occurrence_id='SAME'"
        }

        "UR2 does not apply to different relating definitions, different occurrence ids or a plain product_definition" {
            val m = AssemblyTestModel()
            val top1 = m.pd("TOP1")
            val top2 = m.pd("TOP2")
            val same1 = m.pdo("SAME")
            val same2 = m.pdo("SAME")
            val other = m.pdo("OTHER")
            val plain1 = m.pd("PLAIN1")
            val plain2 = m.pd("PLAIN2")
            val result =
                Ap242ModelValidator.validate(
                    listOf(
                        m.usage("U1", top1, same1), // same occurrence id, different relating
                        m.usage("U2", top2, same2),
                        m.usage("U3", top1, other), // same relating as U1, different occurrence id
                        m.usage("U4", top1, plain1), // undetermined derived id: never equal
                        m.usage("U5", top1, plain2),
                    ),
                )
            result.findings
                .flatMap { it.violations }
                .filter { it.code == DslViolationCodes.UNIQUE_RULE_VIOLATED }
                .shouldBeEmpty()
        }

        "UR1 of the usage compares (reference_designator, relating); an unset designator never conflicts" {
            val m = AssemblyTestModel()
            val top = m.pd("TOP")
            val x = m.pd("X")
            val y = m.pd("Y")
            val clash =
                Ap242ModelValidator.validate(
                    listOf(m.usage("U1", top, x, "RD-1"), m.usage("U2", top, y, "RD-1")),
                )
            clash.findings.flatMap { it.violations }.map { it.ruleLabel } shouldBe listOf("UR1", "UR1")

            val unset = Ap242ModelValidator.validate(listOf(m.usage("U1", top, x), m.usage("U2", top, y)))
            unset.isValid shouldBe true
        }

        "the inherited UR1 of product_definition_usage (id, relating, related) is enforced on the population" {
            val m = AssemblyTestModel()
            val top = m.pd("TOP")
            val x = m.pd("X")
            val result = Ap242ModelValidator.validate(listOf(m.usage("SAME", top, x), m.usage("SAME", top, x)))
            result.findings
                .flatMap { it.violations }
                .map { it.message }
                .first() shouldContain
                "product_definition_usage.UR1"
        }

        "product_definition_formation UR1 (id, of_product) is enforced on the population" {
            val m = AssemblyTestModel()
            val prod =
                product("P") {
                    name = "p"
                    frameOfReference =
                        setOf(
                            productContext {
                                name = "e"
                                frameOfReference = m.ac
                                disciplineType = "m"
                            }.getOrThrow(),
                        )
                }.getOrThrow()
            val f1 = productDefinitionFormation("F") { ofProduct = prod }.getOrThrow()
            val f2 = productDefinitionFormation("F") { ofProduct = prod }.getOrThrow()
            val result = Ap242ModelValidator.validate(listOf(f1, f2))
            result.findings
                .flatMap {
                    it.violations
                }.count { it.code == DslViolationCodes.UNIQUE_RULE_VIOLATED } shouldBe
                2
        }

        // ---------------------------------------------------------------- acyclic assembly structure

        "a tree and a diamond-shaped assembly are valid" {
            val m = AssemblyTestModel()
            val top = m.pd("TOP")
            val left = m.pd("LEFT")
            val right = m.pd("RIGHT")
            val leaf = m.pd("LEAF")
            val result =
                Ap242ModelValidator.validate(
                    listOf(
                        m.usage("U1", top, left),
                        m.usage("U2", top, right),
                        m.usage("U3", left, leaf),
                        m.usage("U4", right, leaf),
                    ),
                )
            result.isValid shouldBe true
        }

        "a usage cycle is reported with KSTEP-W-002 and the cycle path on every usage of the cycle" {
            val m = AssemblyTestModel()
            val a = m.pd("A")
            val b = m.pd("B")
            val result = Ap242ModelValidator.validate(listOf(m.usage("U1", a, b), m.usage("U2", b, a)))

            codesOf(result) shouldBe
                listOf(DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED, DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED)
            val message =
                result.findings
                    .first()
                    .violations
                    .single()
                    .message
            message shouldContain "WR1"
            message shouldContain "'A'"
            message shouldContain "'B'"
            result.findings
                .first()
                .violations
                .single()
                .ruleLabel shouldBe "WR1"
        }

        "a usage of a definition within itself is a cycle" {
            val m = AssemblyTestModel()
            val a = m.pd("A")
            val result = Ap242ModelValidator.validate(listOf(m.usage("U1", a, a)))
            codesOf(result) shouldBe listOf(DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED)
        }

        "a cycle above a usage invalidates it too, although it is not on the cycle (specified EXPRESS semantics)" {
            val m = AssemblyTestModel()
            val x = m.pd("X")
            val y = m.pd("Y")
            val b = m.pd("B")
            val c = m.pd("C")
            val result =
                Ap242ModelValidator.validate(
                    listOf(
                        m.usage("U0", x, y), // the usage under test: walks up from X
                        m.usage("UX", b, x), // B is a parent of X
                        m.usage("U2", c, b), // B and C use each other: a cycle above X
                        m.usage("U3", b, c),
                    ),
                )
            val flagged =
                result.findings.filter {
                    it.violations.any { v ->
                        v.code ==
                            DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED
                    }
                }
            val flaggedIds = flagged.map { (it.instance as NextAssemblyUsageOccurrence).id }
            flaggedIds shouldContain "U0"
            flaggedIds shouldContain "UX"
        }

        "occurrences take part in the structure: a cycle through a product_definition_occurrence is found" {
            val m = AssemblyTestModel()
            val a = m.pd("A")
            val occurrence = m.pdo("O", a)
            val result =
                Ap242ModelValidator.validate(
                    listOf(m.usage("U1", a, occurrence), m.usage("U2", occurrence, a)),
                )
            codesOf(result) shouldBe
                listOf(DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED, DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED)
        }

        "an acyclic verdict is withheld when a visited definition has invisible referrers" {
            val m = AssemblyTestModel()
            val a = m.pd("A")
            val b = m.pd("B")
            val c = m.pd("C")
            val usages = listOf(m.usage("U1", a, b), m.usage("U2", b, c))
            val complete = Ap242ModelValidator.validate(usages)
            complete.indeterminateCount shouldBe 0

            // a is referenced by something that could not be reconstructed (e.g. an opaque Part 21 instance):
            // U1 starts at a, U2 walks up from b through U1 to a -- neither can be called acyclic.
            val partial = Ap242ModelValidator.validate(usages, incomplete = listOf(a))
            partial.indeterminateCount shouldBe 2
            partial.findings
                .flatMap { it.violations }
                .none { it.code == DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED } shouldBe true
            partial.isValid shouldBe true
        }

        "a cycle among visible instances is still reported when the population is incomplete" {
            val m = AssemblyTestModel()
            val a = m.pd("A")
            val b = m.pd("B")
            val result =
                Ap242ModelValidator.validate(
                    listOf(m.usage("U1", a, b), m.usage("U2", b, a)),
                    incomplete = listOf(a, b),
                )
            codesOf(result).count { it == DslViolationCodes.ACYCLIC_RELATIONSHIP_VIOLATED } shouldBe 2
        }

        // ---------------------------------------------------------------- occurrence rules

        "product_definition_occurrence WR2 needs a definition (configuration_design is not modelled)" {
            val m = AssemblyTestModel()
            val bare = Ap242ModelValidator.validate(listOf(m.pdo("O")))
            bare.findings
                .single()
                .violations
                .single()
                .ruleLabel shouldBe "WR2"
            bare.assumptions.map { it.roleText }.single() shouldContain "CONFIGURATION_DESIGN.DESIGN"

            val defined = Ap242ModelValidator.validate(listOf(m.pdo("O", m.pd("D"))))
            defined.isValid shouldBe true
        }

        "an occurrence can be the definition of another occurrence, and PDO.assembly_usages stays within [0:?]" {
            val m = AssemblyTestModel()
            val base = m.pdo("BASE", m.pd("D"))
            val derived = m.pdo("DERIVED", base)
            val result = Ap242ModelValidator.validate(listOf(m.usage("U", m.pd("TOP"), derived)))
            result.isValid shouldBe true
        }
    })
