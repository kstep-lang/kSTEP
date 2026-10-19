package dev.kstep.express.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.ExpressSyntaxException
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ResolvedDerivedAttribute
import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.validation.ComparisonOperator
import dev.kstep.express.validation.UnsupportedWhereExpressionException
import dev.kstep.express.validation.WhereRuleExpression
import dev.kstep.express.validation.WhereRuleExpressionBuilder

internal const val MAX_DERIVE_CHAIN_DEPTH = MAX_ALIAS_CHAIN_DEPTH
internal const val MAX_DERIVED_ATTRIBUTES_PER_ENTITY = 256
internal const val MAX_DERIVE_EXPRESSION_LENGTH = 8_192

/** The statically known kind of a translated sub-expression. */
internal sealed interface StaticType {
    data object Str : StaticType

    data object Integer : StaticType

    data object Real : StaticType

    data object Bool : StaticType

    /** Entity / aggregate / enumeration / SELECT: only usable as a plain alias of an equally typed attribute. */
    data class Opaque(
        val typeName: TypeName,
    ) : StaticType
}

/** One attribute (explicit or derived) visible to a DERIVE initializer through `SELF`. */
internal data class Operand(
    val kotlinName: String,
    val type: StaticType,
    val nullable: Boolean,
    val derivedName: String?,
)

internal sealed interface DerivedPlan {
    val attr: ResolvedDerivedAttribute

    data class Supported(
        override val attr: ResolvedDerivedAttribute,
        val propertySpec: PropertySpec,
    ) : DerivedPlan

    data class Unsupported(
        override val attr: ResolvedDerivedAttribute,
        val reason: String,
    ) : DerivedPlan
}

/** Makes arbitrary schema text safe inside a KDoc block (no early close, no nested comment). */
internal fun sanitizeKdoc(text: String): String = text.replace("*/", "*&#47;").replace("/*", "/&#42;")

/**
 * Translates the supported subset of `DERIVE` initializers into Kotlin getter properties
 * (ADR-0021). The translation reuses the WHERE-rule expression parser/AST
 * ([WhereRuleExpressionBuilder], with `allowGroupQualifiedSelf`) and follows
 * [dev.kstep.express.validation.WhereRuleEvaluator] semantically, so the generated property and the
 * reflective evaluator agree on every input: comparisons always go through `compareTo` (numeric
 * operands both via `toDouble()`), never through Kotlin's IEEE `==`/`<` on Double.
 *
 * Everything outside the subset becomes a [DerivedPlan.Unsupported] with the reason; cycles and
 * limit violations are schema errors and throw [CodeGenException] in every mode.
 */
internal object DerivedAttributeCodeGen {
    private class DeriveUnsupported(
        message: String,
    ) : RuntimeException(message)

    private const val INSTANCE_GRAPH_UNSUPPORTED =
        "SIZEOF/USEDIN need an instance graph and are unsupported in DERIVE codegen (ADR-0022)"

    private fun unsupported(reason: String): Nothing = throw DeriveUnsupported(reason)

    private sealed interface Parsed {
        data class Ok(
            val expression: WhereRuleExpression,
            val dependencies: List<String>,
        ) : Parsed

        data class Bad(
            val reason: String,
        ) : Parsed
    }

    private data class Analysis(
        val code: CodeBlock,
        val type: TypeName,
        val nullable: Boolean,
        val staticType: StaticType,
    )

