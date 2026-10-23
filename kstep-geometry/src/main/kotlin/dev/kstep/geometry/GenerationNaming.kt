package dev.kstep.geometry

import dev.kstep.geometry.select.EdgeNamingException

/** Translates profile-level sources into coordinate queries; pure, validated before any native call. */
internal fun resolveProfileSources(
    profile: List<ProfilePoint>,
    sources: List<GenerationSource>,
): NamingQueries {
    val b = NamingQueryBuilder()
    val n = profile.size
    sources.forEach { source ->
        when (source) {
            is ProfileVertex -> {
                require(source.index in 0 until n) { "${source.describe()} is out of range 0..${n - 1}" }
                b.vertex(source, profile[source.index].x, profile[source.index].y)
            }
            is ProfileEdge -> {
                require(source.index in 0 until n) { "${source.describe()} is out of range 0..${n - 1}" }
                val a = profile[source.index]
                val e = profile[(source.index + 1) % n]
                b.segment(source, a.x, a.y, e.x, e.y, (a.x + e.x) / 2.0, (a.y + e.y) / 2.0)
            }
            is PointAt -> addPoint(b, source)
            is SegmentAt -> addSegmentAt(b, source)
            is RegionVertex, is RegionEdge ->
                throw IllegalArgumentException("${source.describe()} names a region; use a region sweep")
            is ExternalGenerationSource -> throw externalNotSupported(source)
            is DerivedGenerationSource -> throw derivedNotSupported(source)
        }
    }
    return b.build()
}

/** Translates region-level sources into coordinate queries (relative to the region AS GIVEN). */
internal fun resolveRegionSources(
    region: ProfileRegion,
    sources: List<GenerationSource>,
): NamingQueries {
    val b = NamingQueryBuilder()
    val loops = listOf(region.outer) + region.holes

    fun segmentOf(
        source: GenerationSource,
        loop: Int,
        segment: Int,
    ): ProfileSegment {
        require(loop in loops.indices) { "${source.describe()}: loop is out of range 0..${loops.size - 1}" }
        val segments = loops[loop].segments
        require(segment in segments.indices) {
            "${source.describe()}: segment is out of range 0..${segments.size - 1}"
        }
        return segments[segment]
    }
    sources.forEach { source ->
        when (source) {
            is RegionVertex -> {
                val seg = segmentOf(source, source.loop, source.segment)
                require(seg !is ProfileSegment.Circle) { "${source.describe()}: a circle has no corner vertex" }
                val p = seg.startPoint()
                b.vertex(source, p.x, p.y)
            }
            is RegionEdge -> {
                val seg = segmentOf(source, source.loop, source.segment)
                val s = seg.startPoint()
                val e = seg.endPoint()
                when (seg) {
                    is ProfileSegment.Line ->
                        b.segment(
                            source,
                            s.x,
                            s.y,
                            e.x,
                            e.y,
                            (s.x + e.x) / 2.0,
                            (s.y + e.y) / 2.0,
                        )
                    is ProfileSegment.Arc -> b.segment(source, s.x, s.y, e.x, e.y, seg.mid.x, seg.mid.y)
                    is ProfileSegment.Circle ->
                        b.segment(source, s.x, s.y, e.x, e.y, seg.center.x - seg.radius, seg.center.y)
                }
            }
            is PointAt -> addPoint(b, source)
            is SegmentAt -> addSegmentAt(b, source)
            is ProfileVertex, is ProfileEdge ->
                throw IllegalArgumentException("${source.describe()} names a polygon profile; use a profile sweep")
            is ExternalGenerationSource -> throw externalNotSupported(source)
            is DerivedGenerationSource -> throw derivedNotSupported(source)
        }
    }
    return b.build()
}

private fun derivedNotSupported(source: GenerationSource) =
    IllegalArgumentException(
        "${source.describe()} names a derived feature; use it on a fillet, chamfer, boolean or hole generator",
    )

private fun addPoint(
    b: NamingQueryBuilder,
    source: PointAt,
) {
    require(source.x.isFinite() && source.y.isFinite()) { "${source.describe()} must be finite" }
    b.vertex(source, source.x, source.y)
}

private fun addSegmentAt(
    b: NamingQueryBuilder,
    source: SegmentAt,
) {
    require(listOf(source.startX, source.startY, source.endX, source.endY).all { it.isFinite() }) {
        "${source.describe()} must be finite"
    }
    b.segment(source, source.startX, source.startY, source.endX, source.endY)
}

private fun externalNotSupported(source: ExternalGenerationSource) =
    IllegalArgumentException(
        "external generation source '${source.description}' cannot be used with the kernel directly: " +
            "the generating feature must translate it to PointAt / SegmentAt",
    )

/**
 * Looks up the images of [sources] with [role] in [generation] (the map of the generating shape
 * [generatorShape]) and returns them sorted and distinct. Strict: every source needs at least one image,
 * and [expected], if set, must match the count.
 */
internal fun resolveGeneratedImages(
    generation: GenerationMap,
    sources: List<GenerationSource>,
    role: GeneratedRole,
    expected: Int?,
    generatorShape: OcctShape,
    featureIndex: Int?,
): List<Int> {
    require(sources.isNotEmpty()) { "at least one generation source is required" }
    require(sources.size <= OcctKernel.MAX_NAMING_SOURCES) {
        "at most ${OcctKernel.MAX_NAMING_SOURCES} generation sources are allowed, got ${sources.size}"
    }
    val perSource = LinkedHashMap<String, List<Int>>()
    val unresolved = ArrayList<GenerationSource>()
    val result = java.util.TreeSet<Int>()
    sources.forEach { source ->
        val images = generation.images(source, role)
        perSource[source.describe()] = images
        if (images.isEmpty()) unresolved.add(source) else result.addAll(images)
    }
    val where = if (featureIndex == null) "" else " (consumer feature[$featureIndex])"
    if (unresolved.isNotEmpty()) {
        throw EdgeNamingException(
            "persistent edge naming$where failed: ${unresolved.size} of ${sources.size} source(s) have no $role " +
                "image in the generating shape: ${unresolved.joinToString { it.describe() }}\n" +
                EdgeNamingException.detail(perSource, generatorShape.edges),
            featureIndex,
            unresolved,
            result.toList(),
            perSource,
            generatorShape.edges,
        )
    }
    if (expected != null && result.size != expected) {
        throw EdgeNamingException(
            "persistent edge naming$where is ambiguous: expected $expected edge(s) but ${result.size} edge(s) " +
                "were generated: ${result.toList()}\n" + EdgeNamingException.detail(perSource, generatorShape.edges),
            featureIndex,
            sources,
            result.toList(),
            perSource,
            generatorShape.edges,
        )
    }
    return result.toList()
}
