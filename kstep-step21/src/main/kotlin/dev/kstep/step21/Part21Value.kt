package dev.kstep.step21

/**
 * A single parsed Part-21 argument value.
 *
 * [Str]/[Ref]/[ListValue]/[Unset] are the V1 subset the typed [Part21Writer] emits and
 * [Part21EntityKind]-checked entities accept. [Num]/[Enumeration]/[Derived]/[Typed] are the
 * additional value forms real, foreign-produced AP242 geometry uses (see ADR-0009) — the
 * tokenizer always parses the full ISO 10303-21 value grammar regardless of [Part21ReadMode];
 * only [Part21ReadMode.STRICT]'s known-entity-shape checks reject them at a *known* entity's
 * argument position (a [Part21Value.Num] is never a legal `STRING`/`REFERENCE` shape there,
 * checked the same way in both modes — see [Part21Tokenizer.checkArgShape]).
 */
sealed interface Part21Value {
    data class Str(
        val text: String,
    ) : Part21Value

    data class Ref(
        val id: Int,
    ) : Part21Value

    data class ListValue(
        val items: List<Part21Value>,
    ) : Part21Value

    /** The Part-21 `$` token — an explicitly-unset value for an OPTIONAL attribute position. */
    data object Unset : Part21Value

    /** The Part-21 `*` token — a DERIVE-redeclared attribute, e.g. `ORIENTED_EDGE('',*,*,#21,.F.)`. */
    data object Derived : Part21Value

    /**
     * An integer or real numeric literal, held **verbatim as its source lexeme** and written back
     * verbatim, never parsed into a [Double]/[Int]. `1.E-07` -> Double -> `toString()` would yield
     * `1.0E-7`; `2013` (an INTEGER) -> Double would yield `2013.0`, a different Part-21 literal
     * shape; and `-4.440892098501E-16` is not guaranteed to round-trip bit-identically through
     * `Double`. See ADR-0009's "stolperfallen" — this module re-emits foreign-produced geometry it
     * does not own the precision of, so no reformatting is acceptable.
     */
    data class Num(
        val lexeme: String,
    ) : Part21Value

    /** A Part-21 enumeration literal without its surrounding dots: `.T.` -> `Enumeration("T")`. */
    data class Enumeration(
        val name: String,
    ) : Part21Value

    /** A typed parameter, e.g. `LENGTH_MEASURE(1.E-07)` or `NAMED_UNIT(*)`. */
    data class Typed(
        val keyword: String,
        val args: List<Part21Value>,
    ) : Part21Value
}

/** Every `#N` this value mentions, recursively through [Part21Value.ListValue] and [Part21Value.Typed]. */
fun Part21Value.referencedIds(): List<Int> =
    when (this) {
        is Part21Value.Ref -> listOf(id)
        is Part21Value.ListValue -> items.flatMap { it.referencedIds() }
        is Part21Value.Typed -> args.flatMap { it.referencedIds() }
        is Part21Value.Str, is Part21Value.Num, is Part21Value.Enumeration,
        Part21Value.Unset, Part21Value.Derived,
        -> emptyList()
    }

/** Rewrites every `#N` this value mentions via [transform], recursively through [Part21Value.ListValue]/[Part21Value.Typed]. */
fun Part21Value.mapRefs(transform: (Int) -> Int): Part21Value =
    when (this) {
        is Part21Value.Ref -> Part21Value.Ref(transform(id))
        is Part21Value.ListValue -> Part21Value.ListValue(items.map { it.mapRefs(transform) })
        is Part21Value.Typed -> Part21Value.Typed(keyword, args.map { it.mapRefs(transform) })
        is Part21Value.Str, is Part21Value.Num, is Part21Value.Enumeration,
        Part21Value.Unset, Part21Value.Derived,
        -> this
    }
