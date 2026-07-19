package dev.kstep.step21

import dev.kstep.core.DslViolation

/**
 * The outcome of [Part21Reader.read]: the parsed [header], every successfully re-validated
 * instance keyed by its Part-21 `#N`, and — for instances whose `kstep-core` builder rejected
 * them — the [violations] that rejected them or the [skipped] ids of the prerequisite(s) that
 * were themselves rejected/skipped. Mirrors `dev.kstep.core.ValidationResult`'s "a validation
 * failure is structured data, not a thrown exception" philosophy, extended to reading: only
 * genuine structural malformation of the Part-21 text throws (see the exception taxonomy in
 * [Part21Reader]'s KDoc); a business-rule (`WHERE`-rule) failure surfaces here instead.
 */
data class Part21ReadResult(
    val header: Part21Header,
    val instances: Map<Int, Any>,
    val violations: Map<Int, List<DslViolation>>,
    val skipped: Map<Int, List<Int>>,
) {
    val isFullySuccessful: Boolean get() = violations.isEmpty() && skipped.isEmpty()
}
