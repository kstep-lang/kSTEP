package dev.kstep.step21

/**
 * Single serialization path for a [Part21Header] + [Part21EntityInstance] list into ISO
 * 10303-21 physical-file text. Both [Part21Writer.write] (via [Part21Writer.emit]) and
 * [Part21Document.render] go through here — see ADR-0009 for why there must be exactly one
 * renderer rather than two independently-maintained ones.
 *
 * Validates **every** value it writes, not only the ones a typed `kstep-core` builder produced:
 * a [Part21Value] reaching this renderer may originate from a foreign, TOLERANT-mode-parsed
 * document (see [Part21ReadMode.TOLERANT]) or from a hand-constructed [Part21EntityInstance] a
 * caller built directly — either way, [Part21EncodingException] rejects anything that could
 * escape its argument-list position (an embedded `\`/control character in a string, a numeric
 * lexeme or identifier containing `);`, etc.) rather than writing it verbatim. See ADR-0009's
 * Security section, "injection across the export boundary".
 */
internal object Part21Renderer {
    private val NUMBER_LEXEME =
        Regex("""^[+-]?\d+(\.\d*([Ee][+-]?\d+)?)?$""")
    private val IDENTIFIER =
        Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

    // Caps how much of an externally-controlled value (a foreign/TOLERANT-parsed string, number
    // lexeme, or identifier — up to MAX_SOURCE_LENGTH characters) is echoed into an exception
    // message. Without this, a caller that logs the exception (e.g. Ap242ShapeExporter's
    // callers) would have an unbounded amount of parsed input land in a single log line —
    // see ADR-0009's Security section, "Logging".
    private const val MAX_ECHOED_VALUE_LENGTH = 80

    fun render(
        header: Part21Header,
        instances: List<Part21EntityInstance>,
    ): String =
        buildString {
            append("ISO-10303-21;\n")
            append("HEADER;\n")
            appendHeaderStatements(this, header)
            append("ENDSEC;\n")
            append("DATA;\n")
            for (instance in instances) {
                appendInstanceStatement(this, instance)
            }
            append("ENDSEC;\n")
            append("END-ISO-10303-21;\n")
        }

    private fun appendInstanceStatement(
        sb: StringBuilder,
        instance: Part21EntityInstance,
    ) {
        when (instance) {
            is Part21SimpleInstance -> {
                assertValidId(instance.id, "instance id")
                assertValidIdentifier(instance.entityName, "entity name")
                val args = instance.args.joinToString(",") { renderValue(it) }
                sb.append("#${instance.id}=${instance.entityName}($args);\n")
            }
            is Part21ComplexInstance -> {
                assertValidId(instance.id, "instance id")
                val parts =
                    instance.parts.joinToString(" ") { part ->
                        assertValidIdentifier(part.entityName, "complex-instance part name")
                        "${part.entityName}(${part.args.joinToString(",") { renderValue(it) }})"
                    }
                sb.append("#${instance.id}=($parts);\n")
            }
        }
    }

    private fun renderValue(value: Part21Value): String =
        when (value) {
            is Part21Value.Str -> quoteString(value.text)
            is Part21Value.Ref -> {
                assertValidId(value.id, "reference")
                "#${value.id}"
            }
            is Part21Value.ListValue -> "(" + value.items.joinToString(",") { renderValue(it) } + ")"
            Part21Value.Unset -> "\$"
            Part21Value.Derived -> "*"
            is Part21Value.Num -> {
                assertValidNumber(value.lexeme)
                value.lexeme
            }
            is Part21Value.Enumeration -> {
                assertValidIdentifier(value.name, "enumeration")
                ".${value.name}."
            }
            is Part21Value.Typed -> {
                assertValidIdentifier(value.keyword, "typed-parameter keyword")
                "${value.keyword}(" + value.args.joinToString(",") { renderValue(it) } + ")"
            }
        }

    private fun assertValidNumber(lexeme: String) {
        if (!NUMBER_LEXEME.matches(lexeme)) {
            throw Part21EncodingException(
                "numeric value '${truncateForMessage(lexeme)}' does not match the ISO 10303-21 number grammar " +
                    "([+-]?DIGIT+('.'DIGIT*(['Ee']['+-']?DIGIT+)?)?) and cannot be written",
            )
        }
    }

    private fun assertValidIdentifier(
        name: String,
        context: String,
    ) {
        if (!IDENTIFIER.matches(name)) {
            throw Part21EncodingException(
                "$context '${truncateForMessage(name)}' is not a valid Part-21 identifier " +
                    "([A-Za-z_][A-Za-z0-9_]*) and cannot be written",
            )
        }
    }

