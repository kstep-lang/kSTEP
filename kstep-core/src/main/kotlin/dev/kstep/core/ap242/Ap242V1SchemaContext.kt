package dev.kstep.core.ap242

import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.semantic.ExpressInverseAttribute
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.validation.PreparedWhereRule
import dev.kstep.express.validation.UsedInRoleResolver
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator

/**
 * The embedded AP242 V1 schema excerpt, resolved once, for model-level validation (ADR-0022).
 * Everything is derived from `ap242-v1-entities.exp` itself — no rule text is duplicated as a
 * constant, so the validator cannot drift from the schema.
 *
 * Initialised lazily (thread-safe `SYNCHRONIZED` lazy): the first access parses the EXPRESS
 * resource with ANTLR at runtime, which costs noticeably more than later calls.
 */
internal object Ap242V1SchemaContext {
    const val SCHEMA_ALIAS = "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"

    /** Roles of the real MIM schema whose entities the V1 excerpt does not model (ADR-0022). */
    val KNOWN_UNMODELLED_ROLES: Map<String, String> =
        mapOf(
            "$SCHEMA_ALIAS.NAME_ATTRIBUTE.NAMED_ITEM" to
                "name_attribute is not modelled in the V1 excerpt; USEDIN is evaluated as the empty set (ADR-0022)",
            "$SCHEMA_ALIAS.DESCRIPTION_ATTRIBUTE.DESCRIBED_ITEM" to
                "description_attribute is not modelled in the V1 excerpt; USEDIN is evaluated as the empty set (ADR-0022)",
            "$SCHEMA_ALIAS.ID_ATTRIBUTE.IDENTIFIED_ITEM" to
                "id_attribute is not modelled in the V1 excerpt; USEDIN is evaluated as the empty set (ADR-0022)",
        )

    private val schema by lazy { Ap242V1CodeGen.loadSchema() }

    /** Resolved entities by original-case EXPRESS name. */
    val resolvedEntities: Map<String, ResolvedEntity> by lazy { InheritanceResolver.resolve(schema) }

    val roleResolver: UsedInRoleResolver by lazy {
        UsedInRoleResolver(
            resolvedEntities = resolvedEntities.values.toList(),
            schemaName = schema.name,
            schemaAliases = setOf(SCHEMA_ALIAS),
            knownUnmodelledRoles = KNOWN_UNMODELLED_ROLES,
        )
    }

    /** WHERE rules that need the instance graph, by lower-case entity name. */
    val graphRules: Map<String, List<PreparedWhereRule>> by lazy {
        schema.entities
            .associate { entity ->
                entity.name.lowercase() to
                    entity.whereRules
                        .mapNotNull {
                            WhereRuleValidator.prepareForInstanceGraph(
                                WhereRuleSpec(it.label, it.expressionText, it.sourceLine),
                            )
                        }.filter { it.requiresInstanceGraph }
            }.filterValues { it.isNotEmpty() }
    }

    /** `INVERSE` attributes by lower-case entity name. */
    val inverseAttributes: Map<String, List<ExpressInverseAttribute>> by lazy {
        schema.entities
            .associate { it.name.lowercase() to it.inverseAttributes }
            .filterValues { it.isNotEmpty() }
    }
}
