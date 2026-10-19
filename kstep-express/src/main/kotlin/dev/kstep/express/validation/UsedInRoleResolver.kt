package dev.kstep.express.validation

import dev.kstep.express.semantic.ResolvedEntity

/** The outcome of resolving a `USEDIN` role string against the schema (ADR-0022). */
sealed interface ResolvedUsedInRole {
    /** A role naming an attribute that the schema excerpt models; [declaringEntity] really declares [attribute]. */
    data class Modelled(
        val declaringEntity: String,
        val attribute: String,
    ) : ResolvedUsedInRole

    /**
     * A role that is valid in the full schema but whose entity the excerpt does not model. The
     * population cannot contain a referrer, so the BAG is empty — an explicit, reported
     * assumption ([reason]), never a silent default.
     */
    data class KnownUnmodelled(
        val roleText: String,
        val reason: String,
    ) : ResolvedUsedInRole

    /** `USEDIN(SELF, '')`: any role (ISO 10303-11 section 15.26). */
    data object AnyRole : ResolvedUsedInRole
}

/**
 * Resolves `USEDIN` role strings (`SCHEMA.ENTITY.ATTRIBUTE`) against a resolved schema. Anything
 * that is neither modelled nor explicitly listed in [knownUnmodelledRoles] is a loud
 * [WhereRuleEvaluationException]: a typo in a role must never evaluate to "no referrers".
 *
 * Immutable after construction, safe to share between threads.
 */
class UsedInRoleResolver(
    resolvedEntities: List<ResolvedEntity>,
    schemaName: String,
    schemaAliases: Set<String> = emptySet(),
    knownUnmodelledRoles: Map<String, String> = emptyMap(),
) {
    private val entities: Map<String, ResolvedEntity> =
        resolvedEntities.associateBy { it.entity.name.lowercase() }
    private val acceptedSchemaNames: Set<String> = (schemaAliases + schemaName).map { it.uppercase() }.toSet()
    private val canonicalSchema: String = schemaName.uppercase()
    private val unmodelled: Map<String, String>

    init {
        unmodelled =
            knownUnmodelledRoles.entries.associate { (role, reason) ->
                val parts = role.uppercase().split('.')
                require(parts.size == 3 && parts[0] in acceptedSchemaNames) {
                    "known unmodelled role '${clipForMessage(role)}' must be SCHEMA.ENTITY.ATTRIBUTE of this schema"
                }
                "$canonicalSchema.${parts[1]}.${parts[2]}" to reason
            }
    }

    fun resolve(roleText: String): ResolvedUsedInRole {
        if (roleText.isEmpty()) return ResolvedUsedInRole.AnyRole
        if (roleText.length > MAX_ROLE_LENGTH) {
            throw WhereRuleEvaluationException("USEDIN role text exceeds $MAX_ROLE_LENGTH characters")
        }
        val shown = clipForMessage(roleText)
        val parts = roleText.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) {
            throw WhereRuleEvaluationException(
                "USEDIN role '$shown' must have the form SCHEMA.ENTITY.ATTRIBUTE",
            )
        }
        val schema = parts[0].uppercase()
        if (schema !in acceptedSchemaNames) {
            throw WhereRuleEvaluationException(
                "USEDIN role '$shown' names unknown schema '${clipForMessage(parts[0])}'",
            )
        }
        val entityName = parts[1].lowercase()
        val attributeName = parts[2].lowercase()
        val key = "$canonicalSchema.${parts[1].uppercase()}.${parts[2].uppercase()}"
        val entity = entities[entityName]
        if (entity == null) {
            val reason =
                unmodelled[key]
                    ?: throw WhereRuleEvaluationException(
                        "USEDIN role '$shown' names entity '${clipForMessage(parts[1])}', which is neither modelled " +
                            "nor a known unmodelled role",
                    )
            return ResolvedUsedInRole.KnownUnmodelled(roleText, reason)
        }
        val attribute =
            entity.flattenedAttributes.firstOrNull { it.attribute.name.lowercase() == attributeName }
                ?: throw WhereRuleEvaluationException(
                    "USEDIN role '$shown': entity '${entity.entity.name}' has no attribute " +
                        "'${clipForMessage(parts[2])}'",
                )
        if (attribute.declaringEntity.lowercase() != entityName) {
            throw WhereRuleEvaluationException(
                "USEDIN role '$shown': attribute '${attribute.attribute.name}' is declared by " +
                    "'${attribute.declaringEntity}', use that entity in the role",
            )
        }
        return ResolvedUsedInRole.Modelled(entityName, attributeName)
    }

    private companion object {
        const val MAX_ROLE_LENGTH = 512
    }
}
