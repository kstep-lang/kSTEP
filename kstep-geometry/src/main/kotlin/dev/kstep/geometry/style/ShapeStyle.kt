package dev.kstep.geometry.style

/** Why a colour/layer annotation was rejected; mapped to the `KSTEP-S-012`..`KSTEP-S-015` script error codes. */
enum class StyleErrorKind { INVALID_COLOUR, INVALID_LAYER, CONFLICT, TARGET }

/** A colour or layer annotation was invalid (ADR-0039). */
class StyleException(
    val kind: StyleErrorKind,
    message: String,
) : IllegalArgumentException(message)

/**
 * An sRGB colour with components in 0..1 and an opacity [a] (alpha, 1.0 = opaque, the default). Transparency is
 * supported on part and face colours since ADR-0042; edge colours must be opaque (`CURVE_STYLE` has no transparency).
 */
data class StepColour(
    val r: Double,
    val g: Double,
    val b: Double,
    val a: Double = 1.0,
) {
    init {
        for ((name, v) in listOf("r" to r, "g" to g, "b" to b)) {
            if (!v.isFinite() || v < 0.0 || v > 1.0) {
                throw StyleException(
                    StyleErrorKind.INVALID_COLOUR,
                    "colour component $name must be a finite number in 0..1, was $v",
                )
            }
        }
        if (!a.isFinite() || a < 0.0 || a > 1.0) {
            throw StyleException(
                StyleErrorKind.INVALID_COLOUR,
                "colour component a (alpha) must be a finite number in 0..1, was $a",
            )
        }
    }

    /** True if the colour is fully opaque (alpha 1.0), the only kind that takes the unchanged pre-ADR-0042 path. */
    val isOpaque: Boolean get() = a == 1.0

    /** The three colour components as an array, in r, g, b order (alpha is not included). */
    fun toArray(): DoubleArray = doubleArrayOf(r, g, b)

    /** The four components as an array, in r, g, b, a order. */
    fun toRgbaArray(): DoubleArray = doubleArrayOf(r, g, b, a)

    companion object {
        fun rgb(
            r: Double,
            g: Double,
            b: Double,
        ): StepColour = StepColour(r, g, b)

        fun rgba(
            r: Double,
            g: Double,
            b: Double,
            a: Double,
        ): StepColour = StepColour(r, g, b, a)

        /** Parses `#RRGGBB` or `#RRGGBBAA` (case-insensitive; the last pair is the opacity, `FF` = opaque). */
        fun hex(text: String): StepColour {
            if (!HEX.matches(text)) {
                throw StyleException(
                    StyleErrorKind.INVALID_COLOUR,
                    "colour must be written #RRGGBB or #RRGGBBAA, was '$text'",
                )
            }

            fun part(from: Int) = text.substring(from, from + 2).toInt(16) / 255.0
            val alpha = if (text.length == 9) part(7) else 1.0
            return StepColour(part(1), part(3), part(5), alpha)
        }

        private val HEX = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    }
}

/** A small named palette. Anything else is available through [StepColour.rgb] or [StepColour.hex]. */
object StepColours {
    val RED = StepColour(1.0, 0.0, 0.0)
    val GREEN = StepColour(0.0, 0.6, 0.0)
    val BLUE = StepColour(0.0, 0.0, 1.0)
    val ORANGE = StepColour(1.0, 0.5, 0.0)
    val YELLOW = StepColour(1.0, 0.9, 0.0)
    val GREY = StepColour(0.5, 0.5, 0.5)
    val BLACK = StepColour(0.0, 0.0, 0.0)
    val WHITE = StepColour(1.0, 1.0, 1.0)
}

/**
 * Validation of layer names (`PRESENTATION_LAYER_ASSIGNMENT.name`). Only printable ASCII is accepted, without the
 * reverse solidus: the Part 21 reader and writer of kSTEP (V1) implement no `\X2\` escapes, and OCCT writes
 * non-ASCII text unescaped, so a wider set would fail late and cryptically while merging the file (ADR-0039 D7).
 */
object LayerNames {
    const val MAX_LENGTH: Int = 128

