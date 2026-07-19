package dev.kstep.express.semantic

/**
 * A `UNIQUE` clause `uniqueRule` -- structurally near-identical to `domainRule` (same optional-
 * label precedent as [ExpressWhereRule]), except its body is a comma-separated list of
 * `referencedAttribute`s rather than a single boolean `expression`. Each referenced attribute
 * is captured verbatim rather than assumed to be a bare identifier: `referencedAttribute` can
 * also be a `SELF\entity.attr`-qualified form (real AP242's
 * `next_assembly_usage_occurrence` uses this), the same qualification
 * [ExpressAttribute.Redeclared] already captures verbatim.
 */
data class ExpressUniqueRule(
    val label: String?,
    val referencedAttributes: List<String>,
    val sourceLine: Int,
)
