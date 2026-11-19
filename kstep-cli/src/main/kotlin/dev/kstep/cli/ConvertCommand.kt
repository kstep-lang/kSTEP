package dev.kstep.cli

import dev.kstep.cli.okf.ConvertFiles
import dev.kstep.cli.okf.DisplayName
import dev.kstep.cli.okf.OkfConverter
import dev.kstep.cli.okf.OkfLimits
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** What `kstep convert` writes; see ADR-0060. */
private class PlannedFile(
    val name: String,
    val bytes: ByteArray,
)

/**
 * Runs `kstep convert`: pure text conversion, never evaluates a script. Returns the exit code (0 or 1) and does NOT call
 * `exitProcess`, so it can be tested in-process. Nothing is written unless every target has been checked first.
 */
internal fun runConvert(
    command: CliCommand.Convert,
    out: PrintStream = System.out,
    err: PrintStream = System.err,
    beforeMove: () -> Unit = {},
): Int {
    fun fail(message: String): Int {
        err.println("kstep convert: $message")
        return 1
    }

    val inputPath =
        try {
            Paths.get(command.inputPath)
        } catch (_: InvalidPathException) {
            return fail("the input path is not valid")
        }
    val fileName = inputPath.fileName?.toString() ?: return fail("the input path has no file name")
    val shown = DisplayName.escaped(fileName)
    val lower = fileName.lowercase()
    val stem: String
    val maxBytes: Long
    when (command.target) {
        ConvertTarget.OKF -> {
            if (!lower.endsWith(OkfConverter.SCRIPT_SUFFIX)) return fail("--to okf expects a *.kstep.kts file")
            stem = fileName.substring(0, fileName.length - OkfConverter.SCRIPT_SUFFIX.length)
            maxBytes = OkfLimits.MAX_BLOCK_BYTES.toLong()
        }
        ConvertTarget.KTS -> {
            if (!lower.endsWith(".md")) return fail("--to kts expects a *.md file")
            stem = fileName.substring(0, fileName.length - 3)
            maxBytes = OkfLimits.MAX_FILE_BYTES
        }
    }

    val text =
        when (val r = ConvertFiles.readBounded(inputPath, maxBytes)) {
            is ConvertFiles.ReadResult.Ok -> r.text
            is ConvertFiles.ReadResult.Failed -> return fail("$shown: ${r.message}")
        }
    val inputReal =
        try {
            inputPath.toRealPath()
        } catch (_: java.io.IOException) {
            return fail("$shown: the input file cannot be resolved")
        }
    val outDirPath: Path =
        try {
            command.outDir?.let { Paths.get(it) } ?: inputReal.parent
        } catch (_: InvalidPathException) {
            return fail("the output folder path is not valid")
        }
    val outReal =
        when (val r = ConvertFiles.resolveOutDir(outDirPath)) {
            is ConvertFiles.DirResult.Ok -> r.real
            is ConvertFiles.DirResult.Failed -> return fail(r.message)
        }

    val planned = ArrayList<PlannedFile>()
    when (command.target) {
        ConvertTarget.OKF -> {
            val title = OkfConverter.titleFromStem(stem)
            when (val w = OkfConverter.wrapAsOkf(text, title)) {
                is OkfConverter.WrapResult.Ok -> {
                    val name = OkfConverter.sanitizeStem(stem) + ".md"
                    planned += PlannedFile(name, w.note.toByteArray(StandardCharsets.UTF_8))
                }
                is OkfConverter.WrapResult.Rejected -> return fail("$shown: ${w.reason}")
            }
        }
        ConvertTarget.KTS ->
            when (val x = OkfConverter.extractScripts(text, stem)) {
                is OkfConverter.ExtractResult.Ok -> {
                    for (line in x.skippedEmptyLines) {
                        err.println(
                            "kstep convert: skipped the empty kstep block in line $line",
                        )
                    }
                    for (s in x.scripts) {
                        planned +=
                            PlannedFile(s.fileName, s.source.toByteArray(StandardCharsets.UTF_8))
                    }
                }
                is OkfConverter.ExtractResult.Rejected ->
                    return fail("$shown: ${x.reason}" + (x.line?.let { " (line $it)" } ?: ""))
            }
    }

    if (planned.map { it.name.lowercase() }.toSet().size != planned.size) {
        return fail("the converted file names are not unique")
    }
    // All-or-nothing check of every target before the first byte is written.
    val conflicts = ArrayList<String>()
    for (p in planned) {
        when (val c = ConvertFiles.checkTarget(outReal, p.name, inputReal)) {
            is ConvertFiles.TargetCheck.Free -> Unit
            is ConvertFiles.TargetCheck.Exists -> if (!command.force) conflicts += DisplayName.escaped(p.name)
            is ConvertFiles.TargetCheck.Invalid -> return fail("${DisplayName.escaped(p.name)}: ${c.message}")
        }
    }
    if (conflicts.isNotEmpty()) {
        return fail("${conflicts.first()} already exists; use --force to replace it. Nothing was written.")
    }

    val written = ArrayList<String>()
    for (p in planned) {
        when (val r = ConvertFiles.writeAtomic(outReal, p.name, p.bytes, command.force, beforeMove)) {
            ConvertFiles.WriteResult.Written -> {
                written += p.name
                out.println("Wrote ${DisplayName.escaped(p.name)}")
            }
            ConvertFiles.WriteResult.AlreadyExists ->
                return failAfter(
                    err,
                    written,
                    "${DisplayName.escaped(p.name)} appeared meanwhile; use --force to replace it",
                )
            is ConvertFiles.WriteResult.Failed ->
                return failAfter(err, written, "${DisplayName.escaped(p.name)}: ${r.message}")
        }
    }
    return 0
}

private fun failAfter(
    err: PrintStream,
    written: List<String>,
    message: String,
): Int {
    err.println("kstep convert: $message")
    if (written.isNotEmpty()) {
        err.println("kstep convert: already written: ${written.joinToString(", ") { DisplayName.escaped(it) }}")
    }
    return 1
}
