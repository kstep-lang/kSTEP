package dev.kstep.step21

/**
 * Hand-rolled pass-1 scanner for ISO 10303-21 physical exchange files: `HEADER;` section (the
 * three fixed, unnumbered `FILE_DESCRIPTION`/`FILE_NAME`/`FILE_SCHEMA` statements) plus `DATA;`
 * section (`#N=ENTITY_NAME(args);` statements). Deliberately not built on the ANTLR EXPRESS
 * grammar/lexer used by `dev.kstep.express` — Part-21 is a different, much simpler ISO 10303
 * sub-format (physical file syntax, not an EXPRESS schema), and the vendored EXPRESS lexer's
 * `SimpleStringLiteral` rule is documented (see `WhereRuleExpressionBuilder`'s KDoc) to
 * mis-tokenize the `''`-doubling convention this format also uses — a bug this scanner must
 * not inherit, so string literals are scanned independently below.
 *
 * This pass only validates what is knowable without the full instance table: syntax
 * (semicolons, parens, quotes), section keyword order, entity name recognition, argument
 * arity, and argument *kind* (`STRING` vs. `#N`-reference shape) per [Part21EntityKind].
 * Whether a `#N` reference points at an instance of the *correct target type*, whether it
 * points anywhere at all, and whether the reference graph is acyclic are all deferred to
 * [Part21GraphResolver] (pass 2), which needs the complete instance table to check them.
 */
internal object Part21Tokenizer {
    private const val MAX_SOURCE_LENGTH = 5_000_000
    private const val MAX_INSTANCES = 10_000
    private const val MAX_VALUE_NESTING_DEPTH = 32

    fun parseDocument(source: String): Part21RawDocument {
        if (source.length > MAX_SOURCE_LENGTH) {
            throw Part21LimitExceededException(
                "Part-21 source exceeds the maximum supported length of $MAX_SOURCE_LENGTH characters " +
                    "(was ${source.length})",
            )
        }

        val cursor = Part21Cursor(source)
        cursor.expectLiteral("ISO-10303-21;")
        cursor.expectLiteral("HEADER;")

        val fileDescriptionArgs = parseHeaderStatement(cursor, "FILE_DESCRIPTION", 2)
        val fileNameArgs = parseHeaderStatement(cursor, "FILE_NAME", 7)
        val fileSchemaArgs = parseHeaderStatement(cursor, "FILE_SCHEMA", 1)
        val header = buildHeader(fileDescriptionArgs, fileNameArgs, fileSchemaArgs)

        cursor.expectLiteral("ENDSEC;")
        cursor.expectLiteral("DATA;")

        val instances = mutableListOf<Part21RawInstance>()
        val seenIds = mutableSetOf<Int>()
        cursor.skipWhitespace()
        while (cursor.peek() == '#') {
            val instance = parseInstanceStatement(cursor)
            if (!seenIds.add(instance.id)) {
                throw Part21SyntaxException(
                    "duplicate instance id #${instance.id} at ${cursor.currentPosition()} (already defined earlier in DATA)",
                )
            }
            instances += instance
            if (instances.size > MAX_INSTANCES) {
                throw Part21LimitExceededException(
                    "Part-21 DATA section exceeds the maximum supported instance count of $MAX_INSTANCES",
                )
            }
            cursor.skipWhitespace()
        }

        cursor.expectLiteral("ENDSEC;")
        cursor.expectLiteral("END-ISO-10303-21;")
        cursor.skipWhitespace()
        if (!cursor.isAtEnd()) {
            throw Part21SyntaxException(
                "unexpected trailing content after END-ISO-10303-21; at ${cursor.currentPosition()}",
            )
        }

        return Part21RawDocument(header, instances)
    }

    private fun parseHeaderStatement(
        cursor: Part21Cursor,
        expectedName: String,
        arity: Int,
    ): List<Part21Value> {
        cursor.skipWhitespace()
        val start = cursor.currentPosition()
        val name = parseIdentifier(cursor)
        if (name != expectedName) {
            throw Part21SyntaxException("expected HEADER statement '$expectedName' but found '$name' at $start")
        }
        cursor.expectLiteral("(")
        val args = parseArgList(cursor, 0)
        cursor.expectLiteral(")")
        cursor.expectLiteral(";")
        if (args.size != arity) {
            throw Part21SyntaxException("$expectedName expects $arity argument(s) but found ${args.size}")
        }
        return args
    }