    fun plan(
        resolved: ResolvedEntity,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType>,
    ): List<DerivedPlan> {
        val derived = resolved.flattenedDerivedAttributes
        if (derived.isEmpty()) return emptyList()
        val entityName = resolved.entity.name
        if (derived.size > MAX_DERIVED_ATTRIBUTES_PER_ENTITY) {
            throw CodeGenException(
                "entity '$entityName' has ${derived.size} DERIVE attributes, more than the supported " +
                    "$MAX_DERIVED_ATTRIBUTES_PER_ENTITY per entity",
            )
        }

        val scope = LinkedHashMap<String, Operand>()
        val declarerByKey = HashMap<String, String>()
        resolved.flattenedAttributes.filter { it.redeclaredAsDerived == null }.forEach { resolvedAttribute ->
            val attribute = resolvedAttribute.attribute
            val typeName =
                resolveKotlinTypeName(
                    attribute.declaredType,
                    packageName,
                    "entity '$entityName' attribute '${attribute.name}'",
                    definedTypes,
                )
            scope[attribute.name.lowercase()] =
                Operand(propertyNameOf(attribute.name), staticTypeOf(typeName), attribute.isOptional, null)
            declarerByKey[attribute.name.lowercase()] = resolvedAttribute.declaringEntity
        }
        derived.forEach { declarerByKey[it.name.lowercase()] = it.originalDeclaringEntity }
        val derivedKeys = derived.associateBy { it.name.lowercase() }

        val parsed =
            derived.associate {
                it.name.lowercase() to
                    parse(it, entityName, resolved, scope, declarerByKey, derivedKeys)
            }

        // Dependency order (post-order DFS) with cycle and depth detection: both are schema
        // errors, never silently downgraded to "unsupported".
        val order = mutableListOf<String>()
        val finished = HashSet<String>()
        val path = ArrayList<String>()

        fun visit(key: String) {
            if (key in finished) return
            val cycleStart = path.indexOf(key)
            if (cycleStart >= 0) {
                val cycle = (path.subList(cycleStart, path.size) + key).map { derivedKeys.getValue(it).name }
                throw CodeGenException("DERIVE cycle in entity '$entityName': ${cycle.joinToString(" -> ")}")
            }
            path += key
            if (path.size > MAX_DERIVE_CHAIN_DEPTH) {
                throw CodeGenException(
                    "DERIVE dependency chain in entity '$entityName' exceeds $MAX_DERIVE_CHAIN_DEPTH attributes: " +
                        path.joinToString(" -> ") { derivedKeys.getValue(it).name },
                )
            }
            (parsed.getValue(key) as? Parsed.Ok)?.dependencies?.forEach { visit(it) }
            path.removeAt(path.size - 1)
            finished += key
            order += key
        }
        derived.forEach { visit(it.name.lowercase()) }

        val results = HashMap<String, DerivedPlan>()
        order.forEach { key ->
            val attr = derivedKeys.getValue(key)
            val plan = planOne(attr, parsed.getValue(key), results, scope, entityName, packageName, definedTypes)
            results[key] = plan
            if (plan is DerivedPlan.Supported) {
                val analysisOperand = plan.propertySpec
                scope[key] =
                    Operand(
                        kotlinName = analysisOperand.name,
                        type = staticTypeOf(analysisOperand.type.copy(nullable = false)),
                        nullable = analysisOperand.type.isNullable,
                        derivedName = attr.name,
                    )
            }
        }
        return derived.map { results.getValue(it.name.lowercase()) }
    }

    private fun parse(
        attr: ResolvedDerivedAttribute,
        entityName: String,
        resolved: ResolvedEntity,
        scope: Map<String, Operand>,
        declarerByKey: Map<String, String>,
        derivedKeys: Map<String, ResolvedDerivedAttribute>,
    ): Parsed {
        if (attr.expressionText.length > MAX_DERIVE_EXPRESSION_LENGTH) {
            return Parsed.Bad(
                "initializer is ${attr.expressionText.length} characters long, above the supported " +
                    "$MAX_DERIVE_EXPRESSION_LENGTH; not parsed",
            )
        }
        val expression =
            try {
                WhereRuleExpressionBuilder.build(
                    ExpressParserFactory.parseExpression(attr.expressionText),
                    WhereRuleExpressionBuilder.BuildOptions(allowGroupQualifiedSelf = true),
                )
            } catch (e: UnsupportedWhereExpressionException) {
                return Parsed.Bad(e.message ?: "outside the supported expression subset")
            } catch (e: ExpressSyntaxException) {
                return Parsed.Bad("initializer does not parse as an EXPRESS expression: ${e.message}")
            }
        val chain = resolved.ancestorChain
        val dependencies = LinkedHashSet<String>()
        try {
            references(expression).forEach { reference ->
                val key = reference.name.lowercase()
                if (key !in scope && key !in derivedKeys) {
                    unsupported(
                        "SELF.${reference.name} does not resolve to an explicit or derived attribute of " +
                            "'$entityName' (ancestor chain: ${chain.joinToString(" -> ").ifEmpty { "none" }})",
                    )
                }
                val qualifier = reference.entity
                if (qualifier != null && !qualifier.equals(entityName, ignoreCase = true)) {
                    val index = chain.indexOfFirst { it.equals(qualifier, ignoreCase = true) }
                    if (index < 0) {
                        unsupported(
                            "link 'SELF\\$qualifier': '$qualifier' is neither '$entityName' nor one of its " +
                                "supertypes (ancestor chain: ${chain.joinToString(" -> ").ifEmpty { "none" }})",
                        )
                    }
                    if (declarerByKey[key] !in chain.subList(0, index + 1)) {
                        unsupported(
                            "link '.${reference.name}': '${reference.name}' is not an attribute of '$qualifier'",
                        )
                    }
                }
                if (key in derivedKeys) dependencies += key
            }
        } catch (e: DeriveUnsupported) {
            return Parsed.Bad(e.message ?: "unresolvable reference")
        }
        return Parsed.Ok(expression, dependencies.toList())
    }

