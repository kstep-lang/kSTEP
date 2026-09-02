package dev.kstep.step21

/**
 * A neutral, syntax-level Part-21 document — a [header] plus [instances] in `DATA` order — with
 * no `kstep-core` typing involved. This is the "document algebra" ADR-0009 introduces:
 * [render], [renumbered], and [concat] are the operations `kstep-shape`'s AP242 merge (see
 * `dev.kstep.shape.Ap242ShapeExporter`) composes to combine kSTEP's own validated product
 * structure with a foreign, OCCT-produced geometry subgraph into one file.
 */
data class Part21Document(
    val header: Part21Header,
    val instances: List<Part21EntityInstance>,
) {
    /** The one text-serialization path — see [Part21Renderer]. */
    fun render(): String = Part21Renderer.render(header, instances)

    /** Highest instance id in [instances], or 0 for an empty document. */
    val maxId: Int get() = instances.maxOfOrNull { it.id } ?: 0

    /**
     * A copy of this document with every instance id — and every `#N` reference to one — shifted
     * by [offset].
     *
     * @throws Part21WriteException if shifting any id by [offset] would overflow a 32-bit Int.
     *   Silently wrapping (e.g. an id near [Int.MAX_VALUE] shifted positive, landing negative)
     *   would produce a document [Part21Renderer] itself refuses to write (ids must be `> 0`,
     *   see [Part21Renderer]'s per-value validation) — or, worse, one that wraps back into the
     *   positive range and collides with an unrelated instance. Failing here, at the one place
     *   that knows both the original id and the offset, keeps the cause attributable.
     */
    fun renumbered(offset: Int): Part21Document =
        copy(
            instances =
                instances.map { instance ->
                    instance.withId(shiftId(instance.id, offset)).mapRefs { id -> shiftId(id, offset) }
                },
        )

    companion object {
        /**
         * Appends each group in [others] to [base]'s instances, in order, into one document
         * that keeps [base]'s header. Never silently overwrites: a colliding instance id
         * (already present in [base] or an earlier group) is [Part21WriteException] — callers
         * are expected to have already [renumbered] any group whose ids might collide.
         */
        fun concat(
            base: Part21Document,
            vararg others: List<Part21EntityInstance>,
        ): Part21Document {
            val merged = base.instances.toMutableList()
            val seenIds = base.instances.mapTo(mutableSetOf()) { it.id }
            for (group in others) {
                for (instance in group) {
                    if (!seenIds.add(instance.id)) {
                        throw Part21WriteException(
                            "instance id #${instance.id} is defined more than once while concatenating Part-21 documents",
                        )
                    }
                    merged += instance
                }
            }
            return base.copy(instances = merged)
        }
    }
}

/** [Math.addExact]'s overflow check, surfaced as the domain exception [renumbered] documents. */
private fun shiftId(
    id: Int,
    offset: Int,
): Int =
    try {
        Math.addExact(id, offset)
    } catch (e: ArithmeticException) {
        throw Part21WriteException(
            "renumbering instance id #$id by offset $offset overflows a 32-bit Part-21 instance id",
        )
    }
