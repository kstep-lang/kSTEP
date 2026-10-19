package dev.kstep.express.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import dev.kstep.express.semantic.DefinedTypeRef
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ExpressEntity
import dev.kstep.express.semantic.ExpressSchema
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.ResolvedAttribute
import dev.kstep.express.semantic.ResolvedDerivedAttribute
import dev.kstep.express.semantic.ResolvedEntity

/**
 * Codegen-wide emission options, additive to the "one flat `data class` per instantiable
 * entity" shape [ExpressKotlinCodeGenerator] always produces. Both fields default to `null`/
 * `false` — today's plain-`public`-constructor output — so every existing call site (in
 * particular [ExpressCodeGenTest][dev.kstep.tests.ExpressCodeGenTest], which pins that exact
 * shape) keeps compiling and passing unchanged.
 *
 * Introduced for kSTEP M2 Welle 10: [Ap242V1CodeGen] generates directly into `kstep-core`'s own
 * source set with [constructorVisibility] `KModifier.INTERNAL` and [consistentCopyVisibility]
 * `true`, so the only way to construct one of the twelve generated AP242 entities from outside
 * `kstep-core` is through its validating `dev.kstep.core.ap242` builder function — see that
 * module's README/ADR-0004 for the full rationale. `@ConsistentCopyVisibility` is required
 * alongside an `internal` constructor: without it, KotlinPoet's `data class` still generates a
 * `public copy()` regardless of the primary constructor's own visibility, which would reopen
 * exactly the unvalidated-construction bypass the `internal` constructor exists to close.
 */
data class CodeGenOptions(
    val constructorVisibility: KModifier? = null,
    val consistentCopyVisibility: Boolean = false,
    val definedTypeScope: DefinedTypeScope = DefinedTypeScope.ALL,
    val derivedAttributeMode: DerivedAttributeMode = DerivedAttributeMode.STRICT,
)

/**
 * How [ExpressKotlinCodeGenerator] treats a `DERIVE` attribute it cannot translate (ADR-0021).
 * A DERIVE *cycle* is a schema error and always throws [CodeGenException], in either mode, as does
 * an unsupported redeclaration of an inherited *explicit* attribute (neither keeping nor dropping
 * the constructor parameter would be correct).
 */
enum class DerivedAttributeMode {
    /** Every unsupported DERIVE throws [CodeGenException]. */
    STRICT,

    /** Unsupported DERIVEs are listed in the class KDoc and in the report, but never become properties. */
    GENERATE_SUPPORTED_REPORT_UNSUPPORTED,
}

/** A DERIVE attribute that was skipped (report mode), with the reason it is outside the supported subset. */
data class UnsupportedDerivedAttribute(
    val entity: String,
    val attribute: String,
    val reason: String,
)

data class EntityCodeGenResult(
    val typeSpec: TypeSpec,
    val unsupportedDerived: List<UnsupportedDerivedAttribute>,
)

data class FileCodeGenResult(
    val fileSpec: FileSpec,
    val unsupportedDerived: List<UnsupportedDerivedAttribute>,
)

/**
 * Which ENUMERATION/SELECT TYPEs [ExpressKotlinCodeGenerator.generateFile] emits. Emission and
 * *resolution* are separate: the full `schema.definedTypes` always stays available for resolving
 * attribute types (e.g. `identifier` -> STRING); this only filters what is written out.
 */
enum class DefinedTypeScope {
    /** Every explicit ENUMERATION and closed SELECT of the schema (historic behaviour). */
    ALL,

    /** Only those reachable from the emitted entities' attribute types (see [DefinedTypeReachability]). */
    REACHABLE_FROM_EMITTED_ENTITIES,
}

