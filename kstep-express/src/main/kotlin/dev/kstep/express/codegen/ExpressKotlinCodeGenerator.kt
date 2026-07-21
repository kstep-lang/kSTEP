package dev.kstep.express.codegen

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ExpressEntity
import dev.kstep.express.semantic.ExpressSchema
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.ResolvedAttribute
import dev.kstep.express.semantic.ResolvedEntity

/**
 * Generates idiomatic Kotlin `data class`es from an [ExpressSchema], one per *instantiable*
 * entity, using KotlinPoet's structured `FileSpec`/`TypeSpec` object model. The semantic model
 * is permissive; this layer is strict — it throws [CodeGenException] for any construct it
 * doesn't yet turn into Kotlin (redeclared attributes, LOGICAL/NUMBER/BINARY attributes,
 * defined-TYPE references, zero-attribute entities) instead of emitting silently-wrong or
 * partial classes. Constructor parameters follow the kUML "named parameters everywhere"
 * convention: every EXPRESS attribute becomes a named, and — for OPTIONAL attributes —
 * defaulted, constructor parameter, in EXPRESS declaration order.
 *
 * SUBTYPE OF is handled by **attribute flattening**, not Kotlin inheritance: a Kotlin
 * `data class` cannot be `open` and cannot extend another `data class`, so every generated
 * entity stays exactly one flat `data class` with inherited attributes (supertype-most-general
 * first) prepended to its own, exactly as [dev.kstep.express.semantic.InheritanceResolver]
 * already flattened them into a [ResolvedEntity]. An `ABSTRACT SUPERTYPE`
 * (`ResolvedEntity.isInstantiable == false`) contributes attributes to its subtypes but is
 * never itself emitted as a class — see [generateFile]'s skip and [generateEntityType]'s throw
 * for the two places that matters.
 *
 * As of M1 Welle 6, [ExpressEntity] also carries `derivedAttributes`/`inverseAttributes`/
 * `uniqueRules` — this generator still does not read them; `DERIVE`/`INVERSE`/`UNIQUE`
 * remain capture-only metadata with no Kotlin representation.
 */
object ExpressKotlinCodeGenerator {
    // resolvedEntities defaults to a fresh whole-schema resolve for callers that only have a
    // schema on hand (e.g. ExpressCodeGenTest's schema-only call sites). Ap242V1CodeGen passes
    // an explicit map instead: it filters `schema` down to only the entities it wants emitted
    // (successfully-generated targets + curated support entities) but must resolve inheritance
    // against the *unfiltered* schema first, so an ancestor excluded from the filtered schema
    // (e.g. assembly_component_usage, needed only to flatten NextAssemblyUsageOccurrence, never
    // itself emitted) is still available for lookups. See that class for the full rationale.
    fun generateFile(
        schema: ExpressSchema,
        packageName: String,
        resolvedEntities: Map<String, ResolvedEntity> = InheritanceResolver.resolve(schema),
    ): FileSpec {
        val fileBuilder = FileSpec.builder(packageName, NamingConventions.toClassName(schema.name))
        val definedTypesByLowerName = schema.definedTypes.associateBy { it.name.lowercase() }
        // Declaration order is preserved for readability, but codegen must not rely on it:
        // Kotlin doesn't require forward declaration within a file, so entities can
        // reference other entities regardless of their relative order in the schema.
        //
        // Distinct EXPRESS entity names can collide once mapped through
        // NamingConventions.toClassName (same underscore-collapsing/case-normalizing
        // behavior as toPropertyName), which would otherwise make this loop silently add
        // two same-named TypeSpecs to the same FileSpec — non-compiling Kotlin emitted with
        // no diagnostic. Guard loudly instead, mirroring the per-entity seenPropertyNames
        // check in generateEntityType/addAttribute.
        val seenClassNames = mutableSetOf<String>()
        schema.entities.forEach { entity ->
            val resolved =
                requireNotNull(resolvedEntities[entity.name]) {
                    "entity '${entity.name}' has no resolved inheritance info; resolvedEntities must be " +
                        "computed (via InheritanceResolver.resolve) against a schema that includes this entity, " +
                        "even if that schema is then filtered before being passed to generateFile"
                }
            // ABSTRACT SUPERTYPE: contributes attributes to its subtypes, never emitted itself.
            if (!resolved.isInstantiable) return@forEach

            val className = NamingConventions.toClassName(entity.name)
            if (!seenClassNames.add(className)) {
                throw CodeGenException(
                    "schema '${schema.name}': entity '${entity.name}' generates Kotlin class name " +
                        "'$className', which collides with another entity in the same schema after " +
                        "EXPRESS-to-Kotlin naming conversion (e.g. differing only in underscore run-length " +
                        "or case); codegen refuses to emit a file with duplicate class declarations",
                )
            }
            fileBuilder.addType(generateEntityType(resolved, packageName, definedTypesByLowerName))
        }
        return fileBuilder.build()
    }

    fun generateFileSource(
        schema: ExpressSchema,
        packageName: String,
    ): String = generateFile(schema, packageName).toString()