    private fun parseInstanceStatement(cursor: Part21Cursor): Part21RawInstance {
        val sourceLine = cursor.line
        val start = cursor.currentPosition()
        if (cursor.peek() != '#') {
            throw Part21SyntaxException("expected an instance id starting with '#' at $start")
        }
        cursor.advance()
        val id = parseInstanceId(cursor, start)
        cursor.expectLiteral("=")
        cursor.skipWhitespace()
        val rawName = parseIdentifier(cursor)
        val entityName = rawName.uppercase()
        val kind =
            Part21EntityKind.byEntityName[entityName]
                ?: throw Part21SyntaxException(
                    "unknown entity name '$rawName' for instance #$id at line $sourceLine " +
                        "(expected one of ${Part21EntityKind.entries.joinToString { it.entityName }})",
                )
        cursor.expectLiteral("(")
        val args = parseArgList(cursor, 0)
        cursor.expectLiteral(")")
        cursor.expectLiteral(";")

        if (args.size != kind.argKinds.size) {
            throw Part21SyntaxException(
                "entity '${kind.entityName}' (#$id) expects ${kind.argKinds.size} argument(s) but found ${args.size}",
            )
        }
        args.forEachIndexed { index, value ->
            val expectedKind = kind.argKinds[index]
            val actualKind =
                when (value) {
                    is Part21Value.Str -> Part21ArgKind.STRING
                    is Part21Value.Ref -> Part21ArgKind.REFERENCE
                    is Part21Value.ListValue -> null
                }
            if (actualKind != expectedKind) {
                throw Part21SyntaxException(
                    "entity '${kind.entityName}' (#$id) argument ${index + 1} must be $expectedKind but was ${actualKind ?: "a LIST"}",
                )
            }
        }

        return Part21RawInstance(id, kind.entityName, args, sourceLine)
    }

    private fun parseInstanceId(
        cursor: Part21Cursor,
        contextPosition: String,
    ): Int {
        val digits = StringBuilder()
        while (true) {
            val c = cursor.peek() ?: break
            if (c.isDigit()) {
                digits.append(c)
                cursor.advance()
            } else {
                break
            }
        }
        if (digits.isEmpty()) {
            throw Part21SyntaxException("expected digits after '#' at $contextPosition")
        }
        val id =
            digits.toString().toIntOrNull()
                ?: throw Part21SyntaxException("instance id '#$digits' at $contextPosition is not a valid integer")
        if (id <= 0) {
            throw Part21SyntaxException("instance id #$id at $contextPosition must be a positive integer")
        }
        return id
    }

    private fun parseIdentifier(cursor: Part21Cursor): String {
        val start = cursor.currentPosition()
        val sb = StringBuilder()
        while (true) {
            val c = cursor.peek() ?: break
            if (c.isLetterOrDigit() || c == '_') {
                sb.append(c)
                cursor.advance()
            } else {
                break
            }
        }
        if (sb.isEmpty()) {
            throw Part21SyntaxException("expected an identifier at $start")
        }
        return sb.toString()
    }

    private fun parseArgList(
        cursor: Part21Cursor,
        depth: Int,
    ): List<Part21Value> {
        cursor.skipWhitespace()
        val values = mutableListOf<Part21Value>()
        if (cursor.peek() == ')') {
            return values
        }
        while (true) {
            values += parseValue(cursor, depth)
            cursor.skipWhitespace()
            when (cursor.peek()) {
                ',' -> {
                    cursor.advance()
                    cursor.skipWhitespace()
                }
                ')' -> return values
                else -> throw Part21SyntaxException("expected ',' or ')' at ${cursor.currentPosition()}")
            }
        }
    }