    /** Returns [name] if it is 1..[MAX_LENGTH] printable ASCII characters (no backslash) and not blank. */
    fun require(name: String): String {
        if (name.isBlank()) throw StyleException(StyleErrorKind.INVALID_LAYER, "layer name must not be blank")
        if (name.length > MAX_LENGTH) {
            throw StyleException(
                StyleErrorKind.INVALID_LAYER,
                "layer name has ${name.length} characters, the limit is $MAX_LENGTH",
            )
        }
        for (c in name) {
            if (c.code !in 0x20..0x7E || c == '\\') {
                throw StyleException(
                    StyleErrorKind.INVALID_LAYER,
                    "layer name contains the unsupported character U+${"%04X".format(c.code)}; use printable ASCII " +
                        "without a backslash",
                )
            }
        }
        return name
    }
}

/** Colour and/or layer of one face; [faceIndex] is `OcctShape.faces[i].index`. */
data class FaceStyle(
    val faceIndex: Int,
    val colour: StepColour? = null,
    val layer: String? = null,
) {
    init {
        if (faceIndex < 0) throw StyleException(StyleErrorKind.TARGET, "face index must not be negative: $faceIndex")
        layer?.let { LayerNames.require(it) }
    }
}

/** Colour of one edge; [edgeIndex] is the index in the shape's edge order (`OcctShape.edges[i].index`). Opaque only. */
data class EdgeStyle(
    val edgeIndex: Int,
    val colour: StepColour,
) {
    init {
        if (edgeIndex < 0) throw StyleException(StyleErrorKind.TARGET, "edge index must not be negative: $edgeIndex")
        if (!colour.isOpaque) {
            throw StyleException(
                StyleErrorKind.INVALID_COLOUR,
                "edge colours cannot be transparent (CURVE_STYLE has no transparency), alpha was ${colour.a}",
            )
        }
    }
}

/**
 * Colour and layers attached to a part or to some of its faces (ADR-0039). An annotation on the result: no shape
 * operation, no effect on the feature history. [faces] and [edges] are sorted by index and hold each index at most
 * once. Edge colours are opaque.
 */
data class ShapeStyle(
    val colour: StepColour? = null,
    val layer: String? = null,
    val faces: List<FaceStyle> = emptyList(),
    val edges: List<EdgeStyle> = emptyList(),
) {
    init {
        layer?.let { LayerNames.require(it) }
        if (edges.size > MAX_STYLED_EDGES) {
            throw StyleException(
                StyleErrorKind.TARGET,
                "too many styled edges: ${edges.size} (limit $MAX_STYLED_EDGES)",
            )
        }
        for (i in 1 until edges.size) {
            if (edges[i].edgeIndex <= edges[i - 1].edgeIndex) {
                throw StyleException(
                    StyleErrorKind.CONFLICT,
                    "edge styles must be sorted by edge index and unique, index ${edges[i].edgeIndex} breaks that",
                )
            }
        }
        if (faces.size > MAX_STYLED_FACES) {
            throw StyleException(
                StyleErrorKind.TARGET,
                "too many styled faces: ${faces.size} (limit $MAX_STYLED_FACES)",
            )
        }
        for (i in 1 until faces.size) {
            if (faces[i].faceIndex <= faces[i - 1].faceIndex) {
                throw StyleException(
                    StyleErrorKind.CONFLICT,
                    "face styles must be sorted by face index and unique, index ${faces[i].faceIndex} breaks that",
                )
            }
        }
        val layers = (faces.mapNotNull { it.layer } + listOfNotNull(layer)).toSet()
        if (layers.size > MAX_LAYER_NAMES) {
            throw StyleException(
                StyleErrorKind.INVALID_LAYER,
                "too many distinct layer names: ${layers.size} (limit $MAX_LAYER_NAMES)",
            )
        }
    }

    val isEmpty: Boolean
        get() =
            colour == null &&
                layer == null &&
                edges.isEmpty() &&
                faces.none { it.colour != null || it.layer != null }

    companion object {
        val NONE = ShapeStyle()
        const val MAX_STYLED_FACES: Int = 10_000
        const val MAX_STYLED_EDGES: Int = 10_000
        const val MAX_LAYER_NAMES: Int = 256
    }
}
