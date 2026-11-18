package dev.kstep.desktop.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.kstep.desktop.edit.KotlinTokenizer
import dev.kstep.desktop.okf.HlKind
import dev.kstep.desktop.okf.KnowledgeHighlightPlan
import dev.kstep.desktop.okf.NoteAnalyzer

/**
 * The colouring of a Markdown note in a knowledge workspace (ADR-0058, D3): fence lines in the secondary colour, the
 * Kotlin tokens of `kstep` blocks as in a script, other fenced code dimmed; the prose stays plain. Identity offset
 * mapping (selection, caret and IME are not touched); cached by text, and the analysis itself is shared through
 * [NoteAnalyzer].
 */
internal class KnowledgeSyntaxTransformation(
    private val colors: EditorColors,
) : VisualTransformation {
    private var cachedText: String? = null
    private var cachedResult: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val plain = text.text
        val cached = cachedResult
        val styled =
            if (cached != null && plain == cachedText) {
                cached
            } else {
                val plan = KnowledgeHighlightPlan.of(NoteAnalyzer.analyze(plain).extraction)
                buildAnnotatedString {
                    append(plain)
                    for (range in plan) {
                        when (range.kind) {
                            HlKind.FENCE_LINE -> addStyle(SpanStyle(color = colors.gutter), range.start, range.end)
                            HlKind.OTHER_FENCE -> addStyle(SpanStyle(color = colors.gutter), range.start, range.end)
                            HlKind.KSTEP_CODE ->
                                for (token in KotlinTokenizer.tokenize(plain.substring(range.start, range.end))) {
                                    addStyle(
                                        colors.style(token.kind),
                                        range.offsetBase + token.start,
                                        range.offsetBase + token.end,
                                    )
                                }
                        }
                    }
                }.also {
                    cachedText = plain
                    cachedResult = it
                }
            }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
