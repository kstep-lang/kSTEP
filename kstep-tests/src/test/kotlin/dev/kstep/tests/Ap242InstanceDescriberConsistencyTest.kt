package dev.kstep.tests

import dev.kstep.core.ap242.Ap242ModelValidator
import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.approvalStatus
import dev.kstep.core.ap242.descriptionAttribute
import dev.kstep.core.ap242.idAttribute
import dev.kstep.core.ap242.nameAttribute
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.ap242.productDefinitionOccurrence
import dev.kstep.core.getOrThrow
import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.semantic.AggregationType
import dev.kstep.express.semantic.EntityTypeRef
import dev.kstep.express.semantic.ExpressInverseAttribute
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.validation.InstanceDescriber
import dev.kstep.express.validation.ResolvedUsedInRole
import dev.kstep.express.validation.SchemaFunctionIntrinsicSet
import dev.kstep.express.validation.UsedInRoleResolver
import dev.kstep.express.validation.WhereRuleExpression
import dev.kstep.express.validation.WhereRuleExpressionBuilder
import dev.kstep.express.validation.requiresInstanceGraph
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Drift guard for ADR-0022: the hand-written [dev.kstep.core.ap242] instance describer must agree
 * with the schema, and every USEDIN role used by the embedded `.exp` must resolve.
 */
class Ap242InstanceDescriberConsistencyTest :
    StringSpec({
        val schema = Ap242V1CodeGen.loadSchema()
        val resolved = InheritanceResolver.resolve(schema)
        val definedTypes = schema.definedTypes.associateBy { it.name.lowercase() }

        fun sixteenInstances(): List<Any> {
            val ac = applicationContext { application = "cc" }.getOrThrow()
            val pc =
                productContext {
                    name = "e"
                    frameOfReference = ac
                    disciplineType = "m"
                }.getOrThrow()
            val pdc =
                productDefinitionContext {
                    name = "e"
                    frameOfReference = ac
                    lifeCycleStage = "d"
                }.getOrThrow()
            val prod =
                product("P") {
                    name = "n"
                    frameOfReference = setOf(pc)
                }.getOrThrow()
            val formation = productDefinitionFormation("F") { ofProduct = prod }.getOrThrow()
            val d1 =
                productDefinition("D1") {
                    this.formation = formation
                    frameOfReference = pdc
                }.getOrThrow()
            val d2 =
                productDefinition("D2") {
                    this.formation = formation
                    frameOfReference = pdc
                }.getOrThrow()
            val occurrence = productDefinitionOccurrence("O1") { definition = d2 }.getOrThrow()
            val nauo =
                nextAssemblyUsageOccurrence("N") {
                    name = "u"
                    relatingProductDefinition = d1
                    relatedProductDefinition = occurrence
                    referenceDesignator = "A1"
                }.getOrThrow()
            val status = approvalStatus { name = "approved" }.getOrThrow()
            val appr =
                approval {
                    this.status = status
                    level = "1"
                }.getOrThrow()
            val who = person("x") { lastName = "Doe" }.getOrThrow()
            val org = organization { name = "ACME" }.getOrThrow()
            val po =
                personAndOrganization {
                    thePerson = who
                    theOrganization = org
                }.getOrThrow()
            val nameOfD1 =
                nameAttribute {
                    attributeValue = "Housing"
                    namedItem = d1
                }.getOrThrow()
            val descriptionOfPo =
                descriptionAttribute {
                    attributeValue = "Lead engineer"
                    describedItem = po
                }.getOrThrow()
            val idOfAc =
                idAttribute {
                    attributeValue = "ctx-1"
                    identifiedItem = ac
                }.getOrThrow()
            return listOf(
                ac,
                pc,
                pdc,
                prod,
                formation,
                d1,
                occurrence,
                nauo,
                status,
                appr,
                who,
                org,
                po,
                nameOfD1,
                descriptionOfPo,
                idOfAc,
            )
        }

        "the describer reports, for every entity-typed attribute of every instance, the schema's declaring entity" {
            val validatorRoots = sixteenInstances()
            val describer = describerOrFail()
            val seen = mutableSetOf<String>()
            for (instance in validatorRoots) {
                val description = checkNotNull(describer.describe(instance)) { "no description for ${instance::class}" }
                seen += description.entityName
                val entity = resolved.getValue(description.entityName)
                val expectedReferences =
                    entity.flattenedAttributes
                        .filter { attributeRefersToEntity(it.attribute.declaredType, definedTypes) }
                        .map { it.declaringEntity.lowercase() to it.attribute.name.lowercase() }
                        .toSet()
                description.references.map { it.declaringEntity to it.attribute }.toSet() shouldBe expectedReferences
            }
            seen.size shouldBe 16
            seen shouldBe
                resolved.values
                    .filter { it.isInstantiable && it.entity.name.lowercase() in seen }
                    .map { it.entity.name }
                    .toSet()
        }

        "every USEDIN role in the embedded schema resolves, and only configuration_design is unmodelled" {
            val resolver =
                UsedInRoleResolver(
                    resolved.values.toList(),
                    schema.name,
                    setOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"),
                    mapOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF.CONFIGURATION_DESIGN.DESIGN" to "c"),
                )
            val roles = mutableSetOf<String>()
            var graphRules = 0
            for (entity in schema.entities) {
                for (rule in entity.whereRules) {
                    val expression =
                        try {
                            WhereRuleExpressionBuilder.build(
                                ExpressParserFactory.parseExpression(rule.expressionText),
                                WhereRuleExpressionBuilder.BuildOptions(
                                    allowInstanceGraphFunctions = true,
                                    schemaFunctionIntrinsics = SchemaFunctionIntrinsicSet.AP242,
                                ),
                            )
                        } catch (_: dev.kstep.express.validation.UnsupportedWhereExpressionException) {
                            continue
                        }
                    if (!expression.requiresInstanceGraph()) continue
                    graphRules++
                    collectRoles(expression, roles)
                }
            }
            // application_context WR1/WR2, product_definition WR1, person_and_organization WR1/WR2,
            // product_definition_occurrence WR2 and product_definition_usage WR1 (acyclic).
            graphRules shouldBe 7
            roles.size shouldBe 4
            roles.filter { resolver.resolve(it) is ResolvedUsedInRole.KnownUnmodelled } shouldBe
                listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF.CONFIGURATION_DESIGN.DESIGN")
            roles.map { resolver.resolve(it)::class.simpleName }.toSet() shouldBe setOf("Modelled", "KnownUnmodelled")
            resolved.values
                .flatMap {
                    it.entity.inverseAttributes
                }.filterIsInstance<ExpressInverseAttribute.Explicit>()
                .map { it.name } shouldBe
                listOf("context_elements", "assembly_usages")
            (resolver.resolve("") is ResolvedUsedInRole.AnyRole) shouldBe true
        }

        "the real validator evaluates the schema's graph rules on a full model without skipping any" {
            val result = Ap242ModelValidator.validate(sixteenInstances())
            result.isValid shouldBe true
            // sixteen entity types, seventeen instances: the occurrence's product_definition d2 is only reachable via reference
            result.instanceCount shouldBe 17
        }
    })

