package dev.kstep.geometry

/**
 * STEP Application Protocol schema identifier that OCCT's `STEPControl_Writer` should tag an
 * exported file with (`Interface_Static "write.step.schema"`). Defaults to [AP242DIS] throughout
 * this module, matching the rest of kSTEP's AP242 focus (see kSTEP-ADR-0001).
 */
enum class StepSchema(
    internal val occtValue: String,
) {
    AP203("AP203"),
    AP214IS("AP214IS"),
    AP242DIS("AP242DIS"),
}
