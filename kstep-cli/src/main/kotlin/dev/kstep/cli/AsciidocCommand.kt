package dev.kstep.cli

import dev.kstep.asciidoc.AsciidocOptions
import dev.kstep.asciidoc.AsciidocProcessor
import java.io.File
import kotlin.system.exitProcess

/**
 * Thin CLI adapter for `kstep asciidoc`: builds an [AsciidocOptions] from [CliCommand.Asciidoc],
 * dispatches to [AsciidocProcessor]'s single-file or tree mode, and reports the result -- see
 * docs/adr/ADR-0019-kstep-asciidoc.adoc for the full contract.
 */
fun runAsciidoc(command: CliCommand.Asciidoc) {
    val options =
        AsciidocOptions(
            defaultFormat = command.format,
            width = command.width,
            height = command.height,
            requireGeometry = command.requireGeometry,
            onError = command.onError,
        )
    val processor = AsciidocProcessor(options)
    val report =
        when (val mode = command.mode) {
            is AsciidocMode.SingleFile -> processor.processFile(File(mode.inputPath), File(mode.outputPath))
            is AsciidocMode.Tree -> processor.processTree(File(mode.inputDir), File(mode.outputDir))
        }

    report.warnings.forEach { System.err.println(it) }

    val failure = report.failure
    if (failure != null) {
        val location = failure.line?.let { ":$it" } ?: ""
        System.err.println("Error: ${failure.file}$location: ${failure.message}")
        exitProcess(1)
    }

    when (val mode = command.mode) {
        is AsciidocMode.SingleFile -> println("Wrote ${mode.outputPath} (${report.renderedBlocks} block(s))")
        is AsciidocMode.Tree ->
            println(
                "Rewrote ${report.processedAdocFiles} file(s), ${report.renderedBlocks} block(s); " +
                    "copied ${report.copiedFiles} file(s)",
            )
    }
}
