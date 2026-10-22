package dev.kstep.express.validation

import dev.kstep.express.grammar.ExpressParser

/**
 * Walks the ANTLR `expression` parse tree (as produced by
 * `dev.kstep.express.ExpressParserFactory.parseExpression`, re-parsing the verbatim WHERE-rule
 * text `dev.kstep.express.semantic.ExpressSemanticModelBuilder` already extracted once) into a
 * [WhereRuleExpression] — the AST of the small, supported subset. Throws
 * [UnsupportedWhereExpressionException] for anything outside that subset instead of silently
 * mis-modeling it.
 *
 * The full EXPRESS `expression` grammar is far richer than what real WHERE rules need
 * (arithmetic, aggregates, function calls, entity construction, `QUERY`...); this builder
 * recognizes exactly the supported subset and rejects everything else by construction —
 * every branch not explicitly handled falls through to a named [UnsupportedWhereExpressionException].
 *
 * Recursion here is *indirect*: `buildExpression` -> `buildSimpleExpression` -> `buildTerm`
 * -> `buildFactor` -> `buildSimpleFactor` -> `buildSimpleFactorExpression` -> back to
 * `buildExpression` (via a parenthesized sub-expression), six non-recursive-looking hops
 * before the cycle closes. `depth` is threaded explicitly through every one of those hops
 * (not a self-resetting default parameter, which would only work if every hop remembered to
 * forward it) so a pathologically deep, but syntactically valid, WHERE-rule expression (e.g.
 * many nested parens) cannot exhaust the JVM stack walking the tree — mirroring the
 * `MAX_TYPE_NESTING_DEPTH` guard in `ExpressSemanticModelBuilder`.
 */
object WhereRuleExpressionBuilder {
    private const val MAX_EXPRESSION_NESTING_DEPTH = 128
    private const val MAX_CHAIN_QUALIFIERS = 16

    /**
     * Opt-in extensions of the supported subset. The default keeps the WHERE-rule behaviour
     * unchanged; DERIVE initializers (evaluator and codegen, ADR-0021) enable
     * [allowGroupQualifiedSelf] so `SELF\entity.attr` becomes a
     * [WhereRuleExpression.QualifiedSelfAttribute]. WHERE rules over a whole population
     * enable [allowInstanceGraphFunctions] (ADR-0022) so `SIZEOF(USEDIN(SELF, '<role>'))`
     * becomes a [WhereRuleExpression.SizeOf] of a [WhereRuleExpression.UsedIn]; without it
     * both functions stay unsupported, exactly as before.
     */
    data class BuildOptions(
        val allowGroupQualifiedSelf: Boolean = false,
        val allowInstanceGraphFunctions: Boolean = false,
        /**
         * Recognise the fixed AP242 schema functions (ADR-0023): `get_name_value(SELF)` and siblings
         * and the exact `acyclic_product_definition_relationship(SELF, [...], '...')` form.
         */
        val schemaFunctionIntrinsics: SchemaFunctionIntrinsicSet = SchemaFunctionIntrinsicSet.NONE,
    )

    fun build(
        tree: ExpressParser.ExpressionContext,
        options: BuildOptions = BuildOptions(),
    ): WhereRuleExpression = Walker(options).buildExpression(tree, 0)

    private fun checkDepth(depth: Int) {
        if (depth > MAX_EXPRESSION_NESTING_DEPTH) {
            throw UnsupportedWhereExpressionException(
                "expression nesting exceeds $MAX_EXPRESSION_NESTING_DEPTH levels",
            )
        }
    }

    private fun unsupported(construct: String): Nothing =
        throw UnsupportedWhereExpressionException(
            "expression uses '$construct', which is outside the supported subset " +
                "(comparisons, SELF.attribute references, AND/OR/NOT, string/integer/real literals, " +
                "EXISTS(<attribute>))",
        )

