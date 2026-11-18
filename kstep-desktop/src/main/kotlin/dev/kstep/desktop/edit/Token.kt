package dev.kstep.desktop.edit

/** What a highlighted span is (ADR-0052). */
enum class TokenKind { KEYWORD, STRING, TEMPLATE, NUMBER, COMMENT, ANNOTATION, DSL }

/** A half-open span `[start, end)` of UTF-16 units of the editor text. */
data class Token(
    val start: Int,
    val end: Int,
    val kind: TokenKind,
)