    private data class Reference(
        val name: String,
        val entity: String?,
    )

    private fun references(expression: WhereRuleExpression): List<Reference> =
        when (expression) {
            is WhereRuleExpression.Comparison -> references(expression.left) + references(expression.right)
            is WhereRuleExpression.And -> references(expression.left) + references(expression.right)
            is WhereRuleExpression.Or -> references(expression.left) + references(expression.right)
            is WhereRuleExpression.Not -> references(expression.operand)
            is WhereRuleExpression.SelfAttribute -> listOf(Reference(expression.name, null))
            is WhereRuleExpression.QualifiedSelfAttribute -> listOf(Reference(expression.name, expression.entity))
            is WhereRuleExpression.Exists -> listOf(Reference(expression.attribute, null))
            is WhereRuleExpression.StringLiteral,
            is WhereRuleExpression.IntegerLiteral,
            is WhereRuleExpression.RealLiteral,
            -> emptyList()
            // Unreachable in practice (the builder is invoked without allowInstanceGraphFunctions),
            // but the sealed `when` must be exhaustive and must never silently accept these.
            is WhereRuleExpression.SelfInstance,
            is WhereRuleExpression.UsedIn,
            is WhereRuleExpression.SizeOf,
            -> throw DeriveUnsupported(INSTANCE_GRAPH_UNSUPPORTED)
        }

    @Suppress("LongParameterList")
    private fun planOne(
        attr: ResolvedDerivedAttribute,
        parsed: Parsed,
        results: Map<String, DerivedPlan>,
        scope: Map<String, Operand>,
        entityName: String,
        packageName: String,
        definedTypes: Map<String, ExpressDefinedType>,
    ): DerivedPlan {
        val context = "entity '$entityName' DERIVE '${attr.name}'"
        val ok = parsed as? Parsed.Ok ?: return DerivedPlan.Unsupported(attr, (parsed as Parsed.Bad).reason)
        ok.dependencies.forEach { dependency ->
            if (results[dependency] is DerivedPlan.Unsupported) {
                val dependencyName = (results.getValue(dependency)).attr.name
                return DerivedPlan.Unsupported(attr, "depends on unsupported DERIVE '$dependencyName'")
            }
        }
        return try {
            val declared = resolveKotlinTypeName(attr.declaredType, packageName, context, definedTypes)
            val analysis = analyze(ok.expression, declared, scope, context)
            val property =
                PropertySpec
                    .builder(propertyNameOf(attr.name), analysis.type.copy(nullable = analysis.nullable))
                    .getter(FunSpec.getterBuilder().addStatement("return·%L", analysis.code).build())
                    .addKdoc("%L", kdocFor(attr))
                    .build()
            DerivedPlan.Supported(attr, property)
        } catch (e: DeriveUnsupported) {
            DerivedPlan.Unsupported(attr, e.message ?: "unsupported")
        } catch (e: CodeGenException) {
            DerivedPlan.Unsupported(attr, e.message ?: "unsupported declared type")
        }
    }

