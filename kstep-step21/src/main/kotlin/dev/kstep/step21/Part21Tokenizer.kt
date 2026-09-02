package dev.kstep.step21

/**
 * Hand-rolled pass-1 scanner for ISO 10303-21 physical exchange files: `HEADER;` section (the
 * three fixed, unnumbered `FILE_DESCRIPTION`/`FILE_NAME`/`FILE_SCHEMA` statements) plus `DATA;`
 * section (`#N=ENTITY_NAME(args);` or, under [Part21ReadMode.TOLERANT], `#N=(PART(args)...);`
 * complex-instance statements). Deliberately not built on the ANTLR EXPRESS grammar/lexer used
 * by `dev.kstep.express` — Part-21 is a different, much simpler ISO 10303 sub-format (physical
 * file syntax, not an EXPRESS schema), and the vendored EXPRESS lexer's `SimpleStringLiteral`
 * rule is documented (see `WhereRuleExpressionBuilder`'s KDoc) to mis-tokenize the
 * `''`-doubling convention this format also uses — a bug this scanner must not inherit, so
 * string literals are scanned independently below.
 *
 * The full ISO 10303-21 value grammar (string/reference/list/`$`/`*`/enumeration/numeric/typed
 * parameter) is always parsed, in both [Part21ReadMode]s — see [parseValue]. What differs by
 * mode is only: (a) whether an unrecognized entity name is rejected ([Part21ReadMode.STRICT])
 * or kept as an opaque instance ([Part21ReadMode.TOLERANT]), and (b) whether a complex instance
 * is permitted at all. A *known* [Part21EntityKind]'s argument count/kind is checked identically
 * in both modes via [checkArgShape] — see [Part21ReadMode]'s KDoc for why.
 *
 * This pass only validates what is knowable without the full instance table: syntax
 * (semicolons, parens, quotes), section keyword order, entity name recognition (for known
 * entities), argument arity, and argument *kind* (`STRING` vs. `#N`-reference shape, for known
 * entities) per [Part21EntityKind]. Whether a `#N` reference points at an instance of the
 * *correct target type*, whether it points anywhere at all, and whether the reference graph is
 * acyclic are all deferred to [Part21GraphResolver] (pass 2), which needs the complete instance
 * table to check them.
 */
internal object Part21Tokenizer {
    private const val MAX_SOURCE_LENGTH = 5_000_000
    private const val MAX_INSTANCES = 10_000
    private const val MAX_VALUE_NESTING_DEPTH = 32

    /**
     * Hard cap on any single identifier (entity name, HEADER statement name, complex-instance
     * part name, enumeration literal, typed-parameter keyword) parsed by [parseIdentifier]. A
     * real Part-21 identifier is well under 100 characters (the longest EXPRESS entity/type names
     * in practice run to a few dozen characters); 128 is generous headroom, not a realistic limit.
     * Without this, [parseIdentifier] would happily accumulate an identifier as long as
     * [MAX_SOURCE_LENGTH] allows, and a caller that logs a resulting "unknown entity name"/HEADER-
     * mismatch [Part21SyntaxException] (see [parseInstanceStatement], [parseHeaderStatement])
     * would have that entire multi-megabyte string land in one log line — see
     * [truncateForMessage]'s KDoc and ADR-0009's Security section, "Logging".
     */
    private const val MAX_IDENTIFIER_LENGTH = 128

    /**
     * Caps how much of a parsed identifier is echoed into a [Part21SyntaxException] message.
     * Mirrors [Part21Renderer]'s `MAX_ECHOED_VALUE_LENGTH`/`truncateForMessage` (same value, same
     * purpose) so the read boundary gives the same log-amplification guarantee the write boundary
     * already does, rather than relying solely on [MAX_IDENTIFIER_LENGTH] to keep messages short.
     */
    private const val MAX_ECHOED_VALUE_LENGTH = 80

    /** Measured maximum in real OCCT AP242 output: 4 (unit/context complex instances). 64 is generous headroom, not a realistic limit. */
    private const val MAX_COMPLEX_INSTANCE_PARTS = 64

