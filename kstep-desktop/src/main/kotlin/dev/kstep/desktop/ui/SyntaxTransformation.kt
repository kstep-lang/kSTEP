package dev.kstep.desktop.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.kstep.desktop.edit.KotlinTokenizer

/**
 * Syntax colouring as a visual transformation with an identity offset mapping (so selection, caret and IME are not
 * touched). The field calls [filter] on every recomposition, so the result is cached by text.
 */
internal class SyntaxTransformation(
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
                buildAnnotatedString {
                    append(plain)
                    for (token in KotlinTokenizer.tokenize(
                        plain,
                    )) {
                        addStyle(colors.style(token.kind), token.start, token.end)
                    }
                }.also {
                    cachedText = plain
                    cachedResult = it
                }
            }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
