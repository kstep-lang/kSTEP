package dev.kstep.express.codegen

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import dev.kstep.express.semantic.ExpressAttribute
import dev.kstep.express.semantic.ExpressEntity
import dev.kstep.express.semantic.ExpressSchema

/**
 * Generates idiomatic Kotlin `data class`es from an [ExpressSchema], one per entity, using
 * KotlinPoet's structured `FileSpec`/`TypeSpec` object model. The semantic model is
 * permissive; this layer is strict — it throws [CodeGenException] for any construct it
 * doesn't yet turn into Kotlin (SUBTYPE OF, redeclared attributes, LOGICAL/NUMBER/BINARY
 * attributes, defined-TYPE references, zero-attribute entities) instead of emitting
 * silently-wrong or partial classes. Constructor parameters follow the kUML "named
 * parameters everywhere" convention: every EXPRESS attribute becomes a named, and — for
 * OPTIONAL attributes — defaulted, constructor parameter, in EXPRESS declaration order.
 */
object ExpressKotlinCodeGenerator {
    fun generateFile(
        schema: ExpressSchema,
        packageName: String,
    ): FileSpec {
        val fileBuilder = FileSpec.builder(packageName, NamingConventions.toClassName(schema.name))
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
            val className = NamingConventions.toClassName(entity.name)
            if (!seenClassNames.add(className)) {
                throw CodeGenException(
                    "schema '${schema.name}': entity '${entity.name}' generates Kotlin class name " +
                        "'$className', which collides with another entity in the same schema after " +
                        "EXPRESS-to-Kotlin naming conversion (e.g. differing only in underscore run-length " +
                        "or case); codegen refuses to emit a file with duplicate class declarations",
                )
            }
            fileBuilder.addType(generateEntityType(entity, packageName))
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
    fun generateEntityType(
        entity: ExpressEntity,
        packageName: String,
    ): TypeSpec {
        if (entity.supertypes.isNotEmpty()) {
            throw CodeGenException(
                "entity '${entity.name}' has SUBTYPE OF ${entity.supertypes}; multi-supertype-to-Kotlin " +
                    "inheritance mapping is out of scope for V1 codegen",
            )
        }
        if (entity.attributes.isEmpty()) {
            throw CodeGenException(
                "entity '${entity.name}' has no explicit attributes; Kotlin data classes require at least " +
                    "one constructor parameter",
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
        val seenPropertyNames = mutableSetOf<String>()
        entity.attributes.forEach { attribute ->
            addAttribute(attribute, entity, packageName, constructorBuilder, classBuilder, seenPropertyNames)
        }

        classBuilder.primaryConstructor(constructorBuilder.build())
        addWhereRulesKdoc(entity, classBuilder)

        return classBuilder.build()
    }

    private fun addAttribute(
        attribute: ExpressAttribute,
        entity: ExpressEntity,
        packageName: String,
        constructorBuilder: FunSpec.Builder,
        classBuilder: TypeSpec.Builder,
        seenPropertyNames: MutableSet<String>,
    ) {
        when (attribute) {
            is ExpressAttribute.Redeclared ->
                throw CodeGenException(
                    "entity '${entity.name}' uses SELF\\...RENAMED attribute redeclaration, not supported by " +
                        "codegen yet",
                )
            is ExpressAttribute.Explicit -> {
                val errorContext = "entity '${entity.name}' attribute '${attribute.name}'"
                val baseType = resolveKotlinTypeName(attribute.declaredType, packageName, errorContext)
                val propertyType = if (attribute.isOptional) baseType.copy(nullable = true) else baseType
                val propertyName =
                    NamingConventions.escapeIfKotlinKeyword(NamingConventions.toPropertyName(attribute.name))

                if (!seenPropertyNames.add(propertyName)) {
                    throw CodeGenException(
                        "$errorContext: Kotlin identifier '$propertyName' collides with another attribute in " +
                            "the same entity after EXPRESS-to-Kotlin naming conversion (e.g. differing only in " +
                            "underscore run-length); codegen refuses to emit a non-compiling data class with " +
                            "duplicate constructor parameters",
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
        }
    }

    // Informational only, no evaluation logic — WHERE-rule interpretation is out of scope
    // for this wave. Uses the "%L" literal placeholder rather than interpolating the raw
    // expression text into the KDoc format string directly, so a "%" inside a WHERE
    // expression (however unlikely) can't be misread as a KotlinPoet format specifier.
    private fun addWhereRulesKdoc(
        entity: ExpressEntity,
        classBuilder: TypeSpec.Builder,
    ) {
        if (entity.whereRules.isEmpty()) return
        val kdoc =
            buildString {
                appendLine("WHERE rules (not evaluated by kSTEP V1):")
                entity.whereRules.forEach { rule ->
                    appendLine("- ${rule.label ?: "(unlabeled)"}: ${rule.expressionText}")
                }
            }
        classBuilder.addKdoc("%L", kdoc)
    }
}