    // Public (not `internal`): lets both `generateFile` and `kstep-tests` (a separate
    // Gradle module/compilation unit) drive single-entity codegen and its CodeGenException
    // cases directly, without needing a whole schema wrapper.
    //
    // Convenience overload for callers holding a bare ExpressEntity with no owning-schema
    // context (most of ExpressCodeGenTest): only valid for an entity with no SUBTYPE OF of its
    // own, since flattening a SUBTYPE OF chain needs the ancestor entities, which live in the
    // schema, not in this one ExpressEntity. An entity that does declare SUBTYPE OF must be
    // resolved via InheritanceResolver.resolve(schema) and passed through the ResolvedEntity
    // overload below instead.
    fun generateEntityType(
        entity: ExpressEntity,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType> = emptyMap(),
    ): TypeSpec {
        if (entity.supertypes.isNotEmpty()) {
            throw CodeGenException(
                "entity '${entity.name}' has SUBTYPE OF ${entity.supertypes}; generateEntityType(ExpressEntity, " +
                    "...) has no ancestor context to flatten against — resolve the owning ExpressSchema via " +
                    "InheritanceResolver.resolve(schema) and pass the resulting ResolvedEntity instead",
            )
        }
        return generateEntityType(InheritanceResolver.resolveStandalone(entity), packageName, definedTypes)
    }

    // Primary implementation: consumes an already-flattened ResolvedEntity, so it never itself
    // walks a SUBTYPE OF chain — that is entirely InheritanceResolver's job.
    fun generateEntityType(
        resolved: ResolvedEntity,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType> = emptyMap(),
    ): TypeSpec {
        val entity = resolved.entity
        if (!resolved.isInstantiable) {
            throw CodeGenException(
                "entity '${entity.name}' is an ABSTRACT SUPERTYPE; it contributes attributes to its subtypes " +
                    "via inheritance flattening but is never itself code-generated as a concrete Kotlin class",
            )
        }
        if (resolved.flattenedAttributes.isEmpty()) {
            throw CodeGenException(
                "entity '${entity.name}' has no explicit attributes (including inherited ones); Kotlin data " +
                    "classes require at least one constructor parameter",
            )
        }

        val constructorBuilder = FunSpec.constructorBuilder()
        val classBuilder =
            TypeSpec
                .classBuilder(NamingConventions.toClassName(entity.name))
                .addModifiers(KModifier.DATA)

        // Distinct EXPRESS attribute names can collide once mapped through
        // NamingConventions.toPropertyName (e.g. runs of underscores are collapsed), which
        // would otherwise make KotlinPoet silently emit two constructor parameters/properties
        // with the same name and different types — non-compiling Kotlin. Guard loudly instead.
        // (A collision between attributes declared on *different* entities in the SUBTYPE OF
        // chain is already caught earlier, with a more specific message, by
        // InheritanceResolver's own cross-entity check; this guard's remaining job is exactly
        // the same-entity case it always handled.)
        val seenPropertyNames = mutableSetOf<String>()
        resolved.flattenedAttributes.forEach { resolvedAttribute ->
            addAttribute(
                resolvedAttribute,
                entity,
                packageName,
                definedTypes,
                constructorBuilder,
                classBuilder,
                seenPropertyNames,
            )
        }

        classBuilder.primaryConstructor(constructorBuilder.build())
        addWhereRulesKdoc(entity, classBuilder)

        return classBuilder.build()
    }

    private fun addAttribute(
        resolvedAttribute: ResolvedAttribute,
        entity: ExpressEntity,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType>,
        constructorBuilder: FunSpec.Builder,
        classBuilder: TypeSpec.Builder,
        seenPropertyNames: MutableSet<String>,
    ) {
        val attribute = resolvedAttribute.attribute
        val declaredOnSuffix =
            if (resolvedAttribute.declaringEntity == entity.name) {
                ""
            } else {
                " (inherited from '${resolvedAttribute.declaringEntity}')"
            }
        val errorContext = "entity '${entity.name}' attribute '${attribute.name}'$declaredOnSuffix"
        val baseType = resolveKotlinTypeName(attribute.declaredType, packageName, errorContext, definedTypes)
        val propertyType = if (attribute.isOptional) baseType.copy(nullable = true) else baseType
        val propertyName =
            NamingConventions.escapeIfKotlinKeyword(NamingConventions.toPropertyName(attribute.name))

        if (!seenPropertyNames.add(propertyName)) {
            throw CodeGenException(
                "$errorContext: Kotlin identifier '$propertyName' collides with another attribute in " +
                    "the same (flattened) entity after EXPRESS-to-Kotlin naming conversion; codegen refuses " +
                    "to emit a non-compiling data class with duplicate constructor parameters",
            )
        }

        val parameterSpecBuilder = ParameterSpec.builder(propertyName, propertyType)
        if (attribute.isOptional) {
            parameterSpecBuilder.defaultValue("null")
        }
        constructorBuilder.addParameter(parameterSpecBuilder.build())
        classBuilder.addProperty(
            PropertySpec
                .builder(propertyName, propertyType)
                .initializer(propertyName)
                .build(),
        )
    }

    // Informational only, no evaluation logic here — this generated class does not evaluate
    // its own WHERE rules. Uses the "%L" literal placeholder rather than interpolating the
    // raw expression text into the KDoc format string directly, so a "%" inside a WHERE
    // expression (however unlikely) can't be misread as a KotlinPoet format specifier.
    private fun addWhereRulesKdoc(
        entity: ExpressEntity,
        classBuilder: TypeSpec.Builder,
    ) {
        if (entity.whereRules.isEmpty()) return
        val kdoc =
            buildString {
                appendLine(
                    "WHERE rules (evaluated at runtime via dev.kstep.express.validation.WhereRuleValidator, " +
                        "not by this generated class itself):",
                )
                entity.whereRules.forEach { rule ->
                    appendLine("- ${rule.label ?: "(unlabeled)"}: ${rule.expressionText}")
                }
            }
        classBuilder.addKdoc("%L", kdoc)
    }
}
