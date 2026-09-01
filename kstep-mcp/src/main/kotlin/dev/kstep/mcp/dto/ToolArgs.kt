package dev.kstep.mcp.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BuildApplicationContextArgs(
    val handle: String,
    val application: String? = null,
)

@Serializable
data class BuildProductContextArgs(
    val handle: String,
    val name: String? = null,
    @SerialName("frame_of_reference_handle") val frameOfReferenceHandle: String,
    @SerialName("discipline_type") val disciplineType: String? = null,
)

@Serializable
data class BuildProductDefinitionContextArgs(
    val handle: String,
    val name: String? = null,
    @SerialName("frame_of_reference_handle") val frameOfReferenceHandle: String,
    @SerialName("life_cycle_stage") val lifeCycleStage: String? = null,
)

@Serializable
data class BuildApprovalStatusArgs(
    val handle: String,
    val name: String? = null,
)

@Serializable
data class BuildPersonArgs(
    val id: String,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("middle_names") val middleNames: List<String>? = null,
    @SerialName("prefix_titles") val prefixTitles: List<String>? = null,
    @SerialName("suffix_titles") val suffixTitles: List<String>? = null,
)

@Serializable
data class BuildOrganizationArgs(
    val handle: String,
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class BuildProductArgs(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("frame_of_reference_handles") val frameOfReferenceHandles: List<String>? = null,
)

@Serializable
data class BuildPersonAndOrganizationArgs(
    val handle: String,
    @SerialName("the_person_id") val thePersonId: String,
    @SerialName("the_organization_handle") val theOrganizationHandle: String,
)

@Serializable
data class BuildProductDefinitionFormationArgs(
    val id: String,
    val description: String? = null,
    @SerialName("of_product_id") val ofProductId: String,
)

@Serializable
data class BuildProductDefinitionArgs(
    val id: String,
    val description: String? = null,
    @SerialName("formation_id") val formationId: String,
    @SerialName("frame_of_reference_handle") val frameOfReferenceHandle: String,
)

@Serializable
data class BuildNextAssemblyUsageOccurrenceArgs(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("relating_product_definition_id") val relatingProductDefinitionId: String,
    @SerialName("related_product_definition_id") val relatedProductDefinitionId: String,
    @SerialName("reference_designator") val referenceDesignator: String? = null,
)

@Serializable
data class BuildApprovalArgs(
    val handle: String,
    @SerialName("status_handle") val statusHandle: String,
    val level: String? = null,
)

@Serializable
data class ExportPart21Args(
    @SerialName("root_ids") val rootIds: List<String>,
    @SerialName("file_name") val fileName: String,
    val timestamp: String,
    val description: List<String> = emptyList(),
    val author: List<String> = emptyList(),
    val organization: List<String> = emptyList(),
    @SerialName("schema_identifiers") val schemaIdentifiers: List<String>? = null,
)

@Serializable
data class GetEntityArgs(
    val id: String,
)
