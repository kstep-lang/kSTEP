package dev.kstep.mcp.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BuildProductArgs(
    val id: String,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class BuildPersonAndOrganizationArgs(
    val handle: String,
    @SerialName("the_person") val thePerson: String? = null,
    @SerialName("the_organization") val theOrganization: String? = null,
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
)

@Serializable
data class BuildNextAssemblyUsageOccurrenceArgs(
    val id: String,
    val name: String? = null,
    @SerialName("relating_product_definition_id") val relatingProductDefinitionId: String,
    @SerialName("related_product_definition_id") val relatedProductDefinitionId: String,
    @SerialName("reference_designator") val referenceDesignator: String? = null,
)

@Serializable
data class BuildApprovalArgs(
    val handle: String,
    val status: String,
    val level: String? = null,
    @SerialName("authorized_by_handle") val authorizedByHandle: String,
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