    fun parseDocument(
        source: String,
        mode: Part21ReadMode,
    ): Part21RawDocument {
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

        val instances = mutableListOf<Part21EntityInstance>()
        val seenIds = mutableSetOf<Int>()
        cursor.skipWhitespace()
        while (cursor.peek() == '#') {
            val instance = parseInstanceStatement(cursor, mode)
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
            throw Part21SyntaxException(
                "expected HEADER statement '$expectedName' but found '${truncateForMessage(name)}' at $start",
            )
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

    // Always parses the FULL grammar first (simple-vs-complex shape, then, for a simple
    // instance, arity/arg-shape for a *known* entity kind) and only THEN applies the
    // mode-dependent gates (complex instance permitted? unknown entity name permitted?) — so a
    // STRICT-mode rejection is a clear, specific "not permitted in STRICT mode" message, not an
    // incidental grammar failure, and a TOLERANT-mode known-entity malformation is rejected
    // exactly as strictly as in STRICT mode (see Part21ReadMode's KDoc).
    private fun parseInstanceStatement(
        cursor: Part21Cursor,
        mode: Part21ReadMode,
    ): Part21EntityInstance {
        val sourceLine = cursor.line
        val start = cursor.currentPosition()
        if (cursor.peek() != '#') {
            throw Part21SyntaxException("expected an instance id starting with '#' at $start")
        }
        cursor.advance()
        val id = parseInstanceId(cursor, start)
        cursor.expectLiteral("=")
        cursor.skipWhitespace()

        if (cursor.peek() == '(') {
            val complex = parseComplexInstance(cursor, id, sourceLine)
            cursor.expectLiteral(";")
            if (mode == Part21ReadMode.STRICT) {
                throw Part21SyntaxException(
                    "complex instance #$id at line $sourceLine is not permitted in STRICT mode " +
                        "(see Part21ReadMode.TOLERANT)",
                )
            }
            return complex
        }

        val rawName = parseIdentifier(cursor)
        val entityName = rawName.uppercase()
        val kind = Part21EntityKind.byEntityName[entityName]
        cursor.expectLiteral("(")
        val args = parseArgList(cursor, 0)
        cursor.expectLiteral(")")
        cursor.expectLiteral(";")

        if (kind != null) {
            if (args.size != kind.args.size) {
                throw Part21SyntaxException(
                    "entity '${kind.entityName}' (#$id) expects ${kind.args.size} argument(s) but found ${args.size}",
                )
            }
            args.forEachIndexed { index, value -> checkArgShape(kind, id, index, value) }
        } else if (mode == Part21ReadMode.STRICT) {
            throw Part21SyntaxException(
                "unknown entity name '${truncateForMessage(rawName)}' for instance #$id at line $sourceLine " +
                    "(expected one of ${Part21EntityKind.entries.joinToString { it.entityName }})",
            )
        }

        return Part21SimpleInstance(id, entityName, args, sourceLine)
    }

    private fun parseComplexInstance(
        cursor: Part21Cursor,
        id: Int,
        sourceLine: Int,
    ): Part21ComplexInstance {
        val start = cursor.currentPosition()
        cursor.expectLiteral("(")
        val parts = mutableListOf<Part21InstancePart>()
        cursor.skipWhitespace()
        while (cursor.peek() != ')') {
            val partName = parseIdentifier(cursor).uppercase()
            cursor.expectLiteral("(")
            val args = parseArgList(cursor, 0)
            cursor.expectLiteral(")")
            parts += Part21InstancePart(partName, args)
            if (parts.size > MAX_COMPLEX_INSTANCE_PARTS) {
                throw Part21LimitExceededException(
                    "complex instance #$id exceeds the maximum supported part count of $MAX_COMPLEX_INSTANCE_PARTS",
                )
            }
            cursor.skipWhitespace()
        }
        cursor.expectLiteral(")")
        if (parts.isEmpty()) {
            throw Part21SyntaxException("complex instance #$id at $start has no parts")
        }
        return Part21ComplexInstance(id, parts, sourceLine)
    }

    // Validates one already-parsed argument [value] at position [index] against the entity
    // kind's declared shape at that position: kind (STRING/REFERENCE/STRING_LIST/REFERENCE_LIST)
    // and $-eligibility (optional). REFERENCE_LIST/STRING_LIST element-kind checking happens
    // here too (a LIST that mixes strings and references, or a LIST at a REFERENCE position
    // containing a bare string, is rejected at this pass); which *target entity type* a
    // REFERENCE/REFERENCE_LIST points at is a pass-2 concern (Part21GraphResolver), since it
    // needs the complete instance table to check.
    //
    // Applied to every KNOWN entity kind regardless of Part21ReadMode (see this object's KDoc) —
    // a Num/Enumeration/Derived/Typed value at a STRING/REFERENCE position never matches any
    // Part21ArgKind branch below, so it is rejected identically in both modes.
    private fun checkArgShape(
        kind: Part21EntityKind,
        id: Int,
        index: Int,
        value: Part21Value,
    ) {
        val spec = kind.args[index]
        if (value is Part21Value.Unset) {
            if (!spec.optional) {
                throw Part21SyntaxException(
                    "entity '${kind.entityName}' (#$id) argument ${index + 1} is not OPTIONAL and cannot be '\$'",
                )
            }
            return
        }
        val matches =
            when (spec.kind) {
                Part21ArgKind.STRING -> value is Part21Value.Str
                Part21ArgKind.REFERENCE -> value is Part21Value.Ref
                Part21ArgKind.STRING_LIST ->
                    value is Part21Value.ListValue && value.items.all { it is Part21Value.Str }
                Part21ArgKind.REFERENCE_LIST ->
                    value is Part21Value.ListValue && value.items.all { it is Part21Value.Ref }
            }
        if (!matches) {
            throw Part21SyntaxException(
                "entity '${kind.entityName}' (#$id) argument ${index + 1} must be ${spec.kind} but was " +
                    "${describeShape(value)}",
            )
        }
    }

    private fun describeShape(value: Part21Value): String =
        when (value) {
            is Part21Value.Str -> "STRING"
            is Part21Value.Ref -> "REFERENCE"
            is Part21Value.ListValue -> "a LIST (mixed or wrong-element-kind)"
            is Part21Value.Unset -> "'\$'"
            is Part21Value.Derived -> "'*'"
            is Part21Value.Num -> "a NUMBER"
            is Part21Value.Enumeration -> "an ENUMERATION"
            is Part21Value.Typed -> "a TYPED PARAMETER"
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
                ?: throw Part21SyntaxException(
                    "instance id '#${truncateForMessage(digits.toString())}' at $contextPosition is not a valid " +
                        "integer",
                )
        if (id <= 0) {
            throw Part21SyntaxException("instance id #$id at $contextPosition must be a positive integer")
        }
        return id
    }

    // Mirrors Part21Renderer's IDENTIFIER regex ([A-Za-z_][A-Za-z0-9_]*) EXACTLY, character
    // class included — not just the leading-character restriction — so an identifier this reader
    // accepts (entity name, HEADER statement name, complex-instance part name, enumeration
    // literal, typed-parameter keyword) is always re-writable by Part21Renderer. Deliberately
    // ASCII-only ('A'..'Z'/'a'..'z'/'0'..'9', not Char.isLetter()/isLetterOrDigit()): those two
    // stdlib predicates are Unicode-aware, so a first cut at this mirroring (checking only the
    // leading-digit case, per Part21TolerantReaderTest's "9FOO" regression test) still let e.g. a
    // Unicode letter like 'Ü' or a non-ASCII decimal digit through — Part21Renderer's regex has no
    // Unicode-letter/digit branch, so those still parsed here (TOLERANT-mode opaque or STRICT
    // "unknown entity name") but were rejected at render time, reproducing the exact same
    // read/write asymmetry the leading-digit fix closed, just for a different character class.
    // With this fix, any non-ASCII character stops identifier accumulation immediately, so parsing
    // fails at the read boundary (an unexpected character where '(' / '.' / etc. was expected)
    // instead of surfacing much later inside Part21Document.render() — see
    // Part21TolerantReaderTest's regression tests for both the leading-digit and non-ASCII cases.
    private fun parseIdentifier(cursor: Part21Cursor): String {
        val start = cursor.currentPosition()
        val sb = StringBuilder()
        val first = cursor.peek()
        if (first != null && (first in 'A'..'Z' || first in 'a'..'z' || first == '_')) {
            sb.append(first)
            cursor.advance()
            while (true) {
                val c = cursor.peek() ?: break
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_') {
                    sb.append(c)
                    cursor.advance()
                } else {
                    break
                }
                if (sb.length > MAX_IDENTIFIER_LENGTH) {
                    throw Part21LimitExceededException(
                        "identifier starting at $start exceeds the maximum supported length of " +
                            "$MAX_IDENTIFIER_LENGTH characters",
                    )
                }
            }
        }
        if (sb.isEmpty()) {
            throw Part21SyntaxException("expected an identifier at $start")
        }
        return sb.toString()
    }

    // Callers embed the result inside their own surrounding quotes (e.g.
    // "'${truncateForMessage(name)}'"), so this never adds a closing quote of its own — only an
    // unquoted, human-readable suffix. Mirrors Part21Renderer.truncateForMessage (same cap, same
    // purpose) so the read boundary gives the same log-amplification guarantee the write boundary
    // already does — see MAX_ECHOED_VALUE_LENGTH's KDoc.
    private fun truncateForMessage(value: String): String =
        if (value.length <= MAX_ECHOED_VALUE_LENGTH) {
            value
        } else {
            "${value.take(MAX_ECHOED_VALUE_LENGTH)}… [truncated, ${value.length} characters total]"
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
        val c = cursor.peek()
        return when {
            c == '\'' -> parseStringLiteral(cursor)
            c == '#' -> parseReferenceValue(cursor)
            c == '(' -> parseListValue(cursor, depth)
            c == '$' -> {
                cursor.advance()
                Part21Value.Unset
            }
            c == '*' -> {
                cursor.advance()
                Part21Value.Derived
            }
            c == '.' -> parseEnumeration(cursor)
            c == '+' || c == '-' || (c != null && c.isDigit()) -> parseNumber(cursor)
            c != null && (c.isLetter() || c == '_') -> parseTypedParameter(cursor, depth)
            c == null -> throw Part21SyntaxException("unexpected end of input while parsing a value")
            else -> throw Part21SyntaxException(
                "unexpected character '$c' at ${cursor.currentPosition()} while parsing a value",
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

    // `.` IDENT `.` -- the leading '.' is already confirmed present by the caller's peek(), so it
    // is consumed directly rather than via expectLiteral (mirrors parseReferenceValue's '#').
    private fun parseEnumeration(cursor: Part21Cursor): Part21Value.Enumeration {
        cursor.advance()
        val name = parseIdentifier(cursor)
        cursor.expectLiteral(".")
        return Part21Value.Enumeration(name)
    }

    // [+-]? DIGIT+ ( '.' DIGIT* ( [Ee] [+-]? DIGIT+ )? )? -- held verbatim as [Part21Value.Num]'s
    // lexeme, never parsed into a Double/Int (see that type's KDoc for why).
    private fun parseNumber(cursor: Part21Cursor): Part21Value.Num {
        val start = cursor.currentPosition()
        val sb = StringBuilder()
        if (cursor.peek() == '+' || cursor.peek() == '-') {
            sb.append(cursor.advance())
        }
        var hasIntDigits = false
        while (cursor.peek()?.isDigit() == true) {
            sb.append(cursor.advance())
            hasIntDigits = true
        }
        if (!hasIntDigits) {
            throw Part21SyntaxException("expected digits in numeric literal at $start")
        }
        if (cursor.peek() == '.') {
            sb.append(cursor.advance())
            while (cursor.peek()?.isDigit() == true) {
                sb.append(cursor.advance())
            }
            if (cursor.peek() == 'E' || cursor.peek() == 'e') {
                sb.append(cursor.advance())
                if (cursor.peek() == '+' || cursor.peek() == '-') {
                    sb.append(cursor.advance())
                }
                var hasExpDigits = false
                while (cursor.peek()?.isDigit() == true) {
                    sb.append(cursor.advance())
                    hasExpDigits = true
                }
                if (!hasExpDigits) {
                    throw Part21SyntaxException("expected digits in exponent of numeric literal at $start")
                }
            }
        }
        return Part21Value.Num(sb.toString())
    }

    // IDENT '(' argList ')' -- e.g. LENGTH_MEASURE(1.E-07), NAMED_UNIT(*). Depth-checked the same
    // way parseListValue is: a Typed parameter nests just like a list, and MAX_VALUE_NESTING_DEPTH
    // must bound both.
    private fun parseTypedParameter(
        cursor: Part21Cursor,
        depth: Int,
    ): Part21Value.Typed {
        if (depth >= MAX_VALUE_NESTING_DEPTH) {
            throw Part21LimitExceededException(
                "value nesting exceeds $MAX_VALUE_NESTING_DEPTH levels at ${cursor.currentPosition()}",
            )
        }
        val keyword = parseIdentifier(cursor).uppercase()
        cursor.expectLiteral("(")
        val args = parseArgList(cursor, depth + 1)
        cursor.expectLiteral(")")
        return Part21Value.Typed(keyword, args)
    }

    // Manual quote-doubling scan ('' inside a string means a literal '), independent of the
    // ANTLR EXPRESS lexer's documented-buggy SimpleStringLiteral rule (see this file's class
    // KDoc). Rejects control characters, non-ASCII content, and an unescaped reverse solidus
    // structurally rather than implementing ISO 10303-21's \X\/\X2\/\X4\ escape mechanism (out
    // of scope, see README) — mirrors Part21Renderer.assertEncodable's reverse-solidus rejection
    // exactly, so a string this reader accepts is always re-writable by Part21Renderer, and a
    // \X2\.../\X0\ escape sequence in foreign input is never silently mis-read as its literal
    // ASCII text (see that function's KDoc for the injection-across-the-boundary rationale).
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
            if (c == '\\') {
                throw Part21EncodingException(
                    "string literal starting at $start contains a reverse solidus ('\\', code point 0x5c) — " +
                        "V1 does not implement the ISO 10303-21 \\X\\/\\X2\\/\\X4\\ escape mechanism, so an " +
                        "unescaped backslash cannot be safely interpreted as literal text (it could be the start " +
                        "of a non-ASCII escape sequence) and would not be re-writable by Part21Renderer either, " +
                        "see README",
                )
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

    // Uses describeShape(value) rather than interpolating `value` itself: a Part21Value's
    // toString() (data-class-generated) echoes the entire parsed payload — e.g.
    // Part21Value.Str(text=...) or Part21Value.Num(lexeme=...) — with no bound other than
    // MAX_SOURCE_LENGTH, so a malicious/malformed HEADER argument (FILE_DESCRIPTION,
    // FILE_NAME, FILE_SCHEMA — reachable via the public Part21Reader entry point, before the
    // DATA section, in both Part21ReadModes) could otherwise land a multi-megabyte string in
    // a caller's log line. describeShape already gives checkArgShape this same fixed-word,
    // inherently-bounded description ('STRING', 'a NUMBER', ...) for the identical purpose.
    private fun asString(
        value: Part21Value,
        context: String,
    ): String =
        when (value) {
            is Part21Value.Str -> value.text
            else ->
                throw Part21SyntaxException(
                    "expected a string value for $context but found ${describeShape(value)}",
                )
        }

    private fun asStringList(
        value: Part21Value,
        context: String,
    ): List<String> =
        when (value) {
            is Part21Value.ListValue -> value.items.map { asString(it, context) }
            else ->
                throw Part21SyntaxException(
                    "expected a list of strings for $context but found ${describeShape(value)}",
                )
        }
}
