package dev.kstep.desktop.model

/** Where a part sits: [origin] (3, mm) and [axes] (9, row-major rotation) as written by the render worker. */
class PartPlacement(
    val origin: DoubleArray,
    val axes: DoubleArray,
)

/** One drawn part: a name and a contiguous triangle range of the GLB mesh, plus values derived from that mesh. */
class PartInfo(
    val name: String,
    val firstTriangle: Int,
    val triangleCount: Int,
    val placement: PartPlacement,
    val colorHex: String,
    val bboxMin: DoubleArray,
    val bboxMax: DoubleArray,
    val centroid: DoubleArray,
)

/** The validated part list of one model; [triangleToPart] maps a GLB triangle index to a part index. */
class PartTable(
    val parts: List<PartInfo>,
    private val triangleToPart: IntArray,
) {
    private val byName: Map<String, Int> =
        HashMap<String, Int>().also { map -> parts.forEachIndexed { i, p -> map.putIfAbsent(p.name, i) } }

    val triangleCount: Int get() = triangleToPart.size

    /** Part index of triangle [t], `-1` when out of range. */
    fun partOfTriangle(t: Int): Int = if (t in triangleToPart.indices) triangleToPart[t] else -1

    /** First part with this [name], else `-1`. */
    fun indexOfName(name: String): Int = byName[name] ?: -1

    /** The [occurrence]-th (0-based) part with this [name], else `-1`. */
    fun indexOfName(
        name: String,
        occurrence: Int,
    ): Int {
        if (occurrence < 0) return -1
        var seen = 0
        for (i in parts.indices) {
            if (parts[i].name == name && seen++ == occurrence) return i
        }
        return -1
    }

    /** How many parts before [part] carry the same name (0 for the first of that name). */
    fun occurrenceOf(part: Int): Int {
        if (part !in parts.indices) return 0
        val name = parts[part].name
        var seen = 0
        for (i in 0 until part) if (parts[i].name == name) seen++
        return seen
    }

    /** The raw triangle-to-part map (read-only use: highlight lookup in the viewer). */
    fun triangleToPartArray(): IntArray = triangleToPart
}
