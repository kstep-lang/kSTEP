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

    fun build(tree: ExpressParser.ExpressionContext): WhereRuleExpression = buildExpression(tree, 0)

    private fun checkDepth(depth: Int) {
        if (depth > MAX_EXPRESSION_NESTING_DEPTH) {
            throw UnsupportedWhereExpressionException(
                "WHERE-rule expression nesting exceeds $MAX_EXPRESSION_NESTING_DEPTH levels",
            )
        }
    }

    private fun unsupported(construct: String): Nothing =
        throw UnsupportedWhereExpressionException(
            "WHERE-rule expression uses '$construct', which is outside the supported subset " +
                "(comparisons, SELF.attribute references, AND/OR/NOT, string/integer/real literals)",
        )

    // expression : simpleExpression (relOpExtended simpleExpression)? — the grammar permits
    // at most one comparison per `expression`; `a > 0 AND b < 1` is not legal EXPRESS without
    // parenthesizing each comparison (`(a > 0) AND (b < 1)`), so AND/OR/NOT combinators are
    // only ever reached here through buildSimpleExpression/buildTerm/buildUnary on
    // parenthesized sub-expressions, never by looping at this level.
    private fun buildExpression(
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
        if (ctx.entityConstructor() != null) unsupported("entity constructor")
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
        val primary = ctx.primary() ?: error("simpleFactorExpression matched neither '(' expression ')' nor primary")
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
        unsupported("unary '${unaryOp.text}' operator on anything other than a directly-nested integer/real literal")
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
            unsupported(
                "function call '${qualifiableFactor.functionCall()!!.text}' (EXISTS/SIZEOF/user-defined functions)",
            )
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

    private fun buildConstantFactor(
        ctx: ExpressParser.ConstantFactorContext,
        qualifiers: List<ExpressParser.QualifierContext>,
    ): WhereRuleExpression {
        val builtIn = ctx.builtInConstant()
        if (builtIn != null && builtIn.SELF() != null) {
            if (qualifiers.size == 1 && qualifiers[0].attributeQualifier() != null) {
                val attributeName =
                    qualifiers[0]
                        .attributeQualifier()!!
                        .attributeRef()
                        .attributeId()
                        .text
                return WhereRuleExpression.SelfAttribute(attributeName)
            }
            unsupported(
                "SELF reference without exactly one attribute qualifier (whole-instance reference, " +
                    "group qualifier, index qualifier, or multi-level attribute chain)",
            )
        }
        if (builtIn != null) unsupported("built-in constant '${builtIn.text}'")
        unsupported("named CONSTANT reference '${ctx.text}'")
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
