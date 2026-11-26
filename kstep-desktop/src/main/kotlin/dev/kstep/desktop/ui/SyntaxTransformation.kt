package dev.kstep.desktop.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.kstep.desktop.edit.IncrementalTokenizer
import dev.kstep.desktop.edit.Token

/**
 * Syntax colouring as a visual transformation with an identity offset mapping (so selection, caret and IME are not
 * touched). The field calls [filter] on every recomposition, so the result is cached by text. A changed text is
 * tokenized incrementally from the line of the change (ADR-0088); the tokens always equal a full scan.
 */
internal class SyntaxTransformation(
    private val colors: EditorColors,
) : VisualTransformation {
    private var cachedText: String? = null
    private var cachedResult: AnnotatedString? = null
    private var cachedTokens: List<Token> = emptyList()
    private val tokenizer = IncrementalTokenizer()

    /** The tokens of [text] if the field has coloured exactly that text last, else `null` (no second scan, ADR-0085). */
    fun tokensFor(text: String): List<Token>? = if (text == cachedText && cachedResult != null) cachedTokens else null

    override fun filter(text: AnnotatedString): TransformedText {
        val plain = text.text
        val cached = cachedResult
        val styled =
            if (cached != null && plain == cachedText) {
                cached
            } else {
                val tokens = tokenizer.update(plain)
                buildAnnotatedString {
                    append(plain)
                    for (token in tokens) {
                        addStyle(colors.style(token.kind), token.start, token.end)
                    }
                }.also {
                    cachedTokens = tokens
                    cachedText = plain
                    cachedResult = it
                }
            }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
