package dev.kstep.asciidoc

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ProcessFailure(
    val file: String,
    val line: Int?,
    val message: String,
)

data class ProcessReport(
    val processedAdocFiles: Int,
    val copiedFiles: Int,
    val skippedSymlinks: Int,
    val renderedBlocks: Int,
    val warnings: List<String>,
    /** `null` == success. */
    val failure: ProcessFailure?,
)

private fun stripAdocExtension(fileName: String): String =
    if (fileName.length > 5 && fileName.substring(fileName.length - 5).equals(".adoc", ignoreCase = true)) {
        fileName.substring(0, fileName.length - 5)
    } else {
        fileName
    }

private fun isAdocFile(fileName: String): Boolean =
    fileName.length > 5 && fileName.substring(fileName.length - 5).equals(".adoc", ignoreCase = true)

/**
 * The I/O-/mirroring layer of `kstep asciidoc`: single-file and whole-directory-tree modes, both
 * built on [AsciidocRewriter]'s pure scan-render-reassemble core. See
 * docs/adr/ADR-0019-kstep-asciidoc.adoc for the full contract (symlink handling, the
 * `--output-dir`-inside-`--input-dir` guard, the walk-bomb/file-size caps). Every output-path
 * resolution and collision check goes through [OutputRoot]/[WriteTarget] -- see that file's own
 * KDoc for why a single, real-path-keyed abstraction replaced the previous per-call-site pattern.
 */
