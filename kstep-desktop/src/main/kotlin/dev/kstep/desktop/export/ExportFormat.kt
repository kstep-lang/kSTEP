package dev.kstep.desktop.export

/** The export formats of the dialog, in the fixed order of the dialog (ADR-0057). [workerId] is the `--format` value. */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val purpose: String,
    val workerId: String,
) {
    STEP("STEP AP242", ".step", "Exchange with CAD tools, exact geometry and structure", "step"),
    GLB("GLB", ".glb", "Viewing and web, triangle mesh with colors", "glb"),
    STL("STL", ".stl", "3D printing, triangle mesh only", "stl"),
    THREE_MF("3MF", ".3mf", "3D printing, mesh with units", "3mf"),
    ;

    companion object {
        val ALL: List<ExportFormat> = entries.toList()
        val DEFAULT: ExportFormat = STEP
    }
}