    private class Walker(
        private val options: BuildOptions,
    ) {
        // expression : simpleExpression (relOpExtended simpleExpression)? — the grammar permits
        // at most one comparison per `expression`; `a > 0 AND b < 1` is not legal EXPRESS without
        // parenthesizing each comparison (`(a > 0) AND (b < 1)`), so AND/OR/NOT combinators are
        // only ever reached here through buildSimpleExpression/buildTerm/buildUnary on
        // parenthesized sub-expressions, never by looping at this level.
        fun buildExpression(
            ctx: ExpressParser.ExpressionContext,
            depth: Int,
        ): WhereRuleExpression {
            checkDepth(depth)
            val simpleExpressions = ctx.simpleExpression()
            val relOpExtended = ctx.relOpExtended()
            if (relOpExtended == null) {
                return buildSimpleExpression(simpleExpressions[0], depth + 1)
            }
            if (relOpExtended.IN() != null) unsupported("IN")
            if (relOpExtended.LIKE() != null) unsupported("LIKE")
            val relOp = relOpExtended.relOp() ?: unsupported(relOpExtended.text)
            val operator = mapRelOp(relOp)
            val left = buildSimpleExpression(simpleExpressions[0], depth + 1)
            val right = buildSimpleExpression(simpleExpressions[1], depth + 1)
            return WhereRuleExpression.Comparison(operator, left, right)
        }

        // relOp's eight alternatives are all distinct anonymous-token spellings on a single-token
        // rule (never shared with another operator category the way AND/OR/XOR are), so matching
        // on `.text` here — unlike addLikeOp/multiplicationLikeOp/unaryOp below — carries none of
        // the arithmetic-operator ambiguity risk.
        private fun mapRelOp(ctx: ExpressParser.RelOpContext): ComparisonOperator =
            when (ctx.text) {
                "<" -> ComparisonOperator.LESS_THAN
                "<=" -> ComparisonOperator.LESS_THAN_OR_EQUAL
                ">" -> ComparisonOperator.GREATER_THAN
                ">=" -> ComparisonOperator.GREATER_THAN_OR_EQUAL
                "=" -> ComparisonOperator.EQUAL
                "<>" -> ComparisonOperator.NOT_EQUAL
                else -> unsupported("instance-equality operator '${ctx.text}'")
            }

        // simpleExpression : term (addLikeOp term)* — addLikeOp shares its grammar slot with
        // arithmetic '+'/'-', so OR/XOR are disambiguated via the typed OR()/XOR() accessors,
        // never via `.text` (which '+'/'-' have no named accessor to compare against anyway).
        private fun buildSimpleExpression(
            ctx: ExpressParser.SimpleExpressionContext,
            depth: Int,
        ): WhereRuleExpression {
            checkDepth(depth)
            val terms = ctx.term()
            val addLikeOps = ctx.addLikeOp()
            var result = buildTerm(terms[0], depth + 1)
            for (i in addLikeOps.indices) {
                val opCtx = addLikeOps[i]
                val rhs = buildTerm(terms[i + 1], depth + 1)
                result =
                    when {
                        opCtx.OR() != null -> WhereRuleExpression.Or(result, rhs)
                        opCtx.XOR() != null -> unsupported("XOR")
                        else -> unsupported("arithmetic operator '${opCtx.text}'")
                    }
            }
            return result
        }

        // term : factor (multiplicationLikeOp factor)* — multiplicationLikeOp shares its slot
        // with arithmetic '*'/'/'/'||', so AND is disambiguated via the typed AND() accessor.
        private fun buildTerm(
            ctx: ExpressParser.TermContext,
            depth: Int,
        ): WhereRuleExpression {
            checkDepth(depth)
            val factors = ctx.factor()
            val mulLikeOps = ctx.multiplicationLikeOp()
            var result = buildFactor(factors[0], depth + 1)
            for (i in mulLikeOps.indices) {
                val opCtx = mulLikeOps[i]
                val rhs = buildFactor(factors[i + 1], depth + 1)
                result =
                    when {
                        opCtx.AND() != null -> WhereRuleExpression.And(result, rhs)
                        opCtx.DIV() != null -> unsupported("DIV")
                        opCtx.MOD() != null -> unsupported("MOD")
                        else -> unsupported("operator '${opCtx.text}'")
                    }
            }
            return result
        }

        // factor : simpleFactor ('**' simpleFactor)? — exponentiation has no evaluable meaning
        // in the supported subset (it isn't a boolean combinator or comparison operand shape any
        // real WHERE rule in this fixture needs).
        private fun buildFactor(
            ctx: ExpressParser.FactorContext,
            depth: Int,
        ): WhereRuleExpression {
            checkDepth(depth)
            val simpleFactors = ctx.simpleFactor()
            if (simpleFactors.size > 1) unsupported("exponentiation operator '**'")
            return buildSimpleFactor(simpleFactors[0], depth + 1)
        }

        private fun buildSimpleFactor(
            ctx: ExpressParser.SimpleFactorContext,
            depth: Int,
        ): WhereRuleExpression {
            checkDepth(depth)
            ctx.enumerationReference()?.let { return buildEnumerationReference(it) }
            ctx.simpleFactorExpression()?.let { return buildSimpleFactorExpression(it, depth + 1) }
            ctx.simpleFactorUnaryExpression()?.let { return buildUnary(it, depth + 1) }
            if (ctx.aggregateInitializer() != null) unsupported("aggregate initializer '[...]'")
            ctx.entityConstructor()?.let { constructor ->
                if (options.schemaFunctionIntrinsics == SchemaFunctionIntrinsicSet.AP242) {
                    return buildSchemaFunction(constructor)
                }
                unsupported("function call or entity constructor '${ctx.text}'")
            }
            if (ctx.interval() != null) unsupported("interval expression '{...}'")
            if (ctx.queryExpression() != null) unsupported("QUERY expression")
            error("simpleFactor matched none of its seven alternatives — grammar/generated-code mismatch")
        }

        // enumerationReference : (typeRef '.')? enumerationRef — empirically verified (see
        // kSTEP-Welle-3 report) to be where a *bare* attribute reference actually resolves, not
        // qualifiableFactor.attributeRef() below `primary`: simpleFactor lists
        // `enumerationReference` before `simpleFactorExpression` in its alternatives, and for a
        // standalone SimpleId (or a `name.name` chain) both alternatives can match, so ANTLR's
        // ambiguity resolution always prefers the lower-numbered `enumerationReference` — the
        // `simpleFactorExpression -> primary -> qualifiableFactor -> attributeRef` path is never
        // actually reached for that shape. A bare, unqualified enumerationRef (no leading
        // `typeRef.`) is therefore treated as a bare `SELF.attr`-equivalent attribute reference;
        // a qualified `typeRef.enumerationRef` form is a real enumeration-value/type reference,
        // outside the supported subset.
        private fun buildEnumerationReference(ctx: ExpressParser.EnumerationReferenceContext): WhereRuleExpression {
            if (ctx.typeRef() == null) {
                return WhereRuleExpression.SelfAttribute(ctx.enumerationRef().enumerationId().text)
            }
            unsupported("qualified enumeration or type reference '${ctx.text}'")
        }

        // simpleFactorExpression : '(' expression ')' | primary — the one point where recursion
        // back into buildExpression happens, for a parenthesized sub-expression.
        private fun buildSimpleFactorExpression(
            ctx: ExpressParser.SimpleFactorExpressionContext,
            depth: Int,
        ): WhereRuleExpression {
            checkDepth(depth)
            ctx.expression()?.let { return buildExpression(it, depth + 1) }
            val primary =
                ctx.primary() ?: error("simpleFactorExpression matched neither '(' expression ')' nor primary")
            return buildPrimary(primary, depth + 1)
        }

        // simpleFactorUnaryExpression : unaryOp simpleFactorExpression — unaryOp is '+' | '-' |
        // NOT. NOT wraps a boolean sub-expression. '+'/'-' are constant-folded only when the
        // operand reduces *directly* to an integer/real literal (no deeper nesting) — this is
        // literal constant-folding, not general arithmetic negation; any other '+'/'-' operand
        // (e.g. `-SELF.level`, `-(a + b)`) is unsupported.
        private fun buildUnary(
            ctx: ExpressParser.SimpleFactorUnaryExpressionContext,
            depth: Int,
        ): WhereRuleExpression {
            checkDepth(depth)
            val unaryOp = ctx.unaryOp()
            val operand = ctx.simpleFactorExpression()
            if (unaryOp.NOT() != null) {
                return WhereRuleExpression.Not(buildSimpleFactorExpression(operand, depth + 1))
            }

            val isNegative = unaryOp.text == "-"
            val literal = operand.primary()?.literal()
            literal?.IntegerLiteral()?.let { token ->
                val value = token.text.toLong()
                return WhereRuleExpression.IntegerLiteral(if (isNegative) -value else value)
            }
            literal?.RealLiteral()?.let { token ->
                val value = token.text.toDouble()
                return WhereRuleExpression.RealLiteral(if (isNegative) -value else value)
            }
            unsupported(
                "unary '${unaryOp.text}' operator on anything other than a directly-nested integer/real literal",
            )
        }

        // primary : literal | qualifiableFactor qualifier*
        private fun buildPrimary(
            ctx: ExpressParser.PrimaryContext,
            depth: Int,
        ): WhereRuleExpression {
            checkDepth(depth)
            ctx.literal()?.let { return buildLiteral(it) }
            val qualifiableFactor =
                ctx.qualifiableFactor() ?: error("primary matched neither literal nor qualifiableFactor")
            val qualifiers = ctx.qualifier()

            // qualifiableFactor.attributeRef() (a bare, unqualified SimpleId reached through
            // `primary`) is unreachable in practice: `buildEnumerationReference` above always
            // wins that exact ambiguity one level up, at `simpleFactor`. Kept here anyway,
            // mirroring ExpressSemanticModelBuilder's own precedent of never hard-asserting which
            // ANTLR alternative "loses" a token-level ambiguity — a future grammar edit that
            // reorders simpleFactor's alternatives should not silently regress this case.
            if (qualifiableFactor.attributeRef() != null && qualifiers.isEmpty()) {
                return WhereRuleExpression.SelfAttribute(qualifiableFactor.attributeRef()!!.attributeId().text)
            }

            val constantFactor = qualifiableFactor.constantFactor()
            if (constantFactor != null) {
                return buildConstantFactor(constantFactor, qualifiers)
            }

            if (qualifiableFactor.functionCall() != null) {
                if (qualifiers.isNotEmpty()) {
                    unsupported(
                        "function call qualified with '${qualifiers.joinToString("") { it.text }}' " +
                            "(e.g. EXISTS(SELF.a).b or EXISTS(SELF.a)[1] — only a bare, unqualified " +
                            "function call is supported)",
                    )
                }
                return buildFunctionCall(qualifiableFactor.functionCall()!!, depth)
            }
            if (qualifiableFactor.generalRef() != null) unsupported("reference '${qualifiableFactor.text}'")
            if (qualifiableFactor.population() !=
                null
            ) {
                unsupported("entity population reference '${qualifiableFactor.text}'")
            }
            unsupported(
                "attribute chain or indexed reference '${qualifiableFactor.text}${qualifiers.joinToString(
                    "",
                ) { it.text }}'",
            )
        }

        // functionCall : (builtInFunction | functionRef) actualParameterList? — recognized shapes:
        // EXISTS(<single attribute reference>) and, only with allowInstanceGraphFunctions,
        // SIZEOF(USEDIN(SELF, '<role literal>')). SIZEOF/USEDIN are lexer keywords of
        // builtInFunction (not entityConstructor). Every other built-in, every user-defined
        // functionRef, and any call with the wrong argument shape stays unsupported.
        private fun buildFunctionCall(
            ctx: ExpressParser.FunctionCallContext,
            depth: Int,
        ): WhereRuleExpression {
            val builtIn = ctx.builtInFunction()
            val params = ctx.actualParameterList()?.parameter() ?: emptyList()
            if (builtIn != null && builtIn.EXISTS() != null && params.size == 1) {
                val argument = buildExpression(params[0].expression(), depth + 1)
                val attributeName =
                    (argument as? WhereRuleExpression.SelfAttribute)?.name
                        ?: unsupported(
                            "EXISTS() argument other than a bare attribute or SELF.attribute reference " +
                                "('${ctx.text}')",
                        )
                return WhereRuleExpression.Exists(attributeName)
            }
            if (options.allowInstanceGraphFunctions && builtIn != null) {
                if (builtIn.SIZEOF() != null && params.size == 1) {
                    val operand = buildExpression(params[0].expression(), depth + 1)
                    if (operand !is WhereRuleExpression.UsedIn) {
                        unsupported("SIZEOF() argument other than USEDIN(SELF, '<role>') ('${ctx.text}')")
                    }
                    return WhereRuleExpression.SizeOf(operand)
                }
                if (builtIn.USEDIN() != null && params.size == 2) {
                    requireBareSelf(params[0].expression(), ctx.text)
                    val role =
                        asSimpleStringLiteral(params[1].expression())
                            ?: unsupported("USEDIN() role argument that is not a plain string literal ('${ctx.text}')")
                    return WhereRuleExpression.UsedIn(WhereRuleExpression.SelfInstance, role)
                }
            }
            unsupported(
                "function call '${ctx.text}' (only EXISTS(<attribute>)" +
                    (if (options.allowInstanceGraphFunctions) " and SIZEOF(USEDIN(SELF, '<role>'))" else "") +
                    " is supported)",
            )
        }

        // `name(args)` parses as entityConstructor (it precedes functionCall in simpleFactor), so a
        // user-defined schema function call arrives here. Only the fixed AP242 intrinsics are
        // recognised (ADR-0023); every other name, and every deviating argument shape, stays
        // unsupported and names what was expected.
        private fun buildSchemaFunction(ctx: ExpressParser.EntityConstructorContext): WhereRuleExpression {
            val name = ctx.entityRef().text.lowercase()
            val args = ctx.expression()
            SchemaFunctionIntrinsics.ATTRIBUTE_VALUE_FUNCTIONS[name]?.let {
                if (args.size != 1) unsupported("$name() with ${args.size} arguments (expected exactly SELF)")
                requireBareSelf(args[0], ctx.text)
                return WhereRuleExpression.GetAttributeValue(name)
            }
            if (name == SchemaFunctionIntrinsics.ACYCLIC_FUNCTION) return buildAcyclicCall(ctx, args)
            unsupported(
                "function call '${ctx.text}' (recognised schema functions: " +
                    "${SchemaFunctionIntrinsics.ALL_NAMES.sorted().joinToString()})",
            )
        }

        private fun buildAcyclicCall(
            ctx: ExpressParser.EntityConstructorContext,
            args: List<ExpressParser.ExpressionContext>,
        ): WhereRuleExpression {
            val form =
                "${SchemaFunctionIntrinsics.ACYCLIC_FUNCTION}(SELF, " +
                    "[SELF\\product_definition_relationship.related_product_definition], '<SCHEMA.ENTITY>')"
            if (args.size != 3) unsupported("'${ctx.text}': only the exact form $form is supported")
            requireBareSelf(args[0], ctx.text)
            val elements =
                singleSimpleFactor(args[1])?.aggregateInitializer()?.element()
                    ?: unsupported("'${ctx.text}': second argument is not an aggregate initializer; expected $form")
            val relatives =
                elements.singleOrNull()?.takeIf { it.repetition() == null }?.expression()?.let {
                    Walker(options.copy(allowGroupQualifiedSelf = true)).buildExpression(it, 0)
                }
            val expected =
                relatives as? WhereRuleExpression.QualifiedSelfAttribute
                    ?: unsupported("'${ctx.text}': relatives argument must be $form")
            if (!expected.entity.equals("product_definition_relationship", ignoreCase = true) ||
                !expected.name.equals("related_product_definition", ignoreCase = true)
            ) {
                unsupported("'${ctx.text}': relatives argument must be exactly the related end; expected $form")
            }
            val specific =
                asSimpleStringLiteral(args[2])
                    ?: unsupported("'${ctx.text}': third argument is not a plain string literal; expected $form")
            return WhereRuleExpression.AcyclicCall(specific)
        }

        // Descends expression -> ... -> simpleFactor while every level is a single, operator-free element.
        private fun singleSimpleFactor(
            expression: ExpressParser.ExpressionContext,
        ): ExpressParser.SimpleFactorContext? {
            if (expression.relOpExtended() != null) return null
            val simple = expression.simpleExpression().singleOrNull() ?: return null
            if (simple.addLikeOp().isNotEmpty()) return null
            val term = simple.term().singleOrNull() ?: return null
            if (term.multiplicationLikeOp().isNotEmpty()) return null
            val factor = term.factor().singleOrNull() ?: return null
            return factor.simpleFactor().singleOrNull()
        }

        // The first USEDIN argument must be exactly the unqualified, whole-instance SELF — checked
        // structurally (no operators, no qualifiers) so a bare SELF is never accepted anywhere else.
        private fun requireBareSelf(
            expression: ExpressParser.ExpressionContext,
            callText: String,
        ) {
            val primary = singlePrimary(expression)
            val isBareSelf =
                primary != null &&
                    primary.qualifier().isEmpty() &&
                    primary
                        .qualifiableFactor()
                        ?.constantFactor()
                        ?.builtInConstant()
                        ?.SELF() != null
            if (!isBareSelf) unsupported("USEDIN() first argument other than the bare SELF ('$callText')")
        }

        private fun asSimpleStringLiteral(expression: ExpressParser.ExpressionContext): String? {
            val literal = singlePrimary(expression)?.literal()?.stringLiteral()?.SimpleStringLiteral() ?: return null
            val raw = literal.text
            return raw.substring(1, raw.length - 1).replace("''", "'")
        }

        // Descends expression -> ... -> primary while every level is a single, operator-free,
        // un-parenthesized element; null when the expression is anything more complex.
        private fun singlePrimary(expression: ExpressParser.ExpressionContext): ExpressParser.PrimaryContext? {
            if (expression.relOpExtended() != null) return null
            val simple = expression.simpleExpression().singleOrNull() ?: return null
            if (simple.addLikeOp().isNotEmpty()) return null
            val term = simple.term().singleOrNull() ?: return null
            if (term.multiplicationLikeOp().isNotEmpty()) return null
            val factor = term.factor().singleOrNull() ?: return null
            val simpleFactor = factor.simpleFactor().singleOrNull() ?: return null
            return simpleFactor.simpleFactorExpression()?.primary()
        }

        // SELF-rooted references. Supported shapes: `SELF.attr` and (only with
        // BuildOptions.allowGroupQualifiedSelf) `SELF\entity.attr`. Anything longer or different
        // is rejected naming the first link that cannot be followed and what was still fine
        // before it, e.g. for a two-hop chain `SELF\a.b\c.id`: "unsupported link '\c' after
        // 'SELF\a.b'".
        private fun buildConstantFactor(
            ctx: ExpressParser.ConstantFactorContext,
            qualifiers: List<ExpressParser.QualifierContext>,
        ): WhereRuleExpression {
            val builtIn = ctx.builtInConstant()
            if (builtIn != null && builtIn.SELF() != null) {
                buildChainOrNull(qualifiers)?.let { return it }
                val first = qualifiers.getOrNull(0)
                val second = qualifiers.getOrNull(1)
                val supportedLinks: Int
                var result: WhereRuleExpression? = null
                if (first?.attributeQualifier() != null) {
                    supportedLinks = 1
                    result =
                        WhereRuleExpression.SelfAttribute(
                            first
                                .attributeQualifier()!!
                                .attributeRef()
                                .attributeId()
                                .text,
                        )
                } else if (options.allowGroupQualifiedSelf &&
                    first?.groupQualifier() != null &&
                    second?.attributeQualifier() != null
                ) {
                    supportedLinks = 2
                    result =
                        WhereRuleExpression.QualifiedSelfAttribute(
                            entity = first.groupQualifier()!!.entityRef().text,
                            name =
                                second
                                    .attributeQualifier()!!
                                    .attributeRef()
                                    .attributeId()
                                    .text,
                        )
                } else {
                    supportedLinks = 0
                }
                if (result != null && qualifiers.size == supportedLinks) return result
                if (qualifiers.isEmpty()) {
                    unsupported(
                        "SELF reference without exactly one attribute qualifier (whole-instance reference, " +
                            "group qualifier, index qualifier, or multi-level attribute chain)",
                    )
                }
                val consumed = "SELF" + qualifiers.take(supportedLinks).joinToString("") { it.text }
                throw UnsupportedWhereExpressionException(
                    "unsupported link '${qualifiers[supportedLinks].text}' after '$consumed': " +
                        "SELF reference without exactly one attribute qualifier; navigating through an " +
                        "entity-typed attribute (multi-hop chain), a group qualifier or an index qualifier " +
                        "is outside the supported subset",
                )
            }
            if (builtIn != null) unsupported("built-in constant '${builtIn.text}'")
            unsupported("named CONSTANT reference '${ctx.text}'")
        }

        // SELF\a.x\b.y... : strict (group, attribute) pairs, two or more of them (ADR-0023). A single
        // pair stays the QualifiedSelfAttribute produced by the code above.
        private fun buildChainOrNull(qualifiers: List<ExpressParser.QualifierContext>): WhereRuleExpression? {
            if (!options.allowGroupQualifiedSelf) return null
            if (qualifiers.size < 4 || qualifiers.size % 2 != 0) return null
            if (qualifiers.size > MAX_CHAIN_QUALIFIERS) {
                unsupported("SELF chain with more than ${MAX_CHAIN_QUALIFIERS / 2} links")
            }
            val links = mutableListOf<WhereRuleExpression.ChainLink>()
            for (i in qualifiers.indices step 2) {
                val group = qualifiers[i].groupQualifier()
                val attribute = qualifiers[i + 1].attributeQualifier()
                if (group == null || attribute == null) return null
                links +=
                    WhereRuleExpression.ChainLink(
                        group = group.entityRef().text,
                        attribute = attribute.attributeRef().attributeId().text,
                    )
            }
            return WhereRuleExpression.QualifiedChain(links)
        }

        private fun buildLiteral(ctx: ExpressParser.LiteralContext): WhereRuleExpression {
            ctx.IntegerLiteral()?.let { return WhereRuleExpression.IntegerLiteral(it.text.toLong()) }
            ctx.RealLiteral()?.let { return WhereRuleExpression.RealLiteral(it.text.toDouble()) }
            ctx.stringLiteral()?.let { return buildStringLiteral(it) }
            ctx.logicalLiteral()?.let { unsupported("logical literal '${it.text}' (TRUE/FALSE/UNKNOWN)") }
            ctx.BinaryLiteral()?.let { unsupported("binary literal '${it.text}'") }
            error("literal matched none of its five alternatives — grammar/generated-code mismatch")
        }

        // stringLiteral : SimpleStringLiteral | EncodedStringLiteral. Only the plain
        // single-quoted form is supported; EXPRESS's doubled-single-quote escape convention
        // ('O''Brien' meaning O'Brien) is, per empirical verification, NOT correctly tokenized
        // by the vendored grammar's non-greedy `SimpleStringLiteral: '\'' .*? '\'' ` lexer rule —
        // it lexes 'O''Brien' as two adjacent SimpleStringLiteral tokens ('O' then 'Brien'), which
        // fails to parse as a single expression, so this replace() is defensive/spec-faithful
        // but in practice never sees an unescaped input from a successfully re-parsed rule.
        private fun buildStringLiteral(ctx: ExpressParser.StringLiteralContext): WhereRuleExpression {
            val simple = ctx.SimpleStringLiteral() ?: unsupported("encoded string literal '${ctx.text}'")
            val raw = simple.text
            val inner = raw.substring(1, raw.length - 1)
            return WhereRuleExpression.StringLiteral(inner.replace("''", "'"))
        }
    }
}
