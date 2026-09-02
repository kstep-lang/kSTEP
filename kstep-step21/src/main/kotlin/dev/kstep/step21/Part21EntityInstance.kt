package dev.kstep.step21

/**
 * One `#id=...;` DATA-section statement, before type-aware resolution — either a
 * [Part21SimpleInstance] (`#N=ENTITY_NAME(args);`) or a [Part21ComplexInstance]
 * (`#N=( PART1(args) PART2(args) ... );`, permitted only under [Part21ReadMode.TOLERANT]).
 *
 * Replaces the pre-Welle-4 `internal data class Part21RawInstance`, which could only represent
 * a single-entity-name instance with V1-shaped ([Part21Value.Str]/[Part21Value.Ref]/
 * [Part21Value.ListValue]/[Part21Value.Unset]) arguments — real, foreign-produced AP242
 * geometry needs both complex instances and the fuller [Part21Value] grammar (see ADR-0009).
 */
sealed interface Part21EntityInstance {
    val id: Int
    val sourceLine: Int

    /** Every `#N` this instance's arguments mention, recursively through lists/typed parameters/complex parts. */
    fun referencedIds(): List<Int>

    /** Rewrites every `#N` this instance's arguments mention via [transform]. Does NOT rewrite [id] itself. */
    fun mapRefs(transform: (Int) -> Int): Part21EntityInstance

    /** Returns a copy of this instance with [id] replaced by [newId] (arguments/refs untouched). */
    fun withId(newId: Int): Part21EntityInstance
}

/** `#N=ENTITY_NAME(args);` — [entityName] is upper-cased; may or may not be a known [Part21EntityKind]. */
data class Part21SimpleInstance(
    override val id: Int,
    val entityName: String,
    val args: List<Part21Value>,
    override val sourceLine: Int = 0,
) : Part21EntityInstance {
    override fun referencedIds(): List<Int> = args.flatMap { it.referencedIds() }

    override fun mapRefs(transform: (Int) -> Int): Part21EntityInstance =
        copy(args = args.map { it.mapRefs(transform) })

    override fun withId(newId: Int): Part21EntityInstance = copy(id = newId)
}

/** One `NAME(args)` part of a [Part21ComplexInstance]. */
data class Part21InstancePart(
    val entityName: String,
    val args: List<Part21Value>,
) {
    fun referencedIds(): List<Int> = args.flatMap { it.referencedIds() }

    fun mapRefs(transform: (Int) -> Int): Part21InstancePart = copy(args = args.map { it.mapRefs(transform) })
}

/**
 * `#N=( A(args) B(args) ... );` — one instance of several entity types simultaneously. OCCT
 * emits 28 of these for a single box (units + representation contexts) — see ADR-0009.
 * [Part21ReadMode.STRICT] never produces this type; only [Part21ReadMode.TOLERANT] does.
 */
data class Part21ComplexInstance(
    override val id: Int,
    val parts: List<Part21InstancePart>,
    override val sourceLine: Int = 0,
) : Part21EntityInstance {
    override fun referencedIds(): List<Int> = parts.flatMap { it.referencedIds() }

    override fun mapRefs(transform: (Int) -> Int): Part21EntityInstance =
        copy(parts = parts.map { it.mapRefs(transform) })

    override fun withId(newId: Int): Part21EntityInstance = copy(id = newId)
}