    private fun parseValue(
        cursor: Part21Cursor,
        depth: Int,
    ): Part21Value {
        cursor.skipWhitespace()
        return when (cursor.peek()) {
            '\'' -> parseStringLiteral(cursor)
            '#' -> parseReferenceValue(cursor)
            '(' -> parseListValue(cursor, depth)
            null -> throw Part21SyntaxException("unexpected end of input while parsing a value")
            else ->
                throw Part21SyntaxException(
                    "unexpected character '${cursor.peek()}' at ${cursor.currentPosition()} while parsing a value",
                )
        }
    }

    private fun parseListValue(
        cursor: Part21Cursor,
        depth: Int,
    ): Part21Value.ListValue {
        if (depth >= MAX_VALUE_NESTING_DEPTH) {
            throw Part21LimitExceededException(
                "value nesting exceeds $MAX_VALUE_NESTING_DEPTH levels at ${cursor.currentPosition()}",
            )
        }
        cursor.expectLiteral("(")
        val items = parseArgList(cursor, depth + 1)
        cursor.expectLiteral(")")
        return Part21Value.ListValue(items)
    }

    private fun parseReferenceValue(cursor: Part21Cursor): Part21Value.Ref {
        val start = cursor.currentPosition()
        cursor.advance() // consume '#', already confirmed present by the caller's peek()
        return Part21Value.Ref(parseInstanceId(cursor, start))
    }

    // Manual quote-doubling scan ('' inside a string means a literal '), independent of the
    // ANTLR EXPRESS lexer's documented-buggy SimpleStringLiteral rule (see this file's class
    // KDoc). Rejects control characters and non-ASCII content structurally rather than
    // implementing ISO 10303-21's \X\/\X2\/\X4\ escape mechanism (out of scope, see README).
    private fun parseStringLiteral(cursor: Part21Cursor): Part21Value.Str {
        val start = cursor.currentPosition()
        cursor.advance() // consume opening quote, already confirmed present by the caller's peek()
        val sb = StringBuilder()
        while (true) {
            if (cursor.isAtEnd()) {
                throw Part21SyntaxException("unterminated string literal starting at $start")
            }
            val c = cursor.advance()
            if (c == '\'') {
                if (cursor.peek() == '\'') {
                    sb.append('\'')
                    cursor.advance()
                    continue
                }
                return Part21Value.Str(sb.toString())
            }
            if (c.code < 0x20 || c.code > 0x7E) {
                throw Part21EncodingException(
                    "string literal starting at $start contains an unsupported character (code point 0x${
                        c.code.toString(16)
                    }) — only printable ASCII is supported, see README",
                )
            }
            sb.append(c)
        }
    }

    private fun buildHeader(
        fileDescriptionArgs: List<Part21Value>,
        fileNameArgs: List<Part21Value>,
        fileSchemaArgs: List<Part21Value>,
    ): Part21Header =
        Part21Header(
            fileName = asString(fileNameArgs[0], "FILE_NAME.name"),
            timestamp = asString(fileNameArgs[1], "FILE_NAME.time_stamp"),
            schemaIdentifiers = asStringList(fileSchemaArgs[0], "FILE_SCHEMA.schema_identifiers"),
            description = asStringList(fileDescriptionArgs[0], "FILE_DESCRIPTION.description"),
            implementationLevel = asString(fileDescriptionArgs[1], "FILE_DESCRIPTION.implementation_level"),
            author = asStringList(fileNameArgs[2], "FILE_NAME.author"),
            organization = asStringList(fileNameArgs[3], "FILE_NAME.organization"),
            preprocessorVersion = asString(fileNameArgs[4], "FILE_NAME.preprocessor_version"),
            originatingSystem = asString(fileNameArgs[5], "FILE_NAME.originating_system"),
            authorization = asString(fileNameArgs[6], "FILE_NAME.authorization"),
        )

    private fun asString(
        value: Part21Value,
        context: String,
    ): String =
        when (value) {
            is Part21Value.Str -> value.text
            else -> throw Part21SyntaxException("expected a string value for $context but found $value")
        }

    private fun asStringList(
        value: Part21Value,
        context: String,
    ): List<String> =
        when (value) {
            is Part21Value.ListValue -> value.items.map { asString(it, context) }
            else -> throw Part21SyntaxException("expected a list of strings for $context but found $value")
        }
}
