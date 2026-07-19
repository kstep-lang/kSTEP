package dev.kstep.express.semantic

import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.grammar.ExpressParser
import org.antlr.v4.runtime.ParserRuleContext

/**
 * Walks the ANTLR parse tree ([ExpressParser.SyntaxContext]) produced by
 * [ExpressParserFactory] into the [SemanticModel] AST.
 *
 * Uses plain hand-written functions over the typed ANTLR context objects, not the
 * generated `ExpressBaseVisitor`/`ExpressBaseListener`: the grammar's `expression`/`stmt`
 * subtrees are never visited (WHERE-rule and DERIVE-initializer bodies are captured as raw
 * source substrings, see [verbatim]), and each schema needs a two-pass walk (symbol table first, then type
 * resolution) with different data threaded through each pass — direct `ctx.xxx()`
 * accessor calls fit that more directly than the visitor's single-return-type dispatch.
 *
 * Two-pass per schema: [collectSymbols] gathers entity/type names from top-level
 * declarations only (pass 1), then [buildEntity] resolves attribute types against that
 * symbol table (pass 2). This is required both for forward references (an entity may
 * reference an entity declared later in the same schema) and because `namedTypes` is a
 * grammar-level ambiguity (`entityRef | typeRef`, both reducing to a bare `SimpleId`) that
 * ANTLR resolves silently without regard to what the name actually denotes — this walker
 * therefore never branches on `namedTypes().entityRef()` vs. `.typeRef()`, only on the raw
 * text against the symbol table.
 */
object ExpressSemanticModelBuilder {
    private const val MAX_TYPE_NESTING_DEPTH = 32

    fun build(source: String): SemanticModel {
        val tree = ExpressParserFactory.parse(source)
        return SemanticModel(tree.schemaDecl().map { buildSchema(it, source) })
    }

    // Symbol-table scope is per-schemaDecl, not global across the `syntax` unit: `syntax`
    // allows multiple SCHEMA blocks, each an independent namespace (no cross-schema
    // USE/REFERENCE resolution in V1). collectSymbols is therefore called fresh here, not
    // once for the whole tree.
    private fun buildSchema(
        ctx: ExpressParser.SchemaDeclContext,
        source: String,
    ): ExpressSchema {
        val name = ctx.schemaId().text
        val bodyDeclarations = ctx.schemaBody().schemaBodyDeclaration()
        val symbols = collectSymbols(bodyDeclarations)
        val entities =
            bodyDeclarations
                .mapNotNull { it.declaration()?.entityDecl() }
                .map { buildEntity(it, symbols, source) }
        val definedTypes =
            bodyDeclarations
                .mapNotNull { it.declaration()?.typeDecl() }
                .map { buildDefinedType(it, source) }
        return ExpressSchema(
            name = name,
            entities = entities,
            definedTypes = definedTypes,
            sourceLine = ctx.start.line,
        )
    }

    // EXPRESS identifiers are case-insensitive per ISO 10303-11, but the vendored lexer's
    // SimpleId rule is case-sensitive. Symbol-table keys are lowercase-normalized for
    // membership checks while the map values keep the declaration's original casing, so
    // generated names stay consistent regardless of which casing a reference uses.
    private fun collectSymbols(bodyDeclarations: List<ExpressParser.SchemaBodyDeclarationContext>): SchemaSymbols {
        val entityNames = mutableMapOf<String, String>()
        val definedTypeNames = mutableMapOf<String, String>()
        for (bodyDeclaration in bodyDeclarations) {
            val declaration = bodyDeclaration.declaration() ?: continue
            declaration.entityDecl()?.let { entityDecl ->
                val entityName = entityDecl.entityHead().entityId().text
                entityNames[entityName.lowercase()] = entityName
            }
            declaration.typeDecl()?.let { typeDecl ->
                val typeName = typeDecl.typeId().text
                definedTypeNames[typeName.lowercase()] = typeName
            }
        }
        return SchemaSymbols(entityNames, definedTypeNames)
    }

