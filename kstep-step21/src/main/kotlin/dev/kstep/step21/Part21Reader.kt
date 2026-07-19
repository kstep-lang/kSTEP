package dev.kstep.step21

/**
 * Parses ISO 10303-21 physical exchange file text back into `kstep-core` AP242 V1 instances.
 *
 * [read] throws for structurally malformed input:
 * - [Part21SyntaxException] — missing semicolon, malformed `#N=`, mismatched parens/quotes,
 *   missing/misordered section keyword, unknown entity name, wrong argument arity/kind, or a
 *   reference whose target instance is the wrong entity type.
 * - [Part21EncodingException] — a string literal contains a non-ASCII or control character.
 * - [Part21DanglingReferenceException] — a `#N` is used but never defined in `DATA`.
 * - [Part21CycleException] — the raw `#N` reference graph contains a cycle.
 * - [Part21LimitExceededException] — a DoS/resource guard trips (source length, instance
 *   count, value-nesting depth, reference-chain depth).
 *
 * It never throws for a business-rule (`WHERE`-rule or missing-mandatory-reference) validation
 * failure while reconstructing an instance through its `kstep-core` builder — that surfaces in
 * the returned [Part21ReadResult.violations]/[Part21ReadResult.skipped] instead, mirroring
 * `dev.kstep.core.ValidationResult`'s "a validation failure is structured data, not a thrown
 * exception" philosophy.
 */
object Part21Reader {
    fun read(source: String): Part21ReadResult {
        val document = Part21Tokenizer.parseDocument(source)
        return Part21GraphResolver.resolve(document)
    }
}
