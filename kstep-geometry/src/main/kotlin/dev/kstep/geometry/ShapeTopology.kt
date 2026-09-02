package dev.kstep.geometry

/**
 * Unique B-Rep topology element counts for an [OcctShape], as computed by the native bridge via
 * `TopExp::MapShapes` -- an *indexed map*, not `TopExp_Explorer`. A naive explorer walk counts a
 * sub-shape once per parent that references it (e.g. a box edge is shared by two adjacent faces,
 * so an explorer-based count would report 24 edges instead of the true 12); `MapShapes`
 * de-duplicates by shape identity first, so every count here is genuinely unique. See
 * `src/main/cpp/kstep_occt_bridge.cpp` and docs/adr/ADR-0005-occt-jni-bridge.adoc.
 */
data class ShapeTopology(
    val solids: Int,
    val shells: Int,
    val faces: Int,
    val edges: Int,
    val vertices: Int,
)
