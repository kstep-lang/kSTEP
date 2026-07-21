package dev.kstep.express.semantic

import dev.kstep.express.codegen.NamingConventions

/**
 * Flattens each [ExpressEntity]'s SUBTYPE OF chain into a [ResolvedEntity]: every ancestor's
 * explicit attributes prepended (supertype-most-general first) to the entity's own, in EXPRESS
 * declaration order throughout. This is the missing piece between [ExpressEntity] (which already
 * captures `isAbstract`/`supertypes`/`supertypeConstraintRawText` losslessly, see that class's
 * KDoc) and Kotlin codegen: codegen needs the *flattened* attribute list, not the raw
 * single-level one, because a generated Kotlin `data class` cannot itself `extend` another
 * `data class` (see `dev.kstep.express.codegen.ExpressKotlinCodeGenerator`'s KDoc for why
 * attribute flattening, not Kotlin inheritance, is this codebase's chosen mapping).
 *
 * V1 scope, mirrored by the structured failures below: single inheritance only (one SUBTYPE OF
 * ancestor per entity — EXPRESS `AND`/`ANDOR` multiple supertypes are out of scope), and
 * redeclared (`SELF\entity.attr RENAMED ...`) attributes are never flattened (still capture-only,
 * same as `ExpressKotlinCodeGenerator`'s pre-existing handling of a non-inherited `Redeclared`
 * attribute). `DERIVE`/`INVERSE`/`UNIQUE` clauses referencing inherited attributes via
 * `SELF\entity.attr` (e.g. `next_assembly_usage_occurrence`'s real `UNIQUE`/`DERIVE`) are
 * unaffected by this resolver: those clauses remain raw captured text on [ExpressEntity], never
 * parsed or rewritten here.
 */
object InheritanceResolver {
    // Mirrors ExpressSemanticModelBuilder.MAX_TYPE_NESTING_DEPTH's role for TYPE nesting: a
    // structured failure for a pathologically deep (but acyclic) SUBTYPE OF chain, distinct from
    // the visited-set cycle guard below. No real STEP AP schema nests supertypes anywhere close
    // to this deep; this is defense-in-depth, not a limit expected to bind in practice.
    private const val MAX_SUPERTYPE_DEPTH = 32

    /**
     * Resolves every entity in [schema] against the schema's own entities as the SUBTYPE OF
     * ancestor pool. Returns a map keyed by each entity's original-case [ExpressEntity.name]
     * (not lowercased — EXPRESS name resolution is case-insensitive, but the returned map's own
     * keys mirror the schema's declared casing for convenient by-name lookup at call sites like
     * `dev.kstep.express.codegen.Ap242V1CodeGen`).
     */
    fun resolve(schema: ExpressSchema): Map<String, ResolvedEntity> {
        val entitiesByLowerName = schema.entities.associateBy { it.name.lowercase() }
        val cache = mutableMapOf<String, ResolvedEntity>()
        schema.entities.forEach { entity ->
            resolveEntity(entity, entitiesByLowerName, cache, emptyList())
        }
        return schema.entities.associate { it.name to cache.getValue(it.name.lowercase()) }
    }

    /**
     * Resolves a single entity known to have no SUBTYPE OF of its own (`entity.supertypes` must
     * be empty) — for call sites holding one [ExpressEntity] without its owning [ExpressSchema]
     * (schema-less codegen tests, mostly; see `ExpressKotlinCodeGenerator`'s convenience
     * `generateEntityType(ExpressEntity, ...)` overload). An entity that itself declares
     * SUBTYPE OF cannot be resolved this way — its ancestor's attributes live in the schema, not
     * in the bare [ExpressEntity] — use [resolve] against the owning schema for those.
     */
    fun resolveStandalone(entity: ExpressEntity): ResolvedEntity {
        require(entity.supertypes.isEmpty()) {
            "resolveStandalone requires an entity with no SUBTYPE OF (entity '${entity.name}' declares " +
                "SUBTYPE OF ${entity.supertypes}); resolve the owning ExpressSchema with " +
                "InheritanceResolver.resolve(schema) instead so its ancestor's attributes are available"
        }
        return resolveEntity(entity, emptyMap(), mutableMapOf(), emptyList())
    }

