package dev.kstep.desktop.export

/** A file name the script suggested for [format] (ADR-0063). Validated by [ExportNaming.isValidDeclaredName]; never a path. */
data class ScriptExportTarget(
    val format: ExportFormat,
    val fileName: String,
)
