package dev.kstep.desktop.okf

import dev.kstep.cli.okf.Extraction
import dev.kstep.cli.okf.Frontmatter
import dev.kstep.cli.okf.FrontmatterParser
import dev.kstep.cli.okf.MarkdownBlockExtractor
import dev.kstep.desktop.edit.LineIndex

/** Front matter, blocks and line index of one text version. */
class NoteAnalysis(
    val text: String,
    val frontmatter: Frontmatter,
    val extraction: Extraction,
    val lines: LineIndex,
)

/**
 * A one-entry cache of the analysis of the editor text (ADR-0058): the caret handler, the highlighter and the gutter all
 * ask for the same text on every key press, and a 1 MiB note must not be scanned several times per key.
 */
object NoteAnalyzer {
    private var last: NoteAnalysis? = null

    @Synchronized
    fun analyze(text: String): NoteAnalysis {
        last?.let { if (it.text === text || it.text == text) return it }
        val fm = FrontmatterParser.parse(text)
        val analysis = NoteAnalysis(text, fm, MarkdownBlockExtractor.extract(text, fm.bodyStartLine), LineIndex(text))
        last = analysis
        return analysis
    }
}
