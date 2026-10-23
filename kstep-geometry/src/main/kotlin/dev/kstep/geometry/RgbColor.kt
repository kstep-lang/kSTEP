package dev.kstep.geometry

/** An sRGB colour with components in `0.0..1.0` (the value written in the STEP file's `COLOUR_RGB`). */
data class RgbColor(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    init {
        require(listOf(red, green, blue).all { it.isFinite() && it in 0.0..1.0 }) {
            "colour components must be finite and within 0..1, got ($red, $green, $blue)"
        }
    }
}