class AsciidocProcessor(
    private val options: AsciidocOptions,
) {
    fun processFile(
        input: File,
        output: File,
    ): ProcessReport {
        val inputPath = input.toPath()
        if (!Files.isRegularFile(inputPath)) {
            return failureReport(ProcessFailure(input.path, null, "input file not found: '${input.path}'"))
        }
        val inputSize =
            try {
                Files.size(inputPath)
            } catch (e: IOException) {
                return failureReport(ProcessFailure(input.path, null, "failed to read '${input.path}': ${e.message}"))
            }
        if (inputSize > AsciidocLimits.MAX_ADOC_BYTES) {
            return failureReport(
                ProcessFailure(input.path, null, "input file exceeds ${AsciidocLimits.MAX_ADOC_BYTES} bytes"),
            )
        }
        val text =
            try {
                Files.readString(inputPath)
            } catch (e: IOException) {
                return failureReport(ProcessFailure(input.path, null, "failed to read '${input.path}': ${e.message}"))
            }
        val sourceDir = inputPath.toAbsolutePath().normalize().parent
        val document =
            AsciidocDocument(
                text = text,
                sourceDir = sourceDir,
                inputRoot = sourceDir,
                baseName = stripAdocExtension(input.name),
            )

        return when (val result = AsciidocRewriter(options).process(document)) {
            is RewriteResult.Failed -> failureReport(ProcessFailure(input.path, result.line, result.message))
            is RewriteResult.Rendered -> {
                val outputPath = output.toPath().toAbsolutePath().normalize()
                val outputParent = outputPath.parent ?: outputPath
                val outRoot =
                    OutputRoot.resolve(outputParent)
                        ?: return failureReport(
                            ProcessFailure(output.path, null, "failed to resolve '$outputParent'"),
                        )
                val outputTarget =
                    outRoot.resolveTarget(outputPath.fileName.toString())
                        ?: return failureReport(
                            ProcessFailure(output.path, null, "output path '${output.path}' escapes its own directory"),
                        )
                // Single-file mode has no whole-tree mirroring to collide with, but its OWN
                // output can still collide with itself: the rewritten `.adoc` must not be the
                // SAME physical file as the script it was read from (an in-place `--input
                // x.adoc --output x.adoc` would otherwise silently destroy the original script
                // the moment the first block renders -- see AsciidocProcessorTest H4/D23), and a
                // rendered image must not clobber the `.adoc` output either (a fresh, non-null
                // `reservedOutputPaths` below catches that the same way tree mode always has).
                val inputReal =
                    try {
                        inputPath.toRealPath()
                    } catch (_: IOException) {
                        null
                    }
                if (inputReal != null && inputReal == outputTarget.identity) {
                    return failureReport(
                        ProcessFailure(
                            output.path,
                            null,
                            "--output must not be the same file as --input '${input.path}' -- refusing to " +
                                "overwrite the source script",
                        ),
                    )
                }
                val reservedOutputPaths = mutableSetOf<Path>()
                val writeFailure = writeRendered(outputTarget, result, reservedOutputPaths)
                if (writeFailure != null) return failureReport(writeFailure)
                ProcessReport(
                    processedAdocFiles = 1,
                    copiedFiles = 0,
                    skippedSymlinks = 0,
                    renderedBlocks = result.images.size,
                    warnings = result.warnings,
                    failure = null,
                )
            }
        }
    }

    fun processTree(
        inputDir: File,
        outputDir: File,
    ): ProcessReport {
        val inRoot = inputDir.toPath().toAbsolutePath().normalize()
        val outRootLexical = outputDir.toPath().toAbsolutePath().normalize()
        // Lexical fast path: catches the common case (`--output-dir` textually nested inside
        // `--input-dir` or vice versa) BEFORE anything -- including `Files.createDirectories`
        // below -- touches the filesystem. See AsciidocProcessorTest D5, which asserts the
        // rejected `outputDir` is never even created.
        if (outRootLexical.startsWith(inRoot) || inRoot.startsWith(outRootLexical)) {
            return failureReport(
                ProcessFailure(
                    inputDir.path,
                    null,
                    "--output-dir must not be inside --input-dir, nor --input-dir inside --output-dir",
                ),
            )
        }
        if (!Files.isDirectory(inRoot)) {
            return failureReport(ProcessFailure(inputDir.path, null, "input directory not found: '${inputDir.path}'"))
        }

        // Physical re-check: the lexical guard above compares NORMALIZED-but-unresolved paths, so
        // an `--output-dir` that is ITSELF a symlink into (or an ancestor of) `--input-dir` sails
        // straight through it -- textually the two paths share no prefix at all. This MUST run
        // BEFORE `Files.createDirectories(outRootLexical)` (not after, as an earlier revision
        // did) -- `realPathOfNearestExistingAncestor` tolerates `outRootLexical` not existing yet
        // by walking up to whichever ancestor does, so the check needs no directory to have been
        // created first. Running it first closes AsciidocProcessorTest's H2 finding: the earlier
        // ordering could plant a directory INSIDE `--input-dir` (via a symlinked intermediate
        // component of `--output-dir`) even on a run that was ultimately rejected. See
        // AsciidocProcessorTest D17/D18/H2.
        val inRootProbe =
            OutputRoot.resolve(inRoot)
                ?: return failureReport(
                    ProcessFailure(inputDir.path, null, "failed to resolve '${inputDir.path}'"),
                )
        val outRootProbe =
            OutputRoot.resolve(outRootLexical)
                ?: return failureReport(
                    ProcessFailure(outputDir.path, null, "failed to resolve '${outputDir.path}'"),
                )
        if (outRootProbe.overlapsWith(inRootProbe.real)) {
            return failureReport(
                ProcessFailure(
                    inputDir.path,
                    null,
                    "--output-dir must not be inside --input-dir, nor --input-dir inside --output-dir " +
                        "(resolved via symlink)",
                ),
            )
        }

        // Only now -- after BOTH the lexical and the physical overlap checks have passed -- does
        // anything touch the filesystem.
        try {
            Files.createDirectories(outRootLexical)
        } catch (e: IOException) {
            return failureReport(
                ProcessFailure(outputDir.path, null, "failed to create '${outputDir.path}': ${e.message}"),
            )
        }
        val outRoot =
            OutputRoot.resolve(outRootLexical)
                ?: return failureReport(
                    ProcessFailure(outputDir.path, null, "failed to resolve '${outputDir.path}'"),
                )

        // `.limit(MAX + 1)` is applied to the lazy walk stream itself, BEFORE `.sorted()`
        // materializes anything -- this bounds the walk to at most MAX_FILES_IN_TREE + 1 entries
        // actually read off disk. Materializing the full walk first and only checking its size
        // afterwards (as a plain `Files.walk(inRoot).use { it.sorted().toList() }.size > MAX`
        // would) defeats the bound entirely: the oversized list is already sitting in the heap by
        // the time the check runs. The walk itself -- and its lazy enumeration inside `.toList()`
        // -- can throw for an unreadable subdirectory; both are caught and turned into a clean
        // ProcessFailure instead of the raw stack trace an earlier revision let escape (H3).
        val walkStream =
            try {
                Files.walk(inRoot)
            } catch (e: IOException) {
                return failureReport(
                    ProcessFailure(inputDir.path, null, "failed to walk '${inputDir.path}': ${e.message}"),
                )
            }
        val allPaths: List<Path>
        try {
            val limited = walkStream.limit(AsciidocLimits.MAX_FILES_IN_TREE + 1L).toList()
            if (limited.size > AsciidocLimits.MAX_FILES_IN_TREE) {
                return failureReport(
                    ProcessFailure(
                        inputDir.path,
                        null,
                        "input tree exceeds ${AsciidocLimits.MAX_FILES_IN_TREE} entries",
                    ),
                )
            }
            allPaths = limited.sorted()
        } catch (e: UncheckedIOException) {
            return failureReport(
                ProcessFailure(
                    inputDir.path,
                    null,
                    "failed to walk '${inputDir.path}': ${e.cause?.message ?: e.message}",
                ),
            )
        } catch (e: IOException) {
            return failureReport(
                ProcessFailure(inputDir.path, null, "failed to walk '${inputDir.path}': ${e.message}"),
            )
        } finally {
            walkStream.close()
        }

        var processedAdoc = 0
        var copied = 0
        var skippedSymlinks = 0
        var renderedBlocks = 0
        val warnings = mutableListOf<String>()
        // Tracks every OUTPUT path (mirrored/copied file, rewritten .adoc, or rendered image)
        // already produced by THIS tree walk, so a later entry can never silently clobber an
        // earlier one -- e.g. a copied `logo.svg` followed by a kstep block whose default/explicit
        // `target` also resolves to `logo.svg`, or two documents both using `target=shared`. Keyed
        // by [WriteTarget.identity] (a REAL path), not the lexical target path -- an earlier
        // revision keyed this by `target.toAbsolutePath().normalize()` instead, which let two
        // DIFFERENT lexical paths that alias the SAME physical file via an output-dir-internal
        // symlink silently clobber each other with no collision ever detected (H1). The
        // per-document `usedNames` check in [AsciidocRewriter] only catches a collision WITHIN one
        // document; this set is what catches it ACROSS documents and across copies.
        val reservedOutputPaths = mutableSetOf<Path>()

        for (path in allPaths) {
            if (path == inRoot) continue
            val rel = inRoot.relativize(path)
            // `rel` is lexically safe (it comes from relativizing a REAL path found by the walk
            // against `inRoot`, so it can never contain ".."), but that alone does not make
            // resolving it against `outRoot` safe to write through: a directory ALREADY PRESENT
            // under `outRoot` before this run started -- e.g. an intermediate path component that
            // is itself a symlink to somewhere outside `outRoot`, or one aliasing another
            // directory INSIDE `outRoot` -- makes the naive resolve escape the output root, or
            // silently alias another entry's target, even though every input path is clean.
            // `OutputRoot.resolveTarget` applies the same physical (real-path,
            // walk-to-nearest-existing-ancestor) check `writeRendered` already applies to
            // `:imagesdir:`, closing both escapes for the mirrored tree itself (copied files,
            // rewritten `.adoc` files, and mirrored directories) -- see AsciidocProcessorTest
            // D12/H1.
            val target =
                outRoot.resolveTarget(rel.toString())
                    ?: return ProcessReport(
                        processedAdoc,
                        copied,
                        skippedSymlinks,
                        renderedBlocks,
                        warnings,
                        ProcessFailure(
                            rel.toString(),
                            null,
                            "output path '$rel' escapes the output directory",
                        ),
                    )

            if (Files.isSymbolicLink(path)) {
                skippedSymlinks++
                warnings += "skipped symlink: $rel"
                continue
            }
            if (Files.isDirectory(path)) {
                try {
                    Files.createDirectories(target.path)
                } catch (e: IOException) {
                    return ProcessReport(
                        processedAdoc,
                        copied,
                        skippedSymlinks,
                        renderedBlocks,
                        warnings,
                        ProcessFailure(rel.toString(), null, "failed to create directory '$rel': ${e.message}"),
                    )
                }
                continue
            }
            if (!Files.isRegularFile(path)) continue

            val fileName = path.fileName.toString()
            if (isAdocFile(fileName)) {
                val fileSize =
                    try {
                        Files.size(path)
                    } catch (e: IOException) {
                        return ProcessReport(
                            processedAdoc,
                            copied,
                            skippedSymlinks,
                            renderedBlocks,
                            warnings,
                            ProcessFailure(rel.toString(), null, "failed to read '$rel': ${e.message}"),
                        )
                    }
                if (fileSize > AsciidocLimits.MAX_ADOC_BYTES) {
                    return ProcessReport(
                        processedAdoc,
                        copied,
                        skippedSymlinks,
                        renderedBlocks,
                        warnings,
                        ProcessFailure(
                            rel.toString(),
                            null,
                            "input file exceeds ${AsciidocLimits.MAX_ADOC_BYTES} bytes",
                        ),
                    )
                }
                val text =
                    try {
                        Files.readString(path)
                    } catch (e: IOException) {
                        return ProcessReport(
                            processedAdoc,
                            copied,
                            skippedSymlinks,
                            renderedBlocks,
                            warnings,
                            ProcessFailure(rel.toString(), null, "failed to read '$rel': ${e.message}"),
                        )
                    }
                val document =
                    AsciidocDocument(
                        text = text,
                        sourceDir = path.parent,
                        inputRoot = inRoot,
                        baseName = stripAdocExtension(fileName),
                    )
                when (val result = AsciidocRewriter(options).process(document)) {
                    is RewriteResult.Failed ->
                        return ProcessReport(
                            processedAdoc,
                            copied,
                            skippedSymlinks,
                            renderedBlocks,
                            warnings,
                            ProcessFailure(rel.toString(), result.line, result.message),
                        )
                    is RewriteResult.Rendered -> {
                        val writeFailure = writeRendered(target, result, reservedOutputPaths)
                        if (writeFailure != null) {
                            return ProcessReport(
                                processedAdoc,
                                copied,
                                skippedSymlinks,
                                renderedBlocks,
                                warnings,
                                writeFailure,
                            )
                        }
                        processedAdoc++
                        renderedBlocks += result.images.size
                        warnings += result.warnings
                    }
                }
            } else {
                if (!reservedOutputPaths.add(target.identity)) {
                    return ProcessReport(
                        processedAdoc,
                        copied,
                        skippedSymlinks,
                        renderedBlocks,
                        warnings,
                        ProcessFailure(
                            rel.toString(),
                            null,
                            "output path '${target.identity}' is already produced by another file in this tree",
                        ),
                    )
                }
                try {
                    target.path.parent?.let { Files.createDirectories(it) }
                    Files.copy(path, target.path, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: IOException) {
                    return ProcessReport(
                        processedAdoc,
                        copied,
                        skippedSymlinks,
                        renderedBlocks,
                        warnings,
                        ProcessFailure(rel.toString(), null, "failed to copy '$rel': ${e.message}"),
                    )
                }
                copied++
            }
        }

        return ProcessReport(processedAdoc, copied, skippedSymlinks, renderedBlocks, warnings, null)
    }

    /**
     * Writes one rendered document's `.adoc` text and every one of its images. [outputTarget] is
     * the already-resolved [WriteTarget] for the `.adoc` file itself. [reservedOutputPaths], when
     * non-`null`, is the whole-run collision-tracking set -- shared across every file
     * [processTree] writes in tree mode, or a fresh per-document set in single-file mode (never
     * `null` any more: a rendered image colliding with the `.adoc` output itself is a real
     * collision even with no wider tree to share it with -- see AsciidocProcessorTest H4/D24).
     * Every one of [result]'s images is resolved AND containment-checked against the real output
     * directory via a freshly-built [OutputRoot] -- and every target (including the collision
     * check against [reservedOutputPaths]) is validated BEFORE anything is written, preserving the
     * partial-output guarantee: a rejected image must not leave a half-written `.adoc` or a subset
     * of its images on disk, and must never land outside the output directory via a lexical escape
     * or a symlinked directory placed inside the tree (see ADR-0019's Security section, "Path
     * traversal (write)").
     *
     * Every actual filesystem write below is wrapped in `try`/`catch(IOException)` and turned into
     * a [ProcessFailure] instead of escaping as a raw stack trace -- a dangling `:imagesdir:`
     * symlink, a read-only `--output-dir`, or a concurrent deletion can all throw here even after
     * every path has already passed validation. Images are written BEFORE the `.adoc` text (not
     * after, as a naive top-to-bottom reading of "write the document, then its images" would do)
     * so that an I/O failure partway through the image loop can never leave a `.adoc` on disk that
     * references an image which was never written -- see AsciidocProcessorTest D13/D14.
     */
    private fun writeRendered(
        outputTarget: WriteTarget,
        result: RewriteResult.Rendered,
        reservedOutputPaths: MutableSet<Path>?,
    ): ProcessFailure? {
        val normalizedOutputPath = outputTarget.path
        val outputDir = normalizedOutputPath.parent ?: normalizedOutputPath

        // `OutputRoot.resolve`/`resolveTarget` (used just below, per image) requires `outputDir`
        // to already exist -- it real-paths the root itself. `outputDir` need not exist yet on
        // entry here (a brand-new single-file `--output`, or a not-yet-mirrored tree
        // subdirectory), so it is created first, before anything is resolved or written.
        try {
            Files.createDirectories(outputDir)
        } catch (e: IOException) {
            return ProcessFailure(outputTarget.path.toString(), null, "failed to create '$outputDir': ${e.message}")
        }
        val outDirRoot =
            OutputRoot.resolve(outputDir)
                ?: return ProcessFailure(outputTarget.path.toString(), null, "failed to resolve '$outputDir'")

        val resolvedImages = mutableListOf<Pair<WriteTarget, RenderedImage>>()
        for (image in result.images) {
            if (!AsciidocPaths.isSyntacticallySafeRelativePath(image.relativePath)) {
                return ProcessFailure(
                    outputTarget.path.toString(),
                    null,
                    "image path '${image.relativePath}' escapes the output directory",
                )
            }
            val imageTarget =
                outDirRoot.resolveTarget(image.relativePath)
                    ?: return ProcessFailure(
                        outputTarget.path.toString(),
                        null,
                        "image path '${image.relativePath}' escapes the output directory",
                    )
            resolvedImages += imageTarget to image
        }

        if (reservedOutputPaths != null) {
            val allTargets = listOf(outputTarget.identity) + resolvedImages.map { it.first.identity }
            for (candidateIdentity in allTargets) {
                if (!reservedOutputPaths.add(candidateIdentity)) {
                    return ProcessFailure(
                        outputTarget.path.toString(),
                        null,
                        "output path '$candidateIdentity' is already produced by another file in this tree",
                    )
                }
            }
        }

        // `realPathOfNearestExistingAncestor`'s ancestor-walk (used just above, via
        // `resolveTarget`) treats a DANGLING symlink as "not yet existing" -- `Files.exists`
        // follows symlinks, so it climbs straight past one to the nearest real ancestor and never
        // rejects it. That is fine for an intermediate directory component (an actual write
        // through it still fails, see the `:imagesdir:`-is-a-dangling-symlink case covered by
        // AsciidocProcessorTest D13), but a LEAF write target that is ITSELF a symlink -- dangling
        // or not -- is a different story: `Files.write`/`Files.writeString` below use
        // `TRUNCATE_EXISTING` semantics (their implicit default), which FOLLOW a symlink and would
        // happily write straight through it to wherever it points, including outside this output
        // root entirely. `Files.copy(..., REPLACE_EXISTING)` in `processTree`'s non-`.adoc` branch
        // is unaffected by this -- `REPLACE_EXISTING` unlinks the target first instead of
        // following it, which is exactly why that copy path never needed this guard. Checked for
        // every write target up front, before any write happens, to preserve the "everything
        // validated before anything is written" contract described in this method's own KDoc --
        // see AsciidocProcessorTest D15/D16.
        // NOTE: `java.nio.file.Path` itself implements `Iterable<Path>` (iterating its own
        // name-elements) -- `list + aPath` would silently resolve to `plus(elements:
        // Iterable<T>)` and spread `aPath`'s segments into the list instead of appending `aPath`
        // as one element. `plusElement` is unambiguous regardless of what `T` implements, which is
        // exactly why it is used here instead of the `+` operator.
        for (writeTarget in resolvedImages.map { it.first.path }.plusElement(normalizedOutputPath)) {
            if (Files.isSymbolicLink(writeTarget)) {
                return ProcessFailure(
                    outputTarget.path.toString(),
                    null,
                    "output path '$writeTarget' is a symlink -- refusing to write through it",
                )
            }
        }

        try {
            for ((imageTarget, image) in resolvedImages) {
                imageTarget.path.parent?.let { Files.createDirectories(it) }
                Files.write(imageTarget.path, image.bytes)
            }
            Files.writeString(normalizedOutputPath, result.text)
        } catch (e: IOException) {
            return ProcessFailure(outputTarget.path.toString(), null, "failed to write output: ${e.message}")
        }
        return null
    }

    private fun failureReport(failure: ProcessFailure): ProcessReport =
        ProcessReport(
            processedAdocFiles = 0,
            copiedFiles = 0,
            skippedSymlinks = 0,
            renderedBlocks = 0,
            warnings = emptyList(),
            failure = failure,
        )
}
