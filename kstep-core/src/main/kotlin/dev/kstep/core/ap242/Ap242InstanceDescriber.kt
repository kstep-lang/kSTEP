package dev.kstep.core.ap242

import dev.kstep.express.validation.InstanceDescriber
import dev.kstep.express.validation.InstanceDescription
import dev.kstep.express.validation.OutgoingReference
import dev.kstep.express.validation.WhereRuleValue
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.Approval
import dev.kstep.generated.ap242v1.ApprovalStatus
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.Organization
import dev.kstep.generated.ap242v1.Person
import dev.kstep.generated.ap242v1.PersonAndOrganization
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductContext
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionContext
import dev.kstep.generated.ap242v1.ProductDefinitionFormation

/**
 * Describes the twelve generated AP242 V1 types for the reverse-reference index (ADR-0022): the
 * concrete entity name, the scalar attribute values and — per entity-typed attribute — the
 * *declaring* entity of that attribute, which differs from the instance's own type for inherited
 * attributes (`product_context.frame_of_reference` is declared by `application_context_element`).
 * An explicit `when` instead of reflection: no `kotlin-reflect`, and the declaring entity cannot
 * be derived reflectively anyway. `Ap242InstanceDescriberConsistencyTest` pins this table to the schema.
 */
internal object Ap242InstanceDescriber : InstanceDescriber {
    private const val APPLICATION_CONTEXT_ELEMENT = "application_context_element"
    private const val PRODUCT_DEFINITION_RELATIONSHIP = "product_definition_relationship"

    override fun describe(instance: Any): InstanceDescription? =
        when (instance) {
            is ApplicationContext ->
                description("application_context", mapOf("application" to str(instance.application)))
            is ProductContext ->
                description(
                    "product_context",
                    mapOf("name" to str(instance.name), "discipline_type" to str(instance.disciplineType)),
                    ref(APPLICATION_CONTEXT_ELEMENT, "frame_of_reference", instance.frameOfReference),
                )
            is ProductDefinitionContext ->
                description(
                    "product_definition_context",
                    mapOf("name" to str(instance.name), "life_cycle_stage" to str(instance.lifeCycleStage)),
                    ref(APPLICATION_CONTEXT_ELEMENT, "frame_of_reference", instance.frameOfReference),
                )
            is ApprovalStatus -> description("approval_status", mapOf("name" to str(instance.name)))
            is Person ->
                description(
                    "person",
                    mapOf(
                        "id" to str(instance.id),
                        "last_name" to optional(instance.lastName),
                        "first_name" to optional(instance.firstName),
                    ),
                )
            is Organization ->
                description(
                    "organization",
                    mapOf(
                        "id" to optional(instance.id),
                        "name" to str(instance.name),
                        "description" to optional(instance.description),
                    ),
                )
            is Product ->
                description(
                    "product",
                    mapOf(
                        "id" to str(instance.id),
                        "name" to str(instance.name),
                        "description" to optional(instance.description),
                    ),
                    OutgoingReference("product", "frame_of_reference", instance.frameOfReference.toList()),
                )
            is ProductDefinition ->
                description(
                    "product_definition",
                    mapOf("id" to str(instance.id), "description" to optional(instance.description)),
                    ref("product_definition", "formation", instance.formation),
                    ref("product_definition", "frame_of_reference", instance.frameOfReference),
                )
            is ProductDefinitionFormation ->
                description(
                    "product_definition_formation",
                    mapOf("id" to str(instance.id), "description" to optional(instance.description)),
                    ref("product_definition_formation", "of_product", instance.ofProduct),
                )
            is NextAssemblyUsageOccurrence ->
                description(
                    "next_assembly_usage_occurrence",
                    mapOf(
                        "id" to str(instance.id),
                        "name" to str(instance.name),
                        "description" to optional(instance.description),
                        "reference_designator" to optional(instance.referenceDesignator),
                    ),
                    ref(
                        PRODUCT_DEFINITION_RELATIONSHIP,
                        "relating_product_definition",
                        instance.relatingProductDefinition,
                    ),
                    ref(
                        PRODUCT_DEFINITION_RELATIONSHIP,
                        "related_product_definition",
                        instance.relatedProductDefinition,
                    ),
                )
            is Approval ->
                description(
                    "approval",
                    mapOf("level" to str(instance.level)),
                    ref("approval", "status", instance.status),
                )
            is PersonAndOrganization ->
                description(
                    "person_and_organization",
                    emptyMap(),
                    ref("person_and_organization", "the_person", instance.thePerson),
                    ref("person_and_organization", "the_organization", instance.theOrganization),
                )
            else -> null
        }

    private fun description(
        entityName: String,
        values: Map<String, WhereRuleValue>,
        vararg references: OutgoingReference,
    ) = InstanceDescription(entityName, values, references.toList())

    private fun ref(
        declaringEntity: String,
        attribute: String,
        target: Any,
    ) = OutgoingReference(declaringEntity, attribute, listOf(target))

    private fun str(value: String): WhereRuleValue = WhereRuleValue.StringValue(value)

    private fun optional(value: String?): WhereRuleValue =
        if (value == null) WhereRuleValue.Unset else WhereRuleValue.StringValue(value)
}