    private fun analyze(
        expression: WhereRuleExpression,
        declared: TypeName,
        scope: Map<String, Operand>,
        context: String,
    ): Analysis {
        val aliasName =
            when (expression) {
                is WhereRuleExpression.SelfAttribute -> expression.name
                is WhereRuleExpression.QualifiedSelfAttribute -> expression.name
                else -> null
            }
        val code: CodeBlock
        val type: StaticType
        val nullable: Boolean
        if (aliasName != null) {
            val operand = scope[aliasName.lowercase()] ?: unsupported("SELF.$aliasName does not resolve")
            code = CodeBlock.of("%L", operand.kotlinName)
            type = operand.type
            nullable = operand.nullable
        } else {
            val (translated, translatedType) = translate(expression, scope, context)
            code = translated
            type = translatedType
            nullable = false
        }
        val declaredStatic = staticTypeOf(declared.copy(nullable = false))
        val promoted = type == StaticType.Integer && declaredStatic == StaticType.Real
        if (type != declaredStatic && !promoted) {
            throw DeriveUnsupported(
                "$context: initializer result type ${describe(type)} does not match the declared type " +
                    declared.copy(nullable = false),
            )
        }
        val finalCode =
            when {
                !promoted -> code
                nullable -> CodeBlock.of("%L?.toDouble()", code)
                else -> CodeBlock.of("(%L).toDouble()", code)
            }
        return Analysis(finalCode, declared.copy(nullable = false), nullable, declaredStatic)
    }

    /**
     * Translates one expression (no top-level alias handling) to Kotlin; optional attributes are
     * only allowed inside `EXISTS()` -- EXPRESS's three-valued logic is not modeled.
     */
    internal fun translate(
        expression: WhereRuleExpression,
        scope: Map<String, Operand>,
        errorContext: String,
    ): Pair<CodeBlock, StaticType> =
        try {
            Translator(scope).go(expression)
        } catch (e: DeriveUnsupported) {
            throw DeriveUnsupported("$errorContext: ${e.message}")
        }

    private class Translator(
        private val scope: Map<String, Operand>,
    ) {
        fun go(expression: WhereRuleExpression): Pair<CodeBlock, StaticType> =
            when (expression) {
                is WhereRuleExpression.SelfAttribute -> reference(expression.name)
                is WhereRuleExpression.QualifiedSelfAttribute -> reference(expression.name)
                is WhereRuleExpression.Exists -> {
                    val operand = lookup(expression.attribute)
                    val code =
                        if (operand.nullable) {
                            CodeBlock.of(
                                "(%L·!=·null)",
                                operand.kotlinName,
                            )
                        } else {
                            CodeBlock.of("true")
                        }
                    code to StaticType.Bool
                }
                is WhereRuleExpression.StringLiteral -> {
                    if (expression.value.any { it < ' ' }) unsupported("string literal containing control characters")
                    CodeBlock.of("%S", expression.value) to StaticType.Str
                }
                is WhereRuleExpression.IntegerLiteral -> {
                    if (expression.value !in Int.MIN_VALUE..Int.MAX_VALUE) {
                        unsupported(
                            "integer literal ${expression.value} is outside the Kotlin Int range (INTEGER maps to Int)",
                        )
                    }
                    CodeBlock.of("%L", expression.value.toInt()) to StaticType.Integer
                }
                is WhereRuleExpression.RealLiteral -> {
                    if (!expression.value.isFinite()) unsupported("real literal ${expression.value} is not finite")
                    CodeBlock.of("%L", expression.value.toString()) to StaticType.Real
                }
                is WhereRuleExpression.Not -> {
                    val (operand, type) = go(expression.operand)
                    requireBool(type, "NOT")
                    CodeBlock.of("(!%L)", operand) to StaticType.Bool
                }
                is WhereRuleExpression.And -> logical(expression.left, expression.right, "&&", "AND")
                is WhereRuleExpression.Or -> logical(expression.left, expression.right, "||", "OR")
                is WhereRuleExpression.Comparison -> comparison(expression)
                is WhereRuleExpression.SelfInstance,
                is WhereRuleExpression.UsedIn,
                is WhereRuleExpression.SizeOf,
                -> unsupported(INSTANCE_GRAPH_UNSUPPORTED)
            }

        private fun lookup(name: String): Operand =
            scope[name.lowercase()] ?: unsupported("SELF.$name does not resolve to an explicit or derived attribute")

        private fun reference(name: String): Pair<CodeBlock, StaticType> {
            val operand = lookup(name)
            if (operand.nullable) {
                unsupported(
                    "optional attribute '$name' is used outside EXISTS() or a plain alias " +
                        "(three-valued logic is not modeled)",
                )
            }
            return CodeBlock.of("%L", operand.kotlinName) to operand.type
        }

        private fun requireBool(
            type: StaticType,
            operator: String,
        ) {
            if (type !=
                StaticType.Bool
            ) {
                unsupported("type mismatch: $operator needs boolean operands but got ${describe(type)}")
            }
        }

        private fun logical(
            left: WhereRuleExpression,
            right: WhereRuleExpression,
            kotlinOperator: String,
            expressOperator: String,
        ): Pair<CodeBlock, StaticType> {
            val (leftCode, leftType) = go(left)
            val (rightCode, rightType) = go(right)
            requireBool(leftType, expressOperator)
            requireBool(rightType, expressOperator)
            return CodeBlock.of("(%L·$kotlinOperator·%L)", leftCode, rightCode) to StaticType.Bool
        }

        // Integer operands are promoted exactly like WhereRuleEvaluator.asDouble; Double ones stay as they are.
        private fun asDouble(
            code: CodeBlock,
            type: StaticType,
        ): CodeBlock =
            if (type ==
                StaticType.Integer
            ) {
                CodeBlock.of("(%L).toDouble()", code)
            } else {
                CodeBlock.of("(%L)", code)
            }

        private fun comparison(expression: WhereRuleExpression.Comparison): Pair<CodeBlock, StaticType> {
            val (leftCode, leftType) = go(expression.left)
            val (rightCode, rightType) = go(expression.right)
            val operator =
                when (expression.operator) {
                    ComparisonOperator.LESS_THAN -> "<"
                    ComparisonOperator.LESS_THAN_OR_EQUAL -> "<="
                    ComparisonOperator.GREATER_THAN -> ">"
                    ComparisonOperator.GREATER_THAN_OR_EQUAL -> ">="
                    ComparisonOperator.EQUAL -> "=="
                    ComparisonOperator.NOT_EQUAL -> "!="
                }
            val numeric = setOf<StaticType>(StaticType.Integer, StaticType.Real)
            val comparable =
                when {
                    leftType in numeric && rightType in numeric ->
                        CodeBlock.of(
                            "%L.compareTo(%L)",
                            asDouble(leftCode, leftType),
                            asDouble(rightCode, rightType),
                        )
                    leftType == StaticType.Str &&
                        rightType == StaticType.Str ||
                        leftType == StaticType.Bool &&
                        rightType == StaticType.Bool ->
                        CodeBlock.of("(%L).compareTo(%L)", leftCode, rightCode)
                    else ->
                        unsupported(
                            "type mismatch: cannot compare ${describe(leftType)} with ${describe(rightType)}",
                        )
                }
            return CodeBlock.of("(%L·$operator·0)", comparable) to StaticType.Bool
        }
    }

