package dev.kstep.express.validation

import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.semantic.ExpressEntity

/**
 * One WHERE-rule domain rule to check, decoupled from [dev.kstep.express.semantic.ExpressWhereRule] so
 * callers outside `dev.kstep.express.semantic` (e.g. `kstep-core`) can supply rules without depending on
 * the semantic-model package.
 */
data class WhereRuleSpec(
    val label: String?,
    val expressionText: String,
    val sourceLine: Int = 0,
)

/** A WHERE rule that evaluated to `false` against the supplied attribute values. */
data class WhereRuleViolation(
    val entityName: String,
    val ruleLabel: String?,
    val expressionText: String,
    val sourceLine: Int,
    /** Evidence for a failed graph rule, e.g. the cycle path of a failed acyclic check (ADR-0023); `null` otherwise. */
    val detail: String? = null,
    /** `true` when the rule failed because the acyclic intrinsic found a cycle (as opposed to a plain `false`). */
    val cycle: Boolean = false,
)

/**
 * Validates instance attribute values against WHERE-rule specs. No public signature here
 * accepts or returns an ANTLR parse-tree type or a `dev.kstep.express.grammar.*` type — only
 * the [validate] overload taking `(entityName, rules, attributeValues)` is meant for callers
 * outside `dev.kstep.express`, keeping their dependency on this module free of any need for
 * `antlr4-runtime` on their own compile classpath.
 *
 * Each call re-parses `rule.expressionText` — no caching. At V1's scale (six entities, at
 * most one WHERE rule each) this is fine; a future contributor should check actual need
 * before adding a cache rather than adding one reflexively.
 *
 * Two distinct, deliberately non-overlapping failure channels:
 *  - An *unsatisfied* rule (evaluates to `false`) is a non-throwing [WhereRuleViolation]
 *    entry in the returned list — expected, recoverable, structured data.
 *  - A rule using a construct outside the supported subset, or one that is not itself
 *    syntactically valid re-parsed in isolation, makes [ExpressParserFactory.parseExpression]
 *    or [WhereRuleExpressionBuilder.build] *throw* ([dev.kstep.express.ExpressSyntaxException] /
 *    [UnsupportedWhereExpressionException]) — this propagates straight through [validate]
 *    uncaught. Never converted into a violation, never swallowed.
 */
object WhereRuleValidator {
    fun validate(
        entity: ExpressEntity,
        attributeValues: Map<String, WhereRuleValue>,
    ): List<WhereRuleViolation> =
        validate(
            entity.name,
            entity.whereRules.map { WhereRuleSpec(it.label, it.expressionText, it.sourceLine) },
            attributeValues,
        )

    fun validate(
        entityName: String,
        rules: List<WhereRuleSpec>,
        attributeValues: Map<String, WhereRuleValue>,
    ): List<WhereRuleViolation> =
        rules.mapNotNull { rule ->
            val tree = ExpressParserFactory.parseExpression(rule.expressionText)
            val expression = WhereRuleExpressionBuilder.build(tree)
            val satisfied = WhereRuleEvaluator.evaluate(expression, attributeValues)
            if (satisfied) {
                null
            } else {
                WhereRuleViolation(entityName, rule.label, rule.expressionText, rule.sourceLine)
            }
        }

    /**
     * Validates against an instance-graph [context] (ADR-0022): the rules may use
     * `SIZEOF(USEDIN(SELF, '<role>'))`. Returns the violations together with every assumption
     * the evaluation made. Same two failure channels as the other overloads.
     */
    fun validate(
        entityName: String,
        rules: List<WhereRuleSpec>,
        attributeValues: Map<String, WhereRuleValue>,
        context: InstanceEvaluationContext,
    ): WhereRuleValidationReport {
        val prepared =
            rules.map {
                when (val outcome = prepareForInstanceGraph(it)) {
                    is PreparationOutcome.Prepared -> outcome.rule
                    is PreparationOutcome.Unsupported ->
                        throw UnsupportedWhereExpressionException(
                            "WHERE rule ${it.label ?: "(unlabeled)"} is outside the supported subset: " +
                                "${it.expressionText} (${outcome.reason})",
                        )
                }
            }
        return validatePrepared(entityName, prepared, attributeValues, context)
    }

    /**
     * Parses and builds [rule] once (with `allowInstanceGraphFunctions`, and the fixed schema
     * functions of [intrinsics], ADR-0023) so a population-wide validation does not re-parse the same
     * text per instance. A rule outside the supported subset is reported as
     * [PreparationOutcome.Unsupported] with the reason — never dropped silently (ADR-0023); callers
     * decide whether that is an error. A syntactically invalid rule still throws.
     */
    fun prepareForInstanceGraph(
        rule: WhereRuleSpec,
        intrinsics: SchemaFunctionIntrinsicSet = SchemaFunctionIntrinsicSet.NONE,
    ): PreparationOutcome {
        val tree = ExpressParserFactory.parseExpression(rule.expressionText)
        val expression =
            try {
                WhereRuleExpressionBuilder.build(
                    tree,
                    WhereRuleExpressionBuilder.BuildOptions(
                        allowInstanceGraphFunctions = true,
                        schemaFunctionIntrinsics = intrinsics,
                    ),
                )
            } catch (e: UnsupportedWhereExpressionException) {
                return PreparationOutcome.Unsupported(e.message ?: "outside the supported expression subset")
            }
        return PreparationOutcome.Prepared(PreparedWhereRule(rule, expression))
    }

    /** As the graph-context [validate], over rules already built by [prepareForInstanceGraph]. */
    fun validatePrepared(
        entityName: String,
        rules: List<PreparedWhereRule>,
        attributeValues: Map<String, WhereRuleValue>,
        context: InstanceEvaluationContext,
    ): WhereRuleValidationReport {
        val violations = mutableListOf<WhereRuleViolation>()
        val assumptions = mutableListOf<AppliedAssumption>()
        var indeterminate = 0
        for (rule in rules) {
            val result = WhereRuleEvaluator.evaluate(rule.expression, attributeValues, context, rule.spec.label)
            assumptions += result.assumptions
            when {
                result.indeterminate -> indeterminate++
                !result.value ->
                    violations +=
                        WhereRuleViolation(
                            entityName,
                            rule.spec.label,
                            rule.spec.expressionText,
                            rule.spec.sourceLine,
                            detail = result.details.firstOrNull(),
                            cycle = result.cycleFound,
                        )
            }
        }
        return WhereRuleValidationReport(violations, assumptions, indeterminate)
    }
}

/** A WHERE rule parsed and built once; immutable and shareable between threads. */
class PreparedWhereRule internal constructor(
    val spec: WhereRuleSpec,
    internal val expression: WhereRuleExpression,
) {
    val requiresInstanceGraph: Boolean = expression.requiresInstanceGraph()
}

/** Violations plus the assumptions made while evaluating the rules against an instance graph. */
data class WhereRuleValidationReport(
    val violations: List<WhereRuleViolation>,
    val assumptions: List<AppliedAssumption>,
    /** Rules that could not be decided because the population is incomplete (neither violated nor satisfied). */
    val indeterminateCount: Int = 0,
)

/** The outcome of [WhereRuleValidator.prepareForInstanceGraph]. */
sealed interface PreparationOutcome {
    data class Prepared(
        val rule: PreparedWhereRule,
    ) : PreparationOutcome

    /** The rule is outside the supported subset; [reason] names the first construct that is not supported. */
    data class Unsupported(
        val reason: String,
    ) : PreparationOutcome
}
