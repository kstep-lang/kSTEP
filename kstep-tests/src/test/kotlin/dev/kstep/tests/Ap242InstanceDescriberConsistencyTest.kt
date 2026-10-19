package dev.kstep.tests

import dev.kstep.core.ap242.Ap242ModelValidator
import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.approvalStatus
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.semantic.AggregationType
import dev.kstep.express.semantic.EntityTypeRef
import dev.kstep.express.semantic.ExpressInverseAttribute
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.validation.InstanceDescriber
import dev.kstep.express.validation.ResolvedUsedInRole
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

        fun twelveInstances(): List<Any> {
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
            val nauo =
                nextAssemblyUsageOccurrence("N") {
                    name = "u"
                    relatingProductDefinition = d1
                    relatedProductDefinition = d2
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
            return listOf(ac, pc, pdc, prod, formation, d1, nauo, status, appr, who, org, po)
        }

        "the describer reports, for every entity-typed attribute of every instance, the schema's declaring entity" {
            val validatorRoots = twelveInstances()
            val describer = describerOrFail()
            val seen = mutableSetOf<String>()
            for (instance in validatorRoots) {
                val description = checkNotNull(describer.describe(instance)) { "no description for ${instance::class}" }
                seen += description.entityName
                val entity = resolved.getValue(description.entityName)
                val expectedReferences =
                    entity.flattenedAttributes
                        .filter { attributeRefersToEntity(it.attribute.declaredType) }
                        .map { it.declaringEntity.lowercase() to it.attribute.name.lowercase() }
                        .toSet()
                description.references.map { it.declaringEntity to it.attribute }.toSet() shouldBe expectedReferences
            }
            seen.size shouldBe 12
            seen shouldBe
                resolved.values
                    .filter { it.isInstantiable && it.entity.name.lowercase() in seen }
                    .map { it.entity.name }
                    .toSet()
        }

        "every USEDIN role in the embedded schema resolves, and only the three known roles are unmodelled" {
            val resolver =
                UsedInRoleResolver(
                    resolved.values.toList(),
                    schema.name,
                    setOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"),
                    mapOf(
                        "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF.NAME_ATTRIBUTE.NAMED_ITEM" to "n",
                        "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF.DESCRIPTION_ATTRIBUTE.DESCRIBED_ITEM" to "d",
                        "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF.ID_ATTRIBUTE.IDENTIFIED_ITEM" to "i",
                    ),
                )
            val roles = mutableSetOf<String>()
            var graphRules = 0
            for (entity in schema.entities) {
                for (rule in entity.whereRules) {
                    val expression =
                        try {
                            WhereRuleExpressionBuilder.build(
                                ExpressParserFactory.parseExpression(rule.expressionText),
                                WhereRuleExpressionBuilder.BuildOptions(allowInstanceGraphFunctions = true),
                            )
                        } catch (_: dev.kstep.express.validation.UnsupportedWhereExpressionException) {
                            continue
                        }
                    if (!expression.requiresInstanceGraph()) continue
                    graphRules++
                    collectRoles(expression, roles)
                }
            }
            graphRules shouldBe 5
            roles.size shouldBe 3
            roles.map { resolver.resolve(it)::class.simpleName }.toSet() shouldBe setOf("KnownUnmodelled")
            resolved.values
                .flatMap {
                    it.entity.inverseAttributes
                }.filterIsInstance<ExpressInverseAttribute.Explicit>()
                .map { it.name } shouldBe
                listOf("context_elements")
            (resolver.resolve("") is ResolvedUsedInRole.AnyRole) shouldBe true
        }

        "the real validator evaluates the schema's graph rules on a full model without skipping any" {
            val result = Ap242ModelValidator.validate(twelveInstances())
            result.isValid shouldBe true
            // twelve entity types, thirteen instances: the NAUO's second product_definition is only reachable via reference
            result.instanceCount shouldBe 13
        }
    })

private fun attributeRefersToEntity(type: dev.kstep.express.semantic.ExpressType): Boolean =
    when (type) {
        is EntityTypeRef -> true
        is AggregationType -> type.elementType is EntityTypeRef
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