    private fun describe(type: StaticType): String =
        when (type) {
            StaticType.Str -> "STRING"
            StaticType.Integer -> "INTEGER"
            StaticType.Real -> "REAL"
            StaticType.Bool -> "BOOLEAN"
            is StaticType.Opaque -> "${type.typeName} (entity, aggregate, enumeration or SELECT)"
        }

    private fun staticTypeOf(type: TypeName): StaticType =
        when (type) {
            STRING -> StaticType.Str
            INT -> StaticType.Integer
            DOUBLE -> StaticType.Real
            BOOLEAN -> StaticType.Bool
            else -> StaticType.Opaque(type)
        }

    private fun propertyNameOf(expressName: String): String =
        NamingConventions.escapeIfKotlinKeyword(NamingConventions.toPropertyName(expressName))

    private fun kdocFor(attr: ResolvedDerivedAttribute): String =
        buildString {
            append("DERIVE ${attr.name} := ${attr.expressionText.replace(Regex("\\s+"), " ").trim()}\n")
            append("\nDeclared by entity '${attr.declaringEntity}'.")
            when (val override = attr.overrides) {
                is ResolvedDerivedAttribute.Override.InheritedExplicit ->
                    append(
                        " Redeclares the inherited explicit attribute " +
                            "'${override.originalDeclaringEntity}.${attr.name}'" +
                            " (SELF\\${override.originalDeclaringEntity}.${attr.name}); its Part 21 position is '*'.",
                    )
                is ResolvedDerivedAttribute.Override.InheritedDerived ->
                    append(" Overrides the DERIVE of '${override.originalDeclaringEntity}'.")
                null -> Unit
            }
            append(
                "\nComputed from the other attributes; not part of equals/hashCode/copy/toString and " +
                    "not written to Part 21.\n",
            )
        }.let(::sanitizeKdoc)
}