/**
 * Generates idiomatic Kotlin `data class`es from an [ExpressSchema], one per *instantiable*
 * entity, using KotlinPoet's structured `FileSpec`/`TypeSpec` object model. The semantic model
 * is permissive; this layer is strict — it throws [CodeGenException] for any construct it
 * doesn't yet turn into Kotlin (redeclared attributes, LOGICAL/NUMBER/BINARY attributes,
 * unsupported defined-TYPE forms, zero-attribute entities) instead of emitting silently-wrong or
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
 * `DERIVE` attributes within the supported expression subset become computed getter properties in
 * the class body (no backing field, so not part of `equals`/`hashCode`/`copy`/`toString` and never
 * written to Part 21; ADR-0021, see [DerivedAttributeCodeGen]); an inherited *explicit* attribute
 * that a subtype redeclares as DERIVE (`SELF\super.attr`) is dropped from the constructor. What is
 * outside the subset throws in [DerivedAttributeMode.STRICT] or is listed in the KDoc and the
 * report otherwise. `INVERSE`/`UNIQUE` remain capture-only metadata with no Kotlin representation.
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
        options: CodeGenOptions = CodeGenOptions(),
    ): FileSpec = generateFileWithReport(schema, packageName, resolvedEntities, options).fileSpec

    /** Like [generateFile], additionally returning the DERIVE attributes skipped in report mode. */
    fun generateFileWithReport(
        schema: ExpressSchema,
        packageName: String,
        resolvedEntities: Map<String, ResolvedEntity> = InheritanceResolver.resolve(schema),
        options: CodeGenOptions = CodeGenOptions(),
    ): FileCodeGenResult {
        val unsupportedDerived = mutableListOf<UnsupportedDerivedAttribute>()
        val fileBuilder = FileSpec.builder(packageName, NamingConventions.toClassName(schema.name))
        // Resolution map: ALWAYS the full, unfiltered TYPE list (insertion order = declaration order).
        val definedTypesByLowerName = schema.definedTypes.associateBy { it.name.lowercase() }

        // Declaration order is preserved for readability, but codegen must not rely on it:
        // Kotlin doesn't require forward declaration within a file, so entities can
        // reference other entities regardless of their relative order in the schema.
        val emittedEntities =
            schema.entities.mapNotNull { entity ->
                val resolved =
                    requireNotNull(resolvedEntities[entity.name]) {
                        "entity '${entity.name}' has no resolved inheritance info; resolvedEntities must be " +
                            "computed (via InheritanceResolver.resolve) against a schema that includes this " +
                            "entity, even if that schema is then filtered before being passed to generateFile"
                    }
                // ABSTRACT SUPERTYPE: contributes attributes to its subtypes, never emitted itself.
                resolved.takeIf { it.isInstantiable }
            }

        // Emission scope for ENUMERATION / SELECT TYPEs (independent of the resolution map above).
        val emittableKeys =
            definedTypesByLowerName
                .filterValues { it.enumerationItems != null || it.selectItems != null }
                .keys
        val emittedTypeKeys =
            when (options.definedTypeScope) {
                DefinedTypeScope.ALL -> emittableKeys
                DefinedTypeScope.REACHABLE_FROM_EMITTED_ENTITIES ->
                    DefinedTypeReachability.reachableEmittableTypes(emittedEntities, definedTypesByLowerName)
            }
        // Declaration order, regardless of discovery order in the reachability walk.
        val enumKeys =
            definedTypesByLowerName
                .filter { (key, dt) -> key in emittedTypeKeys && dt.enumerationItems != null }
                .keys
        val selectKeys =
            definedTypesByLowerName
                .filter { (key, dt) -> key in emittedTypeKeys && dt.selectItems != null }
                .keys

        val selectModel =
            SelectResolution.analyze(
                schemaName = schema.name,
                packageName = packageName,
                definedTypes = definedTypesByLowerName,
                resolvedEntities = resolvedEntities,
                emittedEntityNames = emittedEntities.map { it.entity.name.lowercase() }.toSet(),
                selectNamesToEmit = selectKeys,
                enumNamesToEmit = enumKeys,
            )

        // Distinct EXPRESS names can collide once mapped through NamingConventions.toClassName
        // (same underscore-collapsing/case-normalizing behavior as toPropertyName), which would
        // otherwise make this file silently contain two same-named TypeSpecs -- non-compiling
        // Kotlin emitted with no diagnostic. Guard loudly instead, naming both sides.
        val seenClassNames = mutableMapOf<String, String>()

        fun claim(
            kind: String,
            expressName: String,
        ) {
            val className = NamingConventions.toClassName(expressName)
            val previous = seenClassNames.put(className, "$kind '$expressName'")
            if (previous != null) {
                throw CodeGenException(
                    "schema '${schema.name}': $kind '$expressName' generates Kotlin class name '$className', " +
                        "which collides with another entity or enumeration in the same schema " +
                        "(already generated for $previous) after EXPRESS-to-Kotlin naming conversion " +
                        "(e.g. differing only in underscore run-length or case); codegen refuses to emit " +
                        "a file with duplicate class declarations",
                )
            }
        }

        emittedEntities.forEach { resolved ->
            claim("ENTITY", resolved.entity.name)
            val entityResult =
                generateEntityTypeWithReport(
                    resolved,
                    packageName,
                    definedTypesByLowerName,
                    options,
                    selectModel.entitySuperinterfaces[resolved.entity.name.lowercase()].orEmpty(),
                )
            fileBuilder.addType(entityResult.typeSpec)
            unsupportedDerived += entityResult.unsupportedDerived
        }
        selectModel.selects.forEach { (key, select) ->
            claim("SELECT TYPE", select.name)
            fileBuilder.addType(
                generateSelectType(select, packageName, selectModel.selectSuperinterfaces[key].orEmpty()),
            )
        }
        enumKeys.forEach { key ->
            val enumType = checkNotNull(definedTypesByLowerName[key])
            claim("ENUMERATION TYPE", enumType.name)
            fileBuilder.addType(generateEnumType(enumType, selectModel.enumSuperinterfaces[key].orEmpty()))
        }
        selectModel.wrapperSuperinterfaces.forEach { (key, supers) ->
            val alias = checkNotNull(definedTypesByLowerName[key])
            claim("SELECT member wrapper for TYPE", alias.name)
            val valueType =
                resolveKotlinTypeName(
                    DefinedTypeRef(alias.name),
                    packageName,
                    "SELECT member wrapper for TYPE '${alias.name}'",
                    definedTypesByLowerName,
                )
            fileBuilder.addType(generateSelectWrapperType(alias, valueType, supers, options))
        }
        return FileCodeGenResult(fileBuilder.build(), unsupportedDerived)
    }

    /**
     * `TYPE s = SELECT (a, b, ...)` -> `sealed interface S` (ADR-0020). Members attach to it from
     * their own side (entities/enums/wrappers list it as a superinterface); a nested SELECT lists
     * its parent SELECT(s) in [superinterfaces].
     */
    fun generateSelectType(
        definedType: ExpressDefinedType,
        packageName: String,
        superinterfaces: List<ClassName> = emptyList(),
    ): TypeSpec {
        val items = requireNotNull(definedType.selectItems) { "'${definedType.name}' is not a closed SELECT" }
        val builder =
            TypeSpec
                .interfaceBuilder(NamingConventions.toClassName(definedType.name))
                .addModifiers(KModifier.SEALED)
        superinterfaces.forEach { builder.addSuperinterface(it) }
        builder.addKdoc("%L", "EXPRESS SELECT (${items.joinToString(", ") { it.name }})")
        return builder.build()
    }

    /**
     * Part-21 typed-parameter wrapper (`LABEL('x')`) for an alias TYPE that is a SELECT member:
     * one top-level `data class` per alias TYPE, shared by every SELECT that lists it.
     */
    fun generateSelectWrapperType(
        definedType: ExpressDefinedType,
        valueType: TypeName,
        superinterfaces: List<ClassName>,
        options: CodeGenOptions = CodeGenOptions(),
    ): TypeSpec {
        val constructorBuilder = FunSpec.constructorBuilder()
        options.constructorVisibility?.let { constructorBuilder.addModifiers(it) }
        constructorBuilder.addParameter("value", valueType)
        val builder =
            TypeSpec
                .classBuilder(NamingConventions.toClassName(definedType.name))
                .addModifiers(KModifier.DATA)
        if (options.consistentCopyVisibility) {
            builder.addAnnotation(ClassName("kotlin", "ConsistentCopyVisibility"))
        }
        superinterfaces.forEach { builder.addSuperinterface(it) }
        builder.primaryConstructor(constructorBuilder.build())
        builder.addProperty(PropertySpec.builder("value", valueType).initializer("value").build())
        builder.addKdoc("%L", "Wrapper for EXPRESS TYPE '${definedType.name}' as a SELECT member.")
        return builder.build()
    }

    // One Kotlin enum class per `TYPE x = ENUMERATION OF (a, b, ...)`, constants UPPERCASED in
    // declaration order. EXPRESS identifiers are case-insensitive, so two items differing only
    // in case would collide -- guarded loudly like every other naming collision here.
    fun generateEnumType(
        definedType: ExpressDefinedType,
        superinterfaces: List<ClassName> = emptyList(),
    ): TypeSpec {
        val items = requireNotNull(definedType.enumerationItems) { "'${definedType.name}' is not an ENUMERATION" }
        val builder = TypeSpec.enumBuilder(NamingConventions.toClassName(definedType.name))
        superinterfaces.forEach { builder.addSuperinterface(it) }
        val seen = mutableSetOf<String>()
        items.forEach { item ->
            val constant = item.uppercase()
            if (!seen.add(constant)) {
                throw CodeGenException(
                    "ENUMERATION TYPE '${definedType.name}': item '$item' collides with another item after " +
                        "case normalization (EXPRESS identifiers are case-insensitive)",
                )
            }
            builder.addEnumConstant(constant)
        }
        return builder.build()
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
        options: CodeGenOptions = CodeGenOptions(),
    ): TypeSpec {
        if (entity.supertypes.isNotEmpty()) {
            throw CodeGenException(
                "entity '${entity.name}' has SUBTYPE OF ${entity.supertypes}; generateEntityType(ExpressEntity, " +
                    "...) has no ancestor context to flatten against — resolve the owning ExpressSchema via " +
                    "InheritanceResolver.resolve(schema) and pass the resulting ResolvedEntity instead",
            )
        }
        return generateEntityType(InheritanceResolver.resolveStandalone(entity), packageName, definedTypes, options)
    }

    // Primary implementation: consumes an already-flattened ResolvedEntity, so it never itself
    // walks a SUBTYPE OF chain — that is entirely InheritanceResolver's job.
    fun generateEntityType(
        resolved: ResolvedEntity,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType> = emptyMap(),
        options: CodeGenOptions = CodeGenOptions(),
        superinterfaces: List<ClassName> = emptyList(),
    ): TypeSpec = generateEntityTypeWithReport(resolved, packageName, definedTypes, options, superinterfaces).typeSpec

    /** Like [generateEntityType], additionally returning the DERIVE attributes skipped in report mode. */
    fun generateEntityTypeWithReport(
        resolved: ResolvedEntity,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType> = emptyMap(),
        options: CodeGenOptions = CodeGenOptions(),
        superinterfaces: List<ClassName> = emptyList(),
    ): EntityCodeGenResult {
        val entity = resolved.entity
        if (!resolved.isInstantiable) {
            throw CodeGenException(
                "entity '${entity.name}' is an ABSTRACT SUPERTYPE; it contributes attributes to its subtypes " +
                    "via inheritance flattening but is never itself code-generated as a concrete Kotlin class",
            )
        }
        val constructorAttributes = resolved.flattenedAttributes.filter { it.redeclaredAsDerived == null }
        if (constructorAttributes.isEmpty()) {
            val redeclared = resolved.flattenedAttributes.size - constructorAttributes.size
            throw CodeGenException(
                "entity '${entity.name}' has no explicit attributes (including inherited ones); Kotlin data " +
                    "classes require at least one constructor parameter" +
                    if (redeclared > 0) " ($redeclared inherited attribute(s) are redeclared as DERIVE)" else "",
            )
        }

        val constructorBuilder = FunSpec.constructorBuilder()
        options.constructorVisibility?.let { constructorBuilder.addModifiers(it) }
        val classBuilder =
            TypeSpec
                .classBuilder(NamingConventions.toClassName(entity.name))
                .addModifiers(KModifier.DATA)
        if (options.consistentCopyVisibility) {
            classBuilder.addAnnotation(ClassName("kotlin", "ConsistentCopyVisibility"))
        }
        superinterfaces.forEach { classBuilder.addSuperinterface(it) }

        // Distinct EXPRESS attribute names can collide once mapped through
        // NamingConventions.toPropertyName (e.g. runs of underscores are collapsed), which
        // would otherwise make KotlinPoet silently emit two constructor parameters/properties
        // with the same name and different types — non-compiling Kotlin. Guard loudly instead.
        // (A collision between attributes declared on *different* entities in the SUBTYPE OF
        // chain is already caught earlier, with a more specific message, by
        // InheritanceResolver's own cross-entity check; this guard's remaining job is exactly
        // the same-entity case it always handled.)
        val seenPropertyNames = mutableSetOf<String>()
        constructorAttributes.forEach { resolvedAttribute ->
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

        val unsupportedDerived = mutableListOf<UnsupportedDerivedAttribute>()
        DerivedAttributeCodeGen.plan(resolved, packageName, definedTypes).forEach { plan ->
            when (plan) {
                is DerivedPlan.Supported -> {
                    val property = plan.propertySpec
                    if (!seenPropertyNames.add(property.name)) {
                        throw CodeGenException(
                            "entity '${entity.name}' DERIVE '${plan.attr.name}': Kotlin identifier " +
                                "'${property.name}' collides with another attribute in the same (flattened) " +
                                "entity after EXPRESS-to-Kotlin naming conversion",
                        )
                    }
                    classBuilder.addProperty(property)
                }
                is DerivedPlan.Unsupported -> {
                    val message =
                        "entity '${entity.name}' DERIVE '${plan.attr.name}' " +
                            "(declared by '${plan.attr.declaringEntity}') is outside the supported subset: " +
                            plan.reason
                    if (options.derivedAttributeMode == DerivedAttributeMode.STRICT ||
                        plan.attr.overrides is ResolvedDerivedAttribute.Override.InheritedExplicit
                    ) {
                        throw CodeGenException(message)
                    }
                    unsupportedDerived +=
                        UnsupportedDerivedAttribute(plan.attr.declaringEntity, plan.attr.name, plan.reason)
                }
            }
        }
        addUnsupportedDerivedKdoc(unsupportedDerived, classBuilder)

        return EntityCodeGenResult(classBuilder.build(), unsupportedDerived)
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
        classBuilder.addKdoc("%L", sanitizeKdoc(kdoc))
    }

    private fun addUnsupportedDerivedKdoc(
        unsupported: List<UnsupportedDerivedAttribute>,
        classBuilder: TypeSpec.Builder,
    ) {
        if (unsupported.isEmpty()) return
        val kdoc =
            buildString {
                appendLine("DERIVE attributes not generated (outside the supported expression subset, ADR-0021):")
                unsupported.forEach {
                    appendLine(
                        "- ${it.entity}.${it.attribute}: ${it.reason.replace(Regex("\\s+"), " ")}",
                    )
                }
            }
        classBuilder.addKdoc("%L", sanitizeKdoc(kdoc))
    }
}