    // Every instance id and #N reference this renderer writes must satisfy the same `id > 0`
    // invariant Part21Tokenizer.parseInstanceId enforces on read — otherwise a caller-supplied or
    // offset-shifted id (see Part21Document.renumbered) that has gone negative or non-positive
    // would be written verbatim as e.g. "#-5", producing a file kSTEP's own reader (and any
    // conformant Part-21 reader) rejects as a syntax error, rather than failing here where the
    // cause is still attributable. See ADR-0009's Security section, "Injection across the export
    // boundary" / instance-id integrity.
    private fun assertValidId(
        id: Int,
        context: String,
    ) {
        if (id <= 0) {
            throw Part21EncodingException(
                "$context #$id is not a positive integer and cannot be written " +
                    "(Part-21 instance ids must satisfy id > 0, matching Part21Tokenizer.parseInstanceId)",
            )
        }
    }

    // Callers embed the result inside their own surrounding quotes (e.g. "'${truncateForMessage(x)}'"),
    // so this never adds a closing quote of its own — only an unquoted, human-readable suffix.
    private fun truncateForMessage(value: String): String =
        if (value.length <= MAX_ECHOED_VALUE_LENGTH) {
            value
        } else {
            "${value.take(MAX_ECHOED_VALUE_LENGTH)}… [truncated, ${value.length} characters total]"
        }

    private fun appendHeaderStatements(
        sb: StringBuilder,
        header: Part21Header,
    ) {
        val description = stringListLiteral(header.description)
        val implementationLevel = quoteString(header.implementationLevel)
        sb.append("FILE_DESCRIPTION($description,$implementationLevel);\n")

        val fileName = quoteString(header.fileName)
        val timestamp = quoteString(header.timestamp)
        val author = stringListLiteral(header.author)
        val organization = stringListLiteral(header.organization)
        val preprocessorVersion = quoteString(header.preprocessorVersion)
        val originatingSystem = quoteString(header.originatingSystem)
        val authorization = quoteString(header.authorization)
        sb.append(
            "FILE_NAME($fileName,$timestamp,$author,$organization,$preprocessorVersion," +
                "$originatingSystem,$authorization);\n",
        )

        val schemaIdentifiers = stringListLiteral(header.schemaIdentifiers)
        sb.append("FILE_SCHEMA($schemaIdentifiers);\n")
    }

    private fun stringListLiteral(items: List<String>): String {
        val quoted = items.joinToString(",") { quoteString(it) }
        return "($quoted)"
    }

    // Single-quote doubling ('O''Brien' round-trips O'Brien), matching the reader's manual
    // scan (see Part21Tokenizer's KDoc for why this is hand-written instead of reusing the
    // ANTLR EXPRESS lexer's documented-buggy SimpleStringLiteral rule).
    private fun quoteString(value: String): String {
        assertEncodable(value)
        return "'" + value.replace("'", "''") + "'"
    }

    // Reverse solidus (\, 0x5C) is deliberately rejected even though it is within the
    // printable-ASCII range this writer otherwise allows: ISO 10303-21 reserves an unescaped
    // '\' to introduce a \X\/\X2\/\X4\ non-ASCII escape sequence, which V1 does not implement
    // (see README). Letting a raw '\' through unescaped would silently change meaning for any
    // conformant external Part-21 reader that DOES implement the escape mechanism — e.g. a
    // caller-supplied value containing "\X2\04D0\X0\" would be re-interpreted by such a reader
    // as a UTF-16 escape rather than the literal ASCII text kSTEP validated and echoed back,
    // a content-forgery gap across the export boundary. Rejecting it here keeps the writer's
    // behavior honest with the README's "anything else raises Part21EncodingException rather
    // than being silently mis-encoded" promise, and forces a caller who genuinely needs a
    // literal backslash to pick a different representation rather than have kSTEP guess.
    private fun assertEncodable(value: String) {
        for (c in value) {
            if (c == '\\') {
                throw Part21EncodingException(
                    "value '${truncateForMessage(value)}' contains a reverse solidus ('\\', code point 0x5c) — " +
                        "V1 does not implement the ISO 10303-21 \\X\\/\\X2\\/\\X4\\ escape mechanism, so an " +
                        "unescaped backslash cannot be written without risking misinterpretation by conformant " +
                        "external Part-21 readers, see README",
                )
            }
            if (c.code < 0x20 || c.code > 0x7E) {
                throw Part21EncodingException(
                    "value '${truncateForMessage(value)}' contains an unsupported character (code point 0x${
                        c.code.toString(16)
                    }) — only printable ASCII is supported, see README",
                )
            }
        }
    }
}
