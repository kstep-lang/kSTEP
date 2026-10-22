package dev.kstep.core.ap242

import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ExpressInverseAttribute
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.semantic.ResolvedWhereRule
import dev.kstep.express.validation.PreparedWhereRule
import dev.kstep.express.validation.SchemaFunctionIntrinsicSet
import dev.kstep.express.validation.SchemaRulePreparer
import dev.kstep.express.validation.UsedInRoleResolver

/**
 * The embedded AP242 V1 schema excerpt, resolved once, for model-level validation (ADR-0022,
 * ADR-0023). Everything is derived from `ap242-v1-entities.exp` itself — no rule text is duplicated as a
 * constant, so the validator cannot drift from the schema.
 *
 * Initialised lazily (thread-safe `SYNCHRONIZED` lazy): the first access parses the EXPRESS
 * resource with ANTLR at runtime, which costs noticeably more than later calls.
 */
internal object Ap242V1SchemaContext {
    const val SCHEMA_ALIAS = "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"

    /** The fixed schema functions this context's rules and DERIVEs may call (ADR-0023). */
    val INTRINSICS = SchemaFunctionIntrinsicSet.AP242

    /**
     * Roles of the real MIM schema whose entities the V1 excerpt does not model (ADR-0022). Since ADR-0023
     * `name_attribute`, `description_attribute` and `id_attribute` are modelled, so only the
     * `configuration_design` role used by the WR2 of `product_definition_occurrence` remains.
     */
    val KNOWN_UNMODELLED_ROLES: Map<String, String> =
        mapOf(
            "$SCHEMA_ALIAS.CONFIGURATION_DESIGN.DESIGN" to
                "configuration_design is not modelled in the V1 excerpt; USEDIN is evaluated as the empty set (ADR-0023)",
        )

    private val schema by lazy { Ap242V1CodeGen.loadSchema() }

    /** Resolved entities by original-case EXPRESS name. */
    val resolvedEntities: Map<String, ResolvedEntity> by lazy { InheritanceResolver.resolve(schema) }

    /** The schema's TYPE declarations by lower-case name (for SELECT-aware INVERSE resolution). */
    val definedTypes: Map<String, ExpressDefinedType> by lazy {
        schema.definedTypes.associateBy { it.name.lowercase() }
    }

    val roleResolver: UsedInRoleResolver by lazy {
        UsedInRoleResolver(
            resolvedEntities = resolvedEntities.values.toList(),
            schemaName = schema.name,
            schemaAliases = setOf(SCHEMA_ALIAS),
            knownUnmodelledRoles = KNOWN_UNMODELLED_ROLES,
        )
    }

    // Every WHERE rule of the schema, prepared once. A rule outside the supported subset is a defect of this
    // excerpt/the evaluator and must never be dropped silently (ADR-0023): the first access fails loudly,
    // naming entity, label and reason (see SchemaRulePreparer).
    private val preparedRules: Map<ResolvedWhereRule, PreparedWhereRule> by lazy {
        SchemaRulePreparer.prepareAll(resolvedEntities.values, INTRINSICS, "AP242 V1 schema")
    }

    /**
     * WHERE rules that need the instance graph, by lower-case name of the concrete entity. Includes the
     * rules inherited from supertypes (ADR-0023): a `next_assembly_usage_occurrence` must satisfy
     * `product_definition_usage.WR1`.
     */
    val graphRules: Map<String, List<PreparedWhereRule>> by lazy {
        resolvedEntities.values
            .filter { it.isInstantiable }
            .associate { entity ->
                entity.entity.name.lowercase() to
                    entity.flattenedWhereRules
                        .map { preparedRules.getValue(it) }
                        .filter { it.requiresInstanceGraph }
            }.filterValues { it.isNotEmpty() }
    }

    /** `INVERSE` attributes by lower-case entity name. */
    val inverseAttributes: Map<String, List<ExpressInverseAttribute>> by lazy {
        schema.entities
            .associate { it.name.lowercase() to it.inverseAttributes }
            .filterValues { it.isNotEmpty() }
    }
}