    private fun resolveEntity(
        entity: ExpressEntity,
        entitiesByLowerName: Map<String, ExpressEntity>,
        cache: MutableMap<String, ResolvedEntity>,
        ancestorPath: List<String>,
    ): ResolvedEntity {
        val key = entity.name.lowercase()
        cache[key]?.let { return it }

        if (ancestorPath.any { it.equals(entity.name, ignoreCase = true) }) {
            throw SemanticModelException(
                "SUBTYPE OF cycle detected: ${(ancestorPath + entity.name).joinToString(" -> ")}",
            )
        }
        if (ancestorPath.size >= MAX_SUPERTYPE_DEPTH) {
            throw SemanticModelException(
                "entity '${entity.name}': SUBTYPE OF chain exceeds $MAX_SUPERTYPE_DEPTH levels " +
                    "(starting from '${ancestorPath.firstOrNull()}') — cycle or pathologically deep hierarchy",
            )
        }
        if (entity.supertypes.size > 1) {
            throw SemanticModelException(
                "entity '${entity.name}' declares multiple supertypes ${entity.supertypes} (EXPRESS " +
                    "AND/ANDOR multiple inheritance); flattening more than a single SUBTYPE OF ancestor " +
                    "is out of scope for V1",
            )
        }

        val supertypeName = entity.supertypes.singleOrNull()
        val ancestorAttributes: List<ResolvedAttribute>
        val ancestorChain: List<String>
        if (supertypeName == null) {
            ancestorAttributes = emptyList()
            ancestorChain = emptyList()
        } else {
            val supertypeEntity =
                entitiesByLowerName[supertypeName.lowercase()]
                    ?: throw SemanticModelException(
                        "entity '${entity.name}': SUBTYPE OF '$supertypeName' does not resolve to a known " +
                            "entity in this schema (cross-schema USE/REFERENCE resolution is out of scope " +
                            "for V1, same limitation as attribute-type resolution)",
                    )
            val resolvedSupertype =
                resolveEntity(supertypeEntity, entitiesByLowerName, cache, ancestorPath + entity.name)
            ancestorAttributes = resolvedSupertype.flattenedAttributes
            ancestorChain = resolvedSupertype.ancestorChain + supertypeEntity.name
        }

        val ownAttributes =
            entity.attributes.map { attribute ->
                when (attribute) {
                    is ExpressAttribute.Explicit -> ResolvedAttribute(entity.name, attribute)
                    is ExpressAttribute.Redeclared ->
                        throw SemanticModelException(
                            "entity '${entity.name}' uses SELF\\...RENAMED attribute redeclaration " +
                                "('${attribute.rawText}'); inheritance flattening does not resolve " +
                                "redeclared attributes (out of scope for V1, same limitation " +
                                "ExpressKotlinCodeGenerator already documents for non-inherited entities)",
                        )
                }
            }

        val flattened = ancestorAttributes + ownAttributes
        checkNoCrossEntityNameCollisions(entity, flattened)

        val resolved =
            ResolvedEntity(
                entity = entity,
                flattenedAttributes = flattened,
                isInstantiable = !entity.isAbstract,
                ancestorChain = ancestorChain,
            )
        cache[key] = resolved
        return resolved
    }

    // Only flags a collision between attributes declared on *different* entities in the SUBTYPE
    // OF chain -- a genuinely new failure mode that only flattening can produce. A collision
    // between two attributes declared on the very same entity (e.g. "the_name"/"the__name" both
    // collapsing to "theName") is left to ExpressKotlinCodeGenerator's own seenPropertyNames
    // guard, unchanged: that check already exists, already throws CodeGenException (not
    // SemanticModelException), and already has test coverage asserting that exact exception
    // type -- duplicating it here with a different exception type would just make the same
    // input throw two different, inconsistent errors depending on incidental call order.
    private fun checkNoCrossEntityNameCollisions(
        entity: ExpressEntity,
        flattened: List<ResolvedAttribute>,
    ) {
        val declaringEntityByPropertyName = mutableMapOf<String, String>()
        flattened.forEach { resolvedAttribute ->
            val propertyName = NamingConventions.toPropertyName(resolvedAttribute.attribute.name)
            val previousDeclaringEntity = declaringEntityByPropertyName[propertyName]
            if (previousDeclaringEntity != null && previousDeclaringEntity != resolvedAttribute.declaringEntity) {
                throw SemanticModelException(
                    "entity '${entity.name}': flattened attribute '$propertyName' is declared both by " +
                        "'$previousDeclaringEntity' and '${resolvedAttribute.declaringEntity}' after " +
                        "EXPRESS-to-Kotlin naming conversion; codegen refuses to flatten a SUBTYPE OF " +
                        "chain with colliding inherited property names",
                )
            }
            declaringEntityByPropertyName.putIfAbsent(propertyName, resolvedAttribute.declaringEntity)
        }
    }
}
