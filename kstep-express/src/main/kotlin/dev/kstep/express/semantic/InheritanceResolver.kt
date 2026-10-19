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
 * attribute). `DERIVE` clauses are flattened too (ADR-0021): ancestors' DERIVEs first, and a
 * subtype's `SELF\super.attr : T := expr;` replaces an inherited DERIVE in place or marks an
 * inherited explicit attribute as redeclared-as-derived (`RENAMED` is rejected). `INVERSE`/
 * `UNIQUE` clauses (e.g. `next_assembly_usage_occurrence`'s real `UNIQUE`) remain raw captured
 * text on [ExpressEntity], never parsed or rewritten here.
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
        val ancestorDerived: List<ResolvedDerivedAttribute>
        val ancestorWhere: List<ResolvedWhereRule>
        val ancestorUnique: List<ResolvedUniqueRule>
        val ancestorChain: List<String>
        if (supertypeName == null) {
            ancestorAttributes = emptyList()
            ancestorDerived = emptyList()
            ancestorWhere = emptyList()
            ancestorUnique = emptyList()
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
            ancestorDerived = resolvedSupertype.flattenedDerivedAttributes
            ancestorWhere = resolvedSupertype.flattenedWhereRules
            ancestorUnique = resolvedSupertype.flattenedUniqueRules
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

        val ancestorExplicitCount = ancestorAttributes.size
        val attributes = (ancestorAttributes + ownAttributes).toMutableList()
        val derived = ancestorDerived.toMutableList()
        entity.derivedAttributes.forEach { clause ->
            when (clause) {
                is ExpressDerivedAttribute.Explicit ->
                    addOwnDerived(entity, clause, attributes, derived)
                is ExpressDerivedAttribute.Redeclared ->
                    redeclareDerived(entity, clause, ancestorChain, ancestorExplicitCount, attributes, derived)
            }
        }

        val flattened: List<ResolvedAttribute> = attributes
        checkNoCrossEntityNameCollisions(entity, flattened)

        val resolved =
            ResolvedEntity(
                entity = entity,
                flattenedAttributes = flattened,
                isInstantiable = !entity.isAbstract,
                ancestorChain = ancestorChain,
                flattenedDerivedAttributes = derived,
                flattenedWhereRules = ancestorWhere + entity.whereRules.map { ResolvedWhereRule(entity.name, it) },
                flattenedUniqueRules = ancestorUnique + entity.uniqueRules.map { ResolvedUniqueRule(entity.name, it) },
            )
        cache[key] = resolved
        return resolved
    }

    // A plain (non-redeclared) DERIVE name must be new: EXPRESS requires SELF\super.attr to
    // redefine an inherited attribute, so a bare name that already exists up the chain (explicit
    // or derived) or on the same entity is a schema error, reported with both declaring entities.
    private fun addOwnDerived(
        entity: ExpressEntity,
        clause: ExpressDerivedAttribute.Explicit,
        attributes: List<ResolvedAttribute>,
        derived: MutableList<ResolvedDerivedAttribute>,
    ) {
        val propertyName = NamingConventions.toPropertyName(clause.name)
        val explicitClash =
            attributes.firstOrNull { NamingConventions.toPropertyName(it.attribute.name) == propertyName }
        val derivedClash = derived.firstOrNull { NamingConventions.toPropertyName(it.name) == propertyName }
        val clashingEntity = explicitClash?.declaringEntity ?: derivedClash?.declaringEntity
        if (clashingEntity != null) {
            throw SemanticModelException(
                "entity '${entity.name}' DERIVE '${clause.name}' collides with an attribute of the same " +
                    "name declared by '$clashingEntity' (after EXPRESS-to-Kotlin naming conversion); " +
                    "redefining an inherited attribute requires the SELF\\supertype.attribute form",
            )
        }
        derived +=
            ResolvedDerivedAttribute(
                declaringEntity = entity.name,
                name = clause.name,
                declaredType = clause.declaredType,
                expressionText = clause.expressionText,
                sourceLine = clause.sourceLine,
            )
    }

    private fun redeclareDerived(
        entity: ExpressEntity,
        clause: ExpressDerivedAttribute.Redeclared,
        ancestorChain: List<String>,
        ancestorExplicitCount: Int,
        attributes: MutableList<ResolvedAttribute>,
        derived: MutableList<ResolvedDerivedAttribute>,
    ) {
        val context = "entity '${entity.name}' DERIVE '${clause.rawText}'"
        if (clause.renamedTo != null) {
            throw SemanticModelException(
                "$context RENAMED ${clause.renamedTo}: RENAMED in DERIVE redeclaration is out of scope " +
                    "(ADR-0021); the attribute keeps its inherited name",
            )
        }
        val chainIndex = ancestorChain.indexOfFirst { it.equals(clause.supertypeName, ignoreCase = true) }
        if (chainIndex < 0) {
            throw SemanticModelException(
                "$context: link 'SELF\\${clause.supertypeName}': '${clause.supertypeName}' is not a " +
                    "supertype of '${entity.name}' (ancestor chain: ${ancestorChain.joinToString(
                        " -> ",
                    ).ifEmpty { "none" }})",
            )
        }
        // Entities whose declarations are visible through the named supertype's flat attribute set.
        val visible = ancestorChain.subList(0, chainIndex + 1).toSet()
        val name = clause.attributeName

        val derivedIndex =
            derived.indexOfFirst { it.name.equals(name, ignoreCase = true) && it.originalDeclaringEntity in visible }
        val explicitIndex =
            (0 until ancestorExplicitCount).firstOrNull { index ->
                val candidate = attributes[index]
                candidate.attribute.name.equals(name, ignoreCase = true) &&
                    candidate.declaringEntity in visible &&
                    candidate.redeclaredAsDerived == null
            }
        when {
            derivedIndex >= 0 -> {
                val previous = derived[derivedIndex]
                val override =
                    when (val previousOverride = previous.overrides) {
                        is ResolvedDerivedAttribute.Override.InheritedExplicit -> previousOverride
                        else -> ResolvedDerivedAttribute.Override.InheritedDerived(previous.originalDeclaringEntity)
                    }
                derived[derivedIndex] = redeclaredEntry(entity, clause, previous.name, override)
            }
            explicitIndex != null -> {
                val target = attributes[explicitIndex]
                val entry =
                    redeclaredEntry(
                        entity,
                        clause,
                        target.attribute.name,
                        ResolvedDerivedAttribute.Override.InheritedExplicit(target.declaringEntity),
                    )
                attributes[explicitIndex] = target.copy(redeclaredAsDerived = entry)
                derived += entry
            }
            else ->
                throw SemanticModelException(
                    "$context: link '.$name': '$name' is not an attribute of '${clause.supertypeName}'",
                )
        }
    }

    private fun redeclaredEntry(
        entity: ExpressEntity,
        clause: ExpressDerivedAttribute.Redeclared,
        originalName: String,
        override: ResolvedDerivedAttribute.Override,
    ) = ResolvedDerivedAttribute(
        declaringEntity = entity.name,
        name = originalName,
        declaredType = clause.declaredType,
        expressionText = clause.expressionText,
        overrides = override,
        sourceLine = clause.sourceLine,
    )

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
