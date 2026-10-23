package dev.kstep.geometry

import dev.kstep.geometry.select.EdgeNamingException

/** The images of one [GenerationSource] in a sweep result: indices into the result's `OcctShape.edges`. */
class GeneratedImages internal constructor(
    /** True for a vertex source (images are in [side]), false for an edge source ([start] / [end]). */
    val vertexLike: Boolean,
    val side: List<Int>,
    val start: List<Int>,
    val end: List<Int>,
)

/**
 * For every requested [GenerationSource], the edges of the sweep result generated from it. Indices are
 * positions in the result's `OcctShape.edges` -- the same indices `OcctKernel.fillet` takes.
 */
class GenerationMap internal constructor(
    private val bySource: Map<GenerationSource, GeneratedImages>,
    /** Derived sources (ADR-0034): role to result edge indices; a role that is allowed but empty maps to an empty list. */
    private val derived: Map<GenerationSource, Map<GeneratedRole, List<Int>>> = emptyMap(),
    /** Roles of derived sources that could not be named unambiguously; asking for them throws, other roles still work. */
    private val roleErrors: Map<GenerationSource, Map<GeneratedRole, EdgeNamingException>> = emptyMap(),
) {
    /** All sources this map was built for. */
    val sources: Set<GenerationSource> get() = bySource.keys + derived.keys

    /**
     * The edges of [role] generated from [source], possibly empty (for example a vertex on the axis of a full
     * revolve has no usable image, or a blind hole has no RIM_EXIT).
     *
     * @throws IllegalArgumentException if [source] was not requested, or [role] does not fit the source (a
     *   vertex source has SIDE images, an edge source START and END images, a derived source the roles of its
     *   operation).
     */
    fun images(
        source: GenerationSource,
        role: GeneratedRole,
    ): List<Int> {
        derived[source]?.let { byRole ->
            roleErrors[source]?.get(role)?.let { throw it }
            return requireNotNull(byRole[role]) {
                "role $role does not fit source ${source.describe()}: its images have the roles ${byRole.keys}"
            }
        }
        val images =
            requireNotNull(bySource[source]) {
                "source ${source.describe()} was not requested when the shape was built"
            }
        require(role == GeneratedRole.SIDE || role == GeneratedRole.START || role == GeneratedRole.END) {
            "role $role does not fit source ${source.describe()}: a profile source has SIDE, START or END images"
        }
        require(images.vertexLike == (role == GeneratedRole.SIDE)) {
            "role $role does not fit source ${source.describe()}: " +
                if (images.vertexLike) "a vertex source has SIDE images" else "an edge source has START and END images"
        }
        return when (role) {
            GeneratedRole.SIDE -> images.side
            GeneratedRole.START -> images.start
            else -> images.end
        }
    }

    /** A copy keyed by the replacement sources of [mapping]; every source of this map must have a replacement. */
    fun rekeyed(mapping: Map<GenerationSource, GenerationSource>): GenerationMap {
        val out = LinkedHashMap<GenerationSource, GeneratedImages>()
        bySource.forEach { (source, images) ->
            val replacement =
                requireNotNull(mapping[source]) { "no replacement for source ${source.describe()}" }
            require(out.put(replacement, images) == null) {
                "two sources map to the same replacement ${replacement.describe()}"
            }
        }
        val outDerived = LinkedHashMap<GenerationSource, Map<GeneratedRole, List<Int>>>()
        derived.forEach { (source, byRole) ->
            val replacement =
                requireNotNull(mapping[source]) { "no replacement for source ${source.describe()}" }
            require(out[replacement] == null && outDerived.put(replacement, byRole) == null) {
                "two sources map to the same replacement ${replacement.describe()}"
            }
        }
        val outErrors = roleErrors.entries.associate { (source, errors) -> mapping.getValue(source) to errors }
        return GenerationMap(out, outDerived, outErrors)
    }
}

/**
 * A freshly built sweep result together with its [GenerationMap]. [AutoCloseable] like [OcctShape];
 * closing releases [shape].
 */
class NamedShape internal constructor(
    val shape: OcctShape,
    val generation: GenerationMap,
) : AutoCloseable {
    override fun close() = shape.close()

    /**
     * The same shape under other source keys (see [GenerationMap.rekeyed]). Ownership of [shape] moves to the
     * returned object: close that one, not this one.
     */
    fun rekeyed(mapping: Map<GenerationSource, GenerationSource>): NamedShape =
        NamedShape(shape, generation.rekeyed(mapping))
}

/** The coordinate queries sent to the native side, and which source each one belongs to. */
internal class NamingQueries(
    val vertexSources: List<GenerationSource>,
    val vertexXy: DoubleArray,
    val segmentSources: List<GenerationSource>,
    val segQuery: DoubleArray,
)

/** Collects the queries of a list of sources, validating counts and duplicates. */
internal class NamingQueryBuilder {
    private val vertexSources = ArrayList<GenerationSource>()
    private val vertices = ArrayList<Double>()
    private val segmentSources = ArrayList<GenerationSource>()
    private val segments = ArrayList<Double>()
    private val seen = HashSet<GenerationSource>()

    fun vertex(
        source: GenerationSource,
        x: Double,
        y: Double,
    ) {
        register(source)
        vertexSources.add(source)
        vertices.add(x)
        vertices.add(y)
    }