    private fun buildEntity(
        ctx: ExpressParser.EntityDeclContext,
        symbols: SchemaSymbols,
        source: String,
    ): ExpressEntity {
        val name = ctx.entityHead().entityId().text
        val subsuper = ctx.entityHead().subsuper()
        val supertypeConstraint = subsuper.supertypeConstraint()
        val isAbstract =
            supertypeConstraint != null &&
                (
                    supertypeConstraint.abstractEntityDeclaration() != null ||
                        supertypeConstraint.abstractSupertypeDeclaration() != null
                )
        val supertypes = subsuper.subtypeDeclaration()?.entityRef()?.map { it.text } ?: emptyList()

        val attributes =
            ctx.entityBody().explicitAttr().flatMap { explicitAttrCtx ->
                buildAttributesFor(explicitAttrCtx, symbols, name, source)
            }
        val derivedAttributes =
            ctx
                .entityBody()
                .deriveClause()
                ?.derivedAttr()
                ?.map { buildDerivedAttribute(it, symbols, name, source) } ?: emptyList()
        val inverseAttributes =
            ctx
                .entityBody()
                .inverseClause()
                ?.inverseAttr()
                ?.map { buildInverseAttribute(it, source) } ?: emptyList()
        val uniqueRules =
            ctx
                .entityBody()
                .uniqueClause()
                ?.uniqueRule()
                ?.map { buildUniqueRule(it, source) } ?: emptyList()
        val whereRules =
            ctx
                .entityBody()
                .whereClause()
                ?.domainRule()
                ?.map { buildWhereRule(it, source) } ?: emptyList()

        return ExpressEntity(
            name = name,
            isAbstract = isAbstract,
            supertypes = supertypes,
            supertypeConstraintRawText = supertypeConstraint?.let { verbatim(it, source) },
            attributes = attributes,
            derivedAttributes = derivedAttributes,
            inverseAttributes = inverseAttributes,
            uniqueRules = uniqueRules,
            whereRules = whereRules,
            sourceLine = ctx.start.line,
        )
    }

    // One explicitAttr can share its type across multiple attribute ids
    // (e.g. "a, b, c : STRING;"), so this yields N attributes from one ANTLR context.
    private fun buildAttributesFor(
        ctx: ExpressParser.ExplicitAttrContext,
        symbols: SchemaSymbols,
        entityName: String,
        source: String,
    ): List<ExpressAttribute> {
        val isOptional = ctx.OPTIONAL() != null
        val declaredType = mapParameterType(ctx.parameterType(), symbols, entityName, source)
        return ctx.attributeDecl().map { attributeDeclCtx ->
            val attributeId = attributeDeclCtx.attributeId()
            if (attributeId != null) {
                ExpressAttribute.Explicit(
                    name = attributeId.text,
                    declaredType = declaredType,
                    isOptional = isOptional,
                    sourceLine = attributeDeclCtx.start.line,
                )
            } else {
                ExpressAttribute.Redeclared(
                    rawText = verbatim(attributeDeclCtx, source),
                    sourceLine = attributeDeclCtx.start.line,
                )
            }
        }
    }

    // Only resolves the single-level simple-alias case (concreteTypes -> simpleTypes); every
    // other underlyingType shape (aggregationTypes, typeRef, constructedTypes) yields a null
    // underlyingSimpleType, deliberately not chased further — see ExpressDefinedType.
    private fun buildDefinedType(
        ctx: ExpressParser.TypeDeclContext,
        source: String,
    ): ExpressDefinedType {
        val simpleTypes = ctx.underlyingType().concreteTypes()?.simpleTypes()
        return ExpressDefinedType(
            name = ctx.typeId().text,
            underlyingSimpleType = simpleTypes?.let { mapSimpleTypes(it, source) },
        )
    }

    private fun buildWhereRule(
        ctx: ExpressParser.DomainRuleContext,
        source: String,
    ): ExpressWhereRule =
        ExpressWhereRule(
            label = ctx.ruleLabelId()?.text,
            expressionText = verbatim(ctx.expression(), source),
            sourceLine = ctx.start.line,
        )

    // derivedAttr shares attributeDecl with explicitAttr, which can itself be a redeclared
    // (SELF\entity.attr) name -- a subtype overriding an inherited DERIVE with a more
    // specific type is legal EXPRESS. Mirrors buildAttributesFor's Explicit/Redeclared split
    // rather than failing the whole build on a construct the codebase already knows how to
    // capture for explicit attributes.
    private fun buildDerivedAttribute(
        ctx: ExpressParser.DerivedAttrContext,
        symbols: SchemaSymbols,
        entityName: String,
        source: String,
    ): ExpressDerivedAttribute {
        val attributeId = ctx.attributeDecl().attributeId()
        val declaredType = mapParameterType(ctx.parameterType(), symbols, entityName, source)
        val expressionText = verbatim(ctx.expression(), source)
        return if (attributeId != null) {
            ExpressDerivedAttribute.Explicit(
                name = attributeId.text,
                declaredType = declaredType,
                expressionText = expressionText,
                sourceLine = ctx.start.line,
            )
        } else {
            ExpressDerivedAttribute.Redeclared(
                rawText = verbatim(ctx.attributeDecl(), source),
                declaredType = declaredType,
                expressionText = expressionText,
                sourceLine = ctx.start.line,
            )
        }
    }