// Entity-typed, directly, in an aggregation, or through a (possibly aliased) SELECT type (ADR-0023).
private fun attributeRefersToEntity(
    type: dev.kstep.express.semantic.ExpressType,
    definedTypes: Map<String, dev.kstep.express.semantic.ExpressDefinedType>,
): Boolean =
    when (type) {
        is EntityTypeRef -> true
        is AggregationType -> attributeRefersToEntity(type.elementType, definedTypes)
        is dev.kstep.express.semantic.DefinedTypeRef -> {
            val definedType = definedTypes.getValue(type.typeName.lowercase())
            definedType.selectItems != null ||
                definedType.aliasedTypeName?.let {
                    attributeRefersToEntity(
                        dev.kstep.express.semantic
                            .DefinedTypeRef(it),
                        definedTypes,
                    )
                } == true
        }
        else -> false
    }

private fun collectRoles(
    expression: WhereRuleExpression,
    into: MutableSet<String>,
) {
    when (expression) {
        is WhereRuleExpression.UsedIn -> into += expression.roleText
        is WhereRuleExpression.SizeOf -> collectRoles(expression.operand, into)
        is WhereRuleExpression.Comparison -> {
            collectRoles(expression.left, into)
            collectRoles(expression.right, into)
        }
        is WhereRuleExpression.And -> {
            collectRoles(expression.left, into)
            collectRoles(expression.right, into)
        }
        is WhereRuleExpression.Or -> {
            collectRoles(expression.left, into)
            collectRoles(expression.right, into)
        }
        is WhereRuleExpression.Not -> collectRoles(expression.operand, into)
        else -> Unit
    }
}

// The describer is `internal` to kstep-core; reach it reflectively (test-only) so the drift guard can
// inspect it directly without widening kstep-core's public API.
private fun describerOrFail(): InstanceDescriber {
    val clazz = Class.forName("dev.kstep.core.ap242.Ap242InstanceDescriber")
    return clazz.getField("INSTANCE").get(null) as InstanceDescriber
}
