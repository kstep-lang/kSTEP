package dev.kstep.express.codegen

/**
 * EXPRESS `snake_case` identifiers to idiomatic Kotlin `PascalCase`/`camelCase`.
 *
 * Public (not `internal`) so it is directly unit-testable from `kstep-tests`, a separate
 * Gradle module/compilation unit where Kotlin's `internal` visibility would not apply.
 */
object NamingConventions {
    private val KOTLIN_HARD_KEYWORDS =
        setOf(
            "as",
            "break",
            "class",
            "continue",
            "do",
            "else",
            "false",
            "for",
            "fun",
            "if",
            "in",
            "interface",
            "is",
            "null",
            "object",
            "package",
            "return",
            "super",
            "this",
            "throw",
            "true",
            "try",
            "typealias",
            "typeof",
            "val",
            "var",
            "when",
            "while",
        )

    fun toClassName(expressIdentifier: String): String =
        segments(expressIdentifier).joinToString("") {
            it.replaceFirstChar(Char::uppercaseChar)
        }

    fun toPropertyName(expressIdentifier: String): String {
        val parts = segments(expressIdentifier)
        if (parts.isEmpty()) return expressIdentifier
        val first = parts.first().replaceFirstChar(Char::lowercaseChar)
        val rest = parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return first + rest
    }

    fun escapeIfKotlinKeyword(kotlinIdentifier: String): String =
        if (kotlinIdentifier in KOTLIN_HARD_KEYWORDS) "`$kotlinIdentifier`" else kotlinIdentifier

    private fun segments(expressIdentifier: String): List<String> =
        expressIdentifier.split('_').filter { it.isNotEmpty() }.map { it.lowercase() }
}
