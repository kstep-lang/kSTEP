package dev.kstep.geometry

/**
 * An sRGB colour with components in `0.0..1.0` (the value written in the STEP file's `COLOUR_RGB`) and an [alpha]
 * (opacity, `1.0` = opaque; `SURFACE_STYLE_TRANSPARENT` carries `1 - alpha`, ADR-0042).
 */
data class RgbColor(
    val red: Double,
    val green: Double,
    val blue: Double,
    val alpha: Double = 1.0,
) {
    init {
        require(listOf(red, green, blue, alpha).all { it.isFinite() && it in 0.0..1.0 }) {
            "colour components must be finite and within 0..1, got ($red, $green, $blue, alpha $alpha)"
        }
    }
}