    fun segment(
        source: GenerationSource,
        sx: Double,
        sy: Double,
        ex: Double,
        ey: Double,
        mx: Double = Double.NaN,
        my: Double = Double.NaN,
    ) {
        register(source)
        segmentSources.add(source)
        listOf(sx, sy, ex, ey, mx, my).forEach { segments.add(it) }
    }

    private fun register(source: GenerationSource) {
        require(seen.add(source)) { "duplicate generation source ${source.describe()}" }
        require(seen.size <= OcctKernel.MAX_NAMING_SOURCES) {
            "at most ${OcctKernel.MAX_NAMING_SOURCES} generation sources are allowed"
        }
    }

    fun build(): NamingQueries {
        require(seen.isNotEmpty()) { "at least one generation source is required" }
        return NamingQueries(
            vertexSources.toList(),
            vertices.toDoubleArray(),
            segmentSources.toList(),
            segments.toDoubleArray(),
        )
    }
}

/**
 * Decodes the native generation payload `[vCount, (n, idx...)*, sCount, (nStart, idx..., nEnd, idx...)*]`. Pure
 * and OCCT-free; every length, count and index is validated before use.
 *
 * @throws IllegalStateException on any inconsistency.
 */
internal fun decodeGenerationMap(
    raw: IntArray,
    queries: NamingQueries,
    resultEdgeCount: Int,
): GenerationMap {
    check(raw.isNotEmpty()) { "native generation map must carry a vertex count" }
    check(raw.size <= OcctKernel.MAX_HISTORY_INTS) {
        "native generation map of ${raw.size} ints exceeds ${OcctKernel.MAX_HISTORY_INTS}"
    }
    var pos = 0

    fun next(what: String): Int {
        check(pos < raw.size) { "malformed generation map: truncated while reading $what" }
        return raw[pos++]
    }

    fun readList(what: String): List<Int> {
        val count = next("$what count")
        check(count in 0..resultEdgeCount) { "malformed generation map: $what count $count out of range" }
        val list = ArrayList<Int>(count)
        repeat(count) {
            val index = next(what)
            check(index in 0 until resultEdgeCount) {
                "malformed generation map: $what index $index outside [0, $resultEdgeCount)"
            }
            list.add(index)
        }
        return list
    }

    val out = LinkedHashMap<GenerationSource, GeneratedImages>()
    val vCount = next("vertex count")
    check(vCount == queries.vertexSources.size) {
        "generation map has $vCount vertices, expected ${queries.vertexSources.size}"
    }
    queries.vertexSources.forEach { source ->
        out[source] = GeneratedImages(true, readList("side image"), emptyList(), emptyList())
    }
    val sCount = next("segment count")
    check(sCount == queries.segmentSources.size) {
        "generation map has $sCount segments, expected ${queries.segmentSources.size}"
    }
    queries.segmentSources.forEach { source ->
        val start = readList("start image")
        val end = readList("end image")
        out[source] = GeneratedImages(false, emptyList(), start, end)
    }
    check(pos == raw.size) { "malformed generation map: ${raw.size - pos} trailing ints" }
    return GenerationMap(out)
}

/**
 * A freshly built derived-feature result (fillet, chamfer, boolean, hole) with the [EdgeHistory] AND the
 * [GenerationMap] of the sources asked for (ADR-0034), both read from the same OCCT maker. [AutoCloseable] like
 * [OcctShape]; closing releases [shape].
 */
class DerivedNamedShape internal constructor(
    val shape: OcctShape,
    val history: EdgeHistory,
    val generation: GenerationMap,
) : AutoCloseable {
    override fun close() = shape.close()
}

/** What `Generated()` produced for one query: the result edges and faces it points to. */
internal class DerivedImages(
    val edges: List<Int>,
    val faces: List<Int>,
)

/**
 * Decodes the native derived generation payload `[queryCount, (nEdges, idx..., nFaces, idx...)*]`. Pure and
 * OCCT-free; every length, count and index is validated before use.
 *
 * @throws IllegalStateException on any inconsistency.
 */
internal fun decodeDerivedGeneration(
    raw: IntArray,
    queryCount: Int,
    resultEdgeCount: Int,
    resultFaceCount: Int,
): List<DerivedImages> {
    check(raw.isNotEmpty()) { "native derived generation map must carry a query count" }
    check(raw.size <= OcctKernel.MAX_HISTORY_INTS) {
        "native derived generation map of ${raw.size} ints exceeds ${OcctKernel.MAX_HISTORY_INTS}"
    }
    var pos = 0

    fun next(what: String): Int {
        check(pos < raw.size) { "malformed derived generation map: truncated while reading $what" }
        return raw[pos++]
    }

    fun readList(
        what: String,
        limit: Int,
    ): List<Int> {
        val count = next("$what count")
        check(count in 0..limit) { "malformed derived generation map: $what count $count out of range" }
        val list = ArrayList<Int>(count)
        repeat(count) {
            val index = next(what)
            check(index in 0 until limit) {
                "malformed derived generation map: $what index $index outside [0, $limit)"
            }
            list.add(index)
        }
        return list
    }
    val count = next("query count")
    check(count == queryCount) { "derived generation map has $count queries, expected $queryCount" }
    val out = ArrayList<DerivedImages>(queryCount)
    repeat(queryCount) {
        val edges = readList("edge image", resultEdgeCount)
        val faces = readList("face image", resultFaceCount)
        out.add(DerivedImages(edges, faces))
    }
    check(pos == raw.size) { "malformed derived generation map: ${raw.size - pos} trailing ints" }
    return out
}
