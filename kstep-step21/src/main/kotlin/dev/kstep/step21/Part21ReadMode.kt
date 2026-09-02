package dev.kstep.step21

/** Controls how tolerant [Part21Reader]/[Part21Tokenizer] are of entity names and instance shapes they don't recognize. */
enum class Part21ReadMode {
    /**
     * Unchanged behavior from before kSTEP Geometrie Welle 4 and the **default** of
     * [Part21Reader.read]: every DATA entity must be a known [Part21EntityKind], every value
     * must be [Part21Value.Str]/[Part21Value.Ref]/[Part21Value.ListValue]/[Part21Value.Unset],
     * and complex instances (`#N=( A(...) B(...) );`) are not permitted — anything else is
     * [Part21SyntaxException]. Every pre-existing caller and every pre-existing test keeps
     * exactly its prior behavior under this mode.
     */
    STRICT,

    /**
     * Entities outside [Part21EntityKind] and complex instances are kept **verbatim as opaque
     * instances** ([Part21ReadResult.opaque]) instead of throwing, and the full ISO 10303-21
     * value grammar (integer/real, enumeration, `*`, typed parameter) is accepted anywhere.
     *
     * *Known* entity names stay just as strict as [STRICT]: a `PRODUCT_DEFINITION` with the
     * wrong argument count or shape is still [Part21SyntaxException], never silently downgraded
     * to an opaque instance — kSTEP controls the writer of the files this mode is meant to read
     * (see ADR-0009). Tolerating malformed *known* entities from arbitrary third-party files is
     * explicitly out of scope (see ADR-0009's Folge-Wellen table, Welle 4e).
     */
    TOLERANT,
}
