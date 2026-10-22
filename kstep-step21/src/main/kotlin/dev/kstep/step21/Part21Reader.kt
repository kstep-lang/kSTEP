package dev.kstep.step21

import dev.kstep.core.ap242.Ap242ModelValidator

/**
 * Parses ISO 10303-21 physical exchange file text back into `kstep-core` AP242 V1 instances.
 *
 * [read] throws for structurally malformed input:
 * - [Part21SyntaxException] — missing semicolon, malformed `#N=`, mismatched parens/quotes,
 *   missing/misordered section keyword, unknown entity name (STRICT mode only — see
 *   [Part21ReadMode]), wrong argument arity/kind, a reference whose target instance is the
 *   wrong entity type, or (TOLERANT mode only) a reference whose target is a complex instance
 *   that kSTEP cannot typed-construct even though one of its parts matches the expected type.
 * - [Part21EncodingException] — a string literal contains a non-ASCII or control character.
 * - [Part21DanglingReferenceException] — a `#N` is used but never defined in `DATA`.
 * - [Part21CycleException] — the raw `#N` reference graph contains a cycle.
 * - [Part21LimitExceededException] — a DoS/resource guard trips (source length, instance
 *   count, value-nesting depth, reference-chain depth, complex-instance part count).
 *
 * It never throws for a business-rule (`WHERE`-rule or missing-mandatory-reference) validation
 * failure while reconstructing a known instance through its `kstep-core` builder — that surfaces
 * in the returned [Part21ReadResult.violations]/[Part21ReadResult.skipped] instead, mirroring
 * `dev.kstep.core.ValidationResult`'s "a validation failure is structured data, not a thrown
 * exception" philosophy.
 */
object Part21Reader {
    /**
     * Full read + typed reconstruction. Defaults to [Part21ReadMode.STRICT] — every pre-existing caller's behavior is unchanged.
     *
     * With [validateModel] set (opt-in, default off), the successfully reconstructed instances are
     * additionally validated on population level (`USEDIN`-based WHERE rules and `INVERSE` bounds,
     * ADR-0022) and the outcome is returned in [Part21ReadResult.modelValidation]. Opaque
     * instances (TOLERANT mode) and instances rejected/skipped on read do not take part, so they are
     * invisible to the reverse-reference index. To avoid false positives, findings on any instance
     * that such a non-reconstructed instance references are withheld and counted in
     * `ModelValidationResult.suppressedFindingCount` (a cycle found by the acyclic check and a UNIQUE violation are
     * facts about the visible instances and are not withheld; a walk through such an instance without finding a
     * cycle is counted as indeterminate). It never changes [Part21ReadResult.isFullySuccessful].
     * The index limits (100 000 instances) exceed the tokenizer's own instance cap, so they cannot
     * trip on a document this reader accepts.
     */
    fun read(
        source: String,
        mode: Part21ReadMode = Part21ReadMode.STRICT,
        validateModel: Boolean = false,
    ): Part21ReadResult {
        val document = Part21Tokenizer.parseDocument(source, mode)
        val resolved = Part21GraphResolver.resolve(document)
        val unreconstructed = resolved.opaque.keys + resolved.violations.keys + resolved.skipped.keys
        // Instances referenced by something that could not be reconstructed have invisible referrers; the
        // validator withholds USEDIN/INVERSE verdicts for them and treats them as incomplete in the acyclic
        // check and in population-dependent derived values (ADR-0022, ADR-0023).
        val shadowedIds =
            document.instances
                .filter { it.id in unreconstructed }
                .flatMapTo(mutableSetOf()) { it.referencedIds() }
        val shadowed =
            resolved.instances
                .filterKeys { it in shadowedIds }
                .values
                .toList()
        val result = resolved.copy(incompleteInstances = shadowed)
        if (!validateModel) return result
        return result.copy(
            modelValidation = Ap242ModelValidator.validate(result.instances.values, incomplete = shadowed),
        )
    }

    /**
     * Purely syntactic view — parses [source] into a [Part21RawDocument] without any
     * `kstep-core` construction, dangling-reference/cycle/reference-target-type checking, or
     * WHERE-rule evaluation. Defaults to [Part21ReadMode.TOLERANT], since this is the entry
     * point `kstep-shape`'s merge path (see ADR-0009) uses on foreign, OCCT-produced AP242
     * output that STRICT mode cannot parse at all.
     */
    fun readRawDocument(
        source: String,
        mode: Part21ReadMode = Part21ReadMode.TOLERANT,
    ): Part21RawDocument = Part21Tokenizer.parseDocument(source, mode)
}