    // Same Explicit/Redeclared split as buildDerivedAttribute, for the same reason --
    // inverseAttr shares attributeDecl too.
    private fun buildInverseAttribute(
        ctx: ExpressParser.InverseAttrContext,
        source: String,
    ): ExpressInverseAttribute {
        val attributeId = ctx.attributeDecl().attributeId()
        val inverseAttrType = ctx.inverseAttrType()
        val kind =
            when {
                inverseAttrType.SET() != null -> InverseAggregationKind.SET
                inverseAttrType.BAG() != null -> InverseAggregationKind.BAG
                else -> null
            }
        val boundsRawText = inverseAttrType.boundSpec()?.let { verbatim(it, source) }
        val targetEntity = inverseAttrType.entityRef().text
        val forEntity = ctx.entityRef()?.text
        val forAttribute = ctx.attributeRef().text
        return if (attributeId != null) {
            ExpressInverseAttribute.Explicit(
                name = attributeId.text,
                kind = kind,
                boundsRawText = boundsRawText,
                targetEntity = targetEntity,
                forEntity = forEntity,
                forAttribute = forAttribute,
                sourceLine = ctx.start.line,
            )
        } else {
            ExpressInverseAttribute.Redeclared(
                rawText = verbatim(ctx.attributeDecl(), source),
                kind = kind,
                boundsRawText = boundsRawText,
                targetEntity = targetEntity,
                forEntity = forEntity,
                forAttribute = forAttribute,
                sourceLine = ctx.start.line,
            )
        }
    }

    private fun buildUniqueRule(
        ctx: ExpressParser.UniqueRuleContext,
        source: String,
    ): ExpressUniqueRule =
        ExpressUniqueRule(
            label = ctx.ruleLabelId()?.text,
            referencedAttributes = ctx.referencedAttribute().map { verbatim(it, source) },
            sourceLine = ctx.start.line,
        )

    private fun mapParameterType(
        ctx: ExpressParser.ParameterTypeContext,
        symbols: SchemaSymbols,
        entityName: String,
        source: String,
        depth: Int = 0,
    ): ExpressType {
        ctx.simpleTypes()?.let { return mapSimpleTypes(it, source) }

        ctx.namedTypes()?.let { namedTypesCtx ->
            val referencedName = namedTypesCtx.text
            val key = referencedName.lowercase()
            symbols.entityNamesByLowerCase[key]?.let { return EntityTypeRef(it) }
            symbols.definedTypeNamesByLowerCase[key]?.let { return DefinedTypeRef(it) }
            throw SemanticModelException(
                "attribute type '$referencedName' in entity '$entityName' is not a known entity or TYPE " +
                    "in this schema (cross-schema USE/REFERENCE resolution is out of scope for V1)",
            )
        }

        val generalizedTypes =
            ctx.generalizedTypes()
                ?: throw SemanticModelException(
                    "unrecognized parameterType at line ${ctx.start.line} in entity '$entityName'",
                )
        val generalAggregationTypes = generalizedTypes.generalAggregationTypes()
        if (generalAggregationTypes != null) {
            if (depth >= MAX_TYPE_NESTING_DEPTH) {
                throw SemanticModelException(
                    "aggregation nesting exceeds $MAX_TYPE_NESTING_DEPTH levels in entity '$entityName'",
                )
            }
            return mapGeneralAggregationTypes(generalAggregationTypes, symbols, entityName, source, depth)
        }
        return UnsupportedType(
            rawText = verbatim(generalizedTypes, source),
            reasonHint = "AGGREGATE/GENERIC/GENERIC_ENTITY parameter types are out of scope for V1 codegen",
        )
    }

