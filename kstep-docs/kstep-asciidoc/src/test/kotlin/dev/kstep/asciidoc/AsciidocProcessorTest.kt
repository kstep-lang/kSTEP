package dev.kstep.asciidoc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

private fun productScript(id: String): String =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    val part = product("$id") { name = "Part"; frameOfReference = setOf(prodCtx) }.getOrThrow()
    val prodFormation = productDefinitionFormation("$id-F") { ofProduct = part }.getOrThrow()
    val definition = productDefinition("$id-D") { formation = prodFormation; frameOfReference = defCtx }.getOrThrow()
    stepFile(fileName = "$id.step", timestamp = "2026-01-01T00:00:00Z") { root(definition) }
    """.trimIndent()

/**
 * Computed ONCE, up front -- whether this filesystem supports symlinks at all (false on some
 * Windows configurations without Developer Mode/admin rights). Every symlink-dependent test below
 * uses `.config(enabled = symlinksSupported)` rather than a per-test `try { createSymbolicLink }
 * catch (UnsupportedOperationException) { false }` guard wrapping the whole test body in an `if`
 * -- the latter pattern makes an unsupported-symlinks run report as a PASSING test with zero
 * assertions executed, indistinguishable in a green CI run from the guard actually having been
 * exercised. `config(enabled = ...)` instead reports the test as explicitly SKIPPED, which is
 * visible in the test report and cannot be mistaken for a real pass. See
 * docs/adr/ADR-0019-kstep-asciidoc.adoc's Stolperfalle on this (Round 5 finding).
 */
private val symlinksSupported: Boolean =
    try {
        val dir = Files.createTempDirectory("kstep-processor-test-symlink-probe")
        val link = dir.resolve("link")
        Files.createSymbolicLink(link, dir)
        true
    } catch (_: UnsupportedOperationException) {
        false
    }

class AsciidocProcessorTest :
    StringSpec({
        val options = AsciidocOptions()

        fun freshDir(name: String): File =
            File("build/asciidoc-processor-test/$name").apply {
                deleteRecursively()
                mkdirs()
            }

        // D1
        "single-file mode writes the rewritten .adoc and its image side by side" {
            val dir = freshDir("d1")
            val input = File(dir, "in.adoc")
            input.writeText("```kstep\n${productScript("D1-001")}\n```\n")
            val output = File(dir, "out.adoc")

            val report = AsciidocProcessor(options).processFile(input, output)

            report.failure shouldBe null
            output.exists() shouldBe true
            output.readText() shouldBe "image::in-1.svg[]\n"
            File(dir, "in-1.svg").exists() shouldBe true
        }

        // D2
        "tree mode mirrors nested directories, processes .adoc, and copies other files byte-identically" {
            val dir = freshDir("d2")
            val inputDir = File(dir, "in").apply { mkdirs() }
            val outputDir = File(dir, "out")
            File(inputDir, "sub").mkdirs()
            File(inputDir, "sub/page.adoc").writeText("```kstep\n${productScript("D2-001")}\n```\n")
            File(inputDir, "sub/notes.txt").writeText("plain text, unchanged")
            File(inputDir, "empty-dir").mkdirs()

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            report.failure shouldBe null
            report.processedAdocFiles shouldBe 1
            report.copiedFiles shouldBe 1
            File(outputDir, "sub/page.adoc").exists() shouldBe true
            File(outputDir, "sub/page-1.svg").exists() shouldBe true
            File(outputDir, "sub/notes.txt").readText() shouldBe "plain text, unchanged"
            File(outputDir, "empty-dir").isDirectory shouldBe true
        }

        // D3
        "an uppercase .ADOC extension is processed as an AsciiDoc file" {
            val dir = freshDir("d3")
            val inputDir = File(dir, "in").apply { mkdirs() }
            File(inputDir, "PAGE.ADOC").writeText("```kstep\n${productScript("D3-001")}\n```\n")
            val outputDir = File(dir, "out")

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            report.failure shouldBe null
            report.processedAdocFiles shouldBe 1
        }

        // D4
        "a symlinked file and a symlinked directory are skipped, not copied or resolved".config(
            enabled = symlinksSupported,
        ) {
            val dir = freshDir("d4")
            val inputDir = File(dir, "in").apply { mkdirs() }
            val outsideFile = File(dir, "outside.txt").apply { writeText("secret") }
            val outsideDir =
                File(dir, "outside-dir").apply {
                    mkdirs()
                    File(this, "x.txt").writeText("x")
                }
            val outputDir = File(dir, "out")

            Files.createSymbolicLink(File(inputDir, "link.txt").toPath(), outsideFile.toPath())
            Files.createSymbolicLink(File(inputDir, "link-dir").toPath(), outsideDir.toPath())

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            report.failure shouldBe null
            report.skippedSymlinks shouldBe 2
            File(outputDir, "link.txt").exists() shouldBe false
            File(outputDir, "link-dir").exists() shouldBe false
        }

        // D5
        "--output-dir inside --input-dir aborts before any write" {
            val dir = freshDir("d5")
            val inputDir = File(dir, "in").apply { mkdirs() }
            File(inputDir, "page.adoc").writeText("prose only\n")
            val outputDir = File(inputDir, "out")

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            (report.failure != null) shouldBe true
            outputDir.exists() shouldBe false
        }

        // D6
        "a path-traversal macro fails the file, writing no output" {
            val dir = freshDir("d6")
            val input = File(dir, "in.adoc")
            input.writeText("kstep::../../../etc/passwd[]\n")
            val output = File(dir, "out.adoc")

            val report = AsciidocProcessor(options).processFile(input, output)

            (report.failure != null) shouldBe true
            output.exists() shouldBe false
        }

        // D7
        "an oversized .adoc file fails cleanly" {
            val dir = freshDir("d7")
            val input = File(dir, "big.adoc")
            input.writeBytes(ByteArray((AsciidocLimits.MAX_ADOC_BYTES + 1024).toInt()))
            val output = File(dir, "out.adoc")

            val report = AsciidocProcessor(options).processFile(input, output)
            (report.failure != null) shouldBe true
        }

        // D8
        "a non-existent output directory is created" {
            val dir = freshDir("d8")
            val input = File(dir, "in.adoc").apply { writeText("prose only\n") }
            val output = File(dir, "nested/does/not/exist/out.adoc")

            val report = AsciidocProcessor(options).processFile(input, output)

            report.failure shouldBe null
            output.exists() shouldBe true
        }

        // D9
        "under FAIL, a document with one good and one broken block writes NEITHER the .adoc NOR the first image" {
            val dir = freshDir("d9")
            val input = File(dir, "in.adoc")
            input.writeText("```kstep\n${productScript("D9-001")}\n```\n\n```kstep\nval x = \n```\n")
            val output = File(dir, "out.adoc")

            val report = AsciidocProcessor(AsciidocOptions(onError = OnErrorPolicy.FAIL)).processFile(input, output)

            (report.failure != null) shouldBe true
            output.exists() shouldBe false
            File(dir, "in-1.svg").exists() shouldBe false
        }

        // D10
        "an :imagesdir: symlink that escapes --output-dir fails the write instead of landing outside it".config(
            enabled = symlinksSupported,
        ) {
            val dir = freshDir("d10")
            // Must be a directory genuinely OUTSIDE `dir` (the output root) -- a symlink target
            // still nested inside `dir` would not exercise the escape this test is guarding
            // against.
            val outsideDir = Files.createTempDirectory("kstep-processor-test-d10-outside").toFile()
            val input = File(dir, "in.adoc")
            input.writeText(":imagesdir: images\n\n```kstep\n${productScript("D10-001")}\n```\n")
            val output = File(dir, "out.adoc")

            Files.createSymbolicLink(File(dir, "images").toPath(), outsideDir.toPath())

            val report = AsciidocProcessor(options).processFile(input, output)

            (report.failure != null) shouldBe true
            File(outsideDir, "in-1.svg").exists() shouldBe false
        }

        // D11
        "in tree mode, a rendered image may not clobber an already-mirrored plain file of the same name" {
            val dir = freshDir("d11")
            val inputDir = File(dir, "in").apply { mkdirs() }
            // "logo.svg" sorts before "page.adoc" -- the plain file is copied first, then the
            // kstep block's default target would try to overwrite it with a rendered image.
            File(inputDir, "logo.svg").writeText("<svg><!-- handcrafted --></svg>")
            File(inputDir, "page.adoc").writeText("[kstep,logo]\n----\n${productScript("D11-001")}\n----\n")
            val outputDir = File(dir, "out")

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            (report.failure != null) shouldBe true
            File(outputDir, "logo.svg").readText() shouldBe "<svg><!-- handcrafted --></svg>"
        }

        // D12
        "a pre-existing symlinked directory inside --output-dir cannot be escaped through by the mirrored tree".config(
            enabled = symlinksSupported,
        ) {
            val dir = freshDir("d12")
            val inputDir = File(dir, "in").apply { mkdirs() }
            File(inputDir, "sub").mkdirs()
            File(inputDir, "sub/page.adoc").writeText("prose only\n")
            File(inputDir, "sub/data.txt").writeText("plain text")
            val outputDir = File(dir, "out").apply { mkdirs() }
            // Must be a directory genuinely OUTSIDE `dir` (the output root) -- see D10's comment.
            val outsideDir = Files.createTempDirectory("kstep-processor-test-d12-outside").toFile()

            Files.createSymbolicLink(File(outputDir, "sub").toPath(), outsideDir.toPath())

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            (report.failure != null) shouldBe true
            File(outsideDir, "page.adoc").exists() shouldBe false
            File(outsideDir, "data.txt").exists() shouldBe false
        }

        // D13
        "a dangling ':imagesdir:' symlink fails cleanly with a ProcessFailure instead of a raw IOException".config(
            enabled = symlinksSupported,
        ) {
            val dir = freshDir("d13")
            val input = File(dir, "in.adoc")
            input.writeText(":imagesdir: images\n\n```kstep\n${productScript("D13-001")}\n```\n")
            val output = File(dir, "out.adoc")

            Files.createSymbolicLink(File(dir, "images").toPath(), File(dir, "does-not-exist").toPath())

            val report = AsciidocProcessor(options).processFile(input, output)

            // Before the fix, `Files.createDirectories` throws `FileAlreadyExistsException`
            // (the dangling symlink itself is neither absent nor a directory) as a raw,
            // uncaught exception. The image write happening BEFORE the `.adoc` write (not
            // after) additionally means the failure must leave no `.adoc` behind at all.
            (report.failure != null) shouldBe true
            output.exists() shouldBe false
        }

        // D14
        "a tree with more than MAX_FILES_IN_TREE entries fails cleanly instead of exhausting memory" {
            val dir = freshDir("d14")
            val inputDir = File(dir, "in").apply { mkdirs() }
            repeat(AsciidocLimits.MAX_FILES_IN_TREE + 1) { n ->
                File(inputDir, "f$n.txt").writeText("x")
            }
            val outputDir = File(dir, "out")

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            (report.failure != null) shouldBe true
            (report.failure?.message?.contains("exceeds") ?: false) shouldBe true
        }

        // D15
        "a dangling symlink AT the rewritten .adoc's own leaf position is rejected, not written through".config(
            enabled = symlinksSupported,
        ) {
            val dir = freshDir("d15")
            val inputDir = File(dir, "in").apply { mkdirs() }
            File(inputDir, "page.adoc").writeText("Just prose, no kstep block.\n")
            val outputDir = File(dir, "out").apply { mkdirs() }
            // Must be a directory genuinely OUTSIDE `dir` (the output root) -- see D10's comment.
            val outsideDir = Files.createTempDirectory("kstep-processor-test-d15-outside").toFile()
            val escapedTarget = File(outsideDir, "escaped.adoc")

            Files.createSymbolicLink(File(outputDir, "page.adoc").toPath(), escapedTarget.toPath())

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            // Before the fix, `AsciidocPaths.realPathOfNearestExistingAncestor`'s ancestor-walk
            // treats this DANGLING symlink as "not yet existing" (`Files.exists` follows it) and
            // climbs straight past it, so the containment check passes and `Files.writeString`
            // FOLLOWS the symlink, writing the document straight through to `escapedTarget`.
            (report.failure != null) shouldBe true
            escapedTarget.exists() shouldBe false
        }

        // D16
        "a dangling symlink AT a rendered image's own leaf position is rejected, not written through".config(
            enabled = symlinksSupported,
        ) {
            val dir = freshDir("d16")
            val inputDir = File(dir, "in").apply { mkdirs() }
            File(inputDir, "doc.adoc").writeText("```kstep\n${productScript("D16-001")}\n```\n")
            val outputDir = File(dir, "out").apply { mkdirs() }
            // Must be a directory genuinely OUTSIDE `dir` (the output root) -- see D10's comment.
            val outsideDir = Files.createTempDirectory("kstep-processor-test-d16-outside").toFile()
            val escapedTarget = File(outsideDir, "escaped.svg")

            Files.createSymbolicLink(File(outputDir, "doc-1.svg").toPath(), escapedTarget.toPath())

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            (report.failure != null) shouldBe true
            escapedTarget.exists() shouldBe false
        }

        // D17
        "a symlinked --output-dir pointing directly at --input-dir is rejected before any write".config(
            enabled = symlinksSupported,
        ) {
            val dir = freshDir("d17")
            val inputDir = File(dir, "in").apply { mkdirs() }
            val docFile = File(inputDir, "doc.adoc")
            docFile.writeText("```kstep\n${productScript("D17-001")}\n```\n")
            val originalBytes = docFile.readBytes()
            val outputDir = File(dir, "out")

            Files.createSymbolicLink(outputDir.toPath(), inputDir.toPath())

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            // Before the fix, the overlap guard compared `outRoot`/`inRoot` as normalized-but-
            // unresolved paths -- "out" and "in" share no textual prefix, so it passed even
            // though "out" IS (via the symlink) the very same directory as "in". The tree walk
            // then rewrote `in/doc.adoc` in place, destroying the original script.
            (report.failure != null) shouldBe true
            docFile.readBytes() shouldBe originalBytes
        }

        // D18
        "a symlinked --output-dir pointing at a subdirectory of --input-dir is rejected before any write".config(
            enabled = symlinksSupported,
        ) {
            val dir = freshDir("d18")
            val inputDir = File(dir, "in").apply { mkdirs() }
            val generatedDir = File(inputDir, "generated").apply { mkdirs() }
            File(inputDir, "page.adoc").writeText("prose only\n")
            val outputDir = File(dir, "out")

            Files.createSymbolicLink(outputDir.toPath(), generatedDir.toPath())

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            // Before the fix, "out" (lexically a sibling of "in") shares no textual prefix
            // with "in" either -- but it resolves, via the symlink, to `in/generated`, a
            // directory INSIDE the input tree. Each run would mirror the input tree into its
            // own subdirectory, and a second run would then reprocess that mirrored copy too,
            // growing without bound.
            (report.failure != null) shouldBe true
            File(generatedDir, "page.adoc").exists() shouldBe false
        }

        // D19
        (
            "single-file mode writes the .adoc and its image to the same resolved directory even when " +
                "the --output path contains a '..' segment through a symlink"
        ).config(enabled = symlinksSupported) {
            val dir = freshDir("d19")
            val work = File(dir, "work").apply { mkdirs() }
            File(dir, "real").mkdirs()
            val input = File(work, "in.adoc")
            input.writeText("```kstep\n${productScript("D19-001")}\n```\n")

            Files.createSymbolicLink(File(work, "link").toPath(), File(dir, "real").toPath())

            // `link/../out.adoc` normalizes lexically to `work/out.adoc`, but the OS -- which
            // follows the `link` symlink to `dir/real` before applying `..` -- would resolve the
            // SAME string to `dir/out.adoc`. Before the fix, the `.adoc` text was written through
            // the raw (OS-resolved) path while its image was written through the normalized path,
            // splitting one document across two directories with no error.
            val output = File(work, "link/../out.adoc")

            val report = AsciidocProcessor(options).processFile(input, output)

            report.failure shouldBe null
            File(work, "out.adoc").exists() shouldBe true
            // The image's name is derived from the INPUT's basename ("in"), same as D1 -- what
            // this test guards is which DIRECTORY it lands in.
            File(work, "in-1.svg").exists() shouldBe true
            File(dir, "out.adoc").exists() shouldBe false
        }

        // D20
        (
            "a directory symlink INSIDE --output-dir aliasing another output path is caught as a " +
                "cross-file collision, never a silent clobber"
        ).config(enabled = symlinksSupported) {
            val dir = freshDir("d20")
            val inputDir = File(dir, "in").apply { mkdirs() }
            File(inputDir, "a").mkdirs()
            File(inputDir, "a/page.adoc").writeText("```kstep\n${productScript("D20-A")}\n```\n")
            File(inputDir, "link").mkdirs()
            File(inputDir, "link/page.adoc").writeText("```kstep\n${productScript("D20-LINK")}\n```\n")
            val outputDir = File(dir, "out")
            File(outputDir, "a").mkdirs()
            // "link" resolves, INSIDE --output-dir, to the SAME physical directory as "a" -- two
            // different input files ("in/a/page.adoc" and "in/link/page.adoc") would otherwise
            // both resolve to the SAME physical "out/a/page.adoc" with no error at all (Round 5's
            // H1 finding: the old whole-tree collision set was keyed by the LEXICAL target path,
            // which differs for "out/a/page.adoc" vs "out/link/page.adoc" even though both name
            // the same file).
            Files.createSymbolicLink(File(outputDir, "link").toPath(), File(outputDir, "a").absoluteFile.toPath())

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            (report.failure != null) shouldBe true
            (report.failure?.message?.contains("already produced by another file") ?: false) shouldBe true
        }

        // D21
        (
            "an --output-dir whose not-yet-existing path has a symlinked ancestor into --input-dir " +
                "is rejected before Files.createDirectories plants anything inside --input-dir"
        ).config(enabled = symlinksSupported) {
            val dir = freshDir("d21")
            val inputDir = File(dir, "in").apply { mkdirs() }
            File(inputDir, "doc.adoc").writeText("prose only\n")
            val pLink = File(dir, "plink")
            Files.createSymbolicLink(pLink.toPath(), inputDir.toPath())
            // "plink/out" does not exist yet anywhere -- "plink" itself is the symlink.
            val outputDir = File(pLink, "out")

            val report = AsciidocProcessor(options).processTree(inputDir, outputDir)

            (report.failure != null) shouldBe true
            // The old ordering created `outRoot` via `Files.createDirectories` BEFORE the
            // physical overlap re-check ran, which -- because "plink" resolves into
            // `inputDir` -- planted a real "out" directory INSIDE the input tree even though
            // the run was ultimately rejected (Round 5's H2 finding).
            File(inputDir, "out").exists() shouldBe false
        }

        // D22
        (
            "an unreadable subdirectory during the tree walk fails cleanly instead of an uncaught " +
                "UncheckedIOException"
        ).config(enabled = !isRunningAsRoot()) {
            val dir = freshDir("d22")
            val inputDir = File(dir, "in").apply { mkdirs() }
            val locked = File(inputDir, "locked").apply { mkdirs() }
            File(locked, "doc.adoc").writeText("prose only\n")
            val outputDir = File(dir, "out")
            val originalPermissions = Files.getPosixFilePermissions(locked.toPath())

            try {
                Files.setPosixFilePermissions(locked.toPath(), emptySet())
                val report = AsciidocProcessor(options).processTree(inputDir, outputDir)
                (report.failure != null) shouldBe true
                (report.failure?.message?.contains("failed to walk") ?: false) shouldBe true
            } finally {
                Files.setPosixFilePermissions(locked.toPath(), originalPermissions)
            }
        }

        // D23
        "single-file mode with --input equal to --output refuses to overwrite the source script" {
            val dir = freshDir("d23")
            val input = File(dir, "doc.adoc")
            val originalText = "```kstep\n${productScript("D23-001")}\n```\n"
            input.writeText(originalText)

            val report = AsciidocProcessor(options).processFile(input, input)

            (report.failure != null) shouldBe true
            input.readText() shouldBe originalText
        }

        // D24
        "single-file mode rejects a rendered image colliding with the .adoc output's own path" {
            val dir = freshDir("d24")
            val input = File(dir, "in.adoc")
            // An explicit target "out" with the default SVG format renders to "out.svg" -- the
            // OUTPUT path below is deliberately also named "out.svg" (processFile does not care
            // what extension `--output` uses), so the rendered image and the rewritten document
            // text land on the IDENTICAL path. Before this test's fix, single-file mode passed
            // `reservedOutputPaths = null` to `writeRendered`, so this collision was never even
            // checked.
            input.writeText("[kstep,out]\n----\n${productScript("D24-001")}\n----\n")
            val output = File(dir, "out.svg")

            val report = AsciidocProcessor(options).processFile(input, output)

            (report.failure != null) shouldBe true
        }

        // D25 (Review Round 7, R7-1 end-to-end)
        "single-file mode leaves a kstep macro inside a [TIP] admonition byte-identical instead of rendering it" {
            val dir = freshDir("d25")
            val input = File(dir, "in.adoc")
            val originalText = "[TIP]\n====\nkstep::demo.kstep.kts[]\n====\n"
            input.writeText(originalText)
            val output = File(dir, "out.adoc")

            val report = AsciidocProcessor(options).processFile(input, output)

            report.failure shouldBe null
            output.readText() shouldBe originalText
            File(dir, "demo-1.svg").exists() shouldBe false
        }
    })

private fun isRunningAsRoot(): Boolean = System.getProperty("user.name") == "root"
