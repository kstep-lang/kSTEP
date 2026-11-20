package dev.kstep.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import dev.kstep.desktop.edit.TokenKind

/** Colours of the script editor (ADR-0052): readable in light and dark, comments dimmed and italic, nothing bold. */
internal class EditorColors(
    val text: Color,
    val gutter: Color,
    val keyword: Color,
    val string: Color,
    val template: Color,
    val number: Color,
    val annotation: Color,
    val dsl: Color,
    /** Find bar (ADR-0067): background of the bar, of every match and of the current match; tint of the error line. */
    val bar: Color,
    val matchBackground: Color,
    val currentMatchBackground: Color,
    val errorLineBackground: Color,
) {
    private val comment = Color(0xFF8A8A8A)

    fun style(kind: TokenKind): SpanStyle =
        when (kind) {
            TokenKind.KEYWORD -> SpanStyle(color = keyword)
            TokenKind.STRING -> SpanStyle(color = string)
            TokenKind.TEMPLATE -> SpanStyle(color = template)
            TokenKind.NUMBER -> SpanStyle(color = number)
            TokenKind.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
            TokenKind.ANNOTATION -> SpanStyle(color = annotation)
            TokenKind.DSL -> SpanStyle(color = dsl)
        }

    companion object {
        val LIGHT =
            EditorColors(
                text = Color(0xFF1F1F1F),
                gutter = Color(0xFF8A8A8A).copy(alpha = 0.7f),
                keyword = Color(0xFF0033B3),
                string = Color(0xFF067D17),
                template = Color(0xFF9E5400),
                number = Color(0xFF1750EB),
                annotation = Color(0xFF9E880D),
                dsl = Color(0xFF871094),
                bar = Color(0xFFEFEFEF),
                matchBackground = Color(0xFFFFE9A0),
                currentMatchBackground = Color(0xFFFFB74D),
                errorLineBackground = Color(0xFFC62828).copy(alpha = 0.14f),
            )
        val DARK =
            EditorColors(
                text = Color(0xFFE0E0E0),
                gutter = Color(0xFF8A8A8A).copy(alpha = 0.7f),
                keyword = Color(0xFFCF8E6D),
                string = Color(0xFF6AAB73),
                template = Color(0xFFCC7832),
                number = Color(0xFF2AACB8),
                annotation = Color(0xFFBBB529),
                dsl = Color(0xFFC77DBB),
                bar = Color(0xFF2B2B2B),
                matchBackground = Color(0xFF51451A),
                currentMatchBackground = Color(0xFF8A6A1A),
                errorLineBackground = Color(0xFFFF6B6B).copy(alpha = 0.22f),
            )
    }
}

internal val MARK_ERROR = Color(0xFFC62828)
internal val MARK_WARNING = Color(0xFFF9A825)

/** Accent of the selection and hover tint in the viewer (ADR-0053): a blue that stays visible on light and dark parts. */
internal val ACCENT_LIGHT = Color(0xFF1E66F5)
internal val ACCENT_DARK = Color(0xFF5B9BFF)