    // The only recursive call in this walker: SET OF SET OF ... nesting. Every other loop
    // (schemaDecl+, schemaBodyDeclaration*, explicitAttr*, attributeDecl (',' attributeDecl)*,
    // domainRule ';' ...) is a flat Kleene-star, not a recursive rule invocation.
    //
    // Note this guard alone does NOT cover the DoS surface for build(): the underlying
    // ANTLR grammar rule this mirrors (parameterType -> generalizedTypes ->
    // generalAggregationTypes -> ... -> parameterType) is itself genuine recursive-descent
    // parsing that runs to completion inside ExpressParserFactory.parse *before* this
    // tree-walk ever starts, so a deeply-enough-nested input can exhaust the JVM stack
    // during parsing regardless of MAX_TYPE_NESTING_DEPTH here. That case is handled at the
    // parse boundary (see the StackOverflowError -> ExpressSyntaxException conversion in
    // ExpressParserFactory.parse). MAX_TYPE_NESTING_DEPTH is defense-in-depth for trees that
    // do parse successfully but are still pathologically deep, and keeps this walker's own
    // stack usage bounded and its failure mode a structured SemanticModelException.
    private fun mapGeneralAggregationTypes(
        ctx: ExpressParser.GeneralAggregationTypesContext,
        symbols: SchemaSymbols,
        entityName: String,
        source: String,
        depth: Int,
    ): AggregationType {
        ctx.generalSetType()?.let { setType ->
            return AggregationType(
                kind = AggregationKind.SET,
                elementType = mapParameterType(setType.parameterType(), symbols, entityName, source, depth + 1),
                boundsRawText = setType.boundSpec()?.let { verbatim(it, source) },
                unique = false,
            )
        }
        ctx.generalListType()?.let { listType ->
            return AggregationType(
                kind = AggregationKind.LIST,
                elementType = mapParameterType(listType.parameterType(), symbols, entityName, source, depth + 1),
                boundsRawText = listType.boundSpec()?.let { verbatim(it, source) },
                unique = listType.UNIQUE() != null,
            )
        }
        ctx.generalBagType()?.let { bagType ->
            return AggregationType(
                kind = AggregationKind.BAG,
                elementType = mapParameterType(bagType.parameterType(), symbols, entityName, source, depth + 1),
                boundsRawText = bagType.boundSpec()?.let { verbatim(it, source) },
                unique = false,
            )
        }
        val arrayType =
            ctx.generalArrayType()
                ?: error(
                    "generalAggregationTypes matched none of its four alternatives — grammar/generated-code mismatch",
                )
        return AggregationType(
            kind = AggregationKind.ARRAY,
            elementType = mapParameterType(arrayType.parameterType(), symbols, entityName, source, depth + 1),
            boundsRawText = arrayType.boundSpec()?.let { verbatim(it, source) },
            unique = arrayType.UNIQUE() != null,
        )
    }

    private fun mapSimpleTypes(
        ctx: ExpressParser.SimpleTypesContext,
        source: String,
    ): ExpressType {
        ctx.stringType()?.let { stringType ->
            return StringType(
                widthText = stringType.widthSpec()?.width()?.let { verbatim(it, source) },
                fixed = stringType.widthSpec()?.FIXED() != null,
            )
        }
        ctx.booleanType()?.let { return BooleanType }
        ctx.integerType()?.let { return IntegerType }
        ctx.logicalType()?.let { return LogicalType }
        ctx.numberType()?.let { return NumberType }
        ctx.realType()?.let { realType ->
            return RealType(precisionText = realType.precisionSpec()?.let { verbatim(it, source) })
        }
        ctx.binaryType()?.let { binaryType ->
            return BinaryType(
                widthText = binaryType.widthSpec()?.width()?.let { verbatim(it, source) },
                fixed = binaryType.widthSpec()?.FIXED() != null,
            )
        }
        error("simpleTypes matched none of its seven alternatives — grammar/generated-code mismatch")
    }

    // Whitespace/EmbeddedRemark/TailRemark are routed to hidden channels 1/2 in the
    // lexer, so ParserRuleContext.getText() concatenates only on-channel tokens with no
    // separators (would mangle "SELF.level > 0" into "SELF.level>0"). Raw source substring
    // extraction from token offsets is exact instead. This is an O(1) substring operation,
    // not a tree walk, so it carries none of the (pre-existing, Welle-1) recursion risk
    // that ANTLR's own left-recursive expression/term/factor rules could in principle have
    // on a maliciously deep WHERE-clause expression — this builder never recurses into
    // those subtrees itself.
    private fun verbatim(
        ctx: ParserRuleContext,
        source: String,
    ): String = source.substring(ctx.start.startIndex, ctx.stop.stopIndex + 1)

    private data class SchemaSymbols(
        val entityNamesByLowerCase: Map<String, String>,
        val definedTypeNamesByLowerCase: Map<String, String>,
    )
}
