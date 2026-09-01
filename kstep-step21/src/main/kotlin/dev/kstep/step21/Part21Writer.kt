package dev.kstep.step21

import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.Approval
import dev.kstep.generated.ap242v1.ApprovalStatus
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.Organization
import dev.kstep.generated.ap242v1.Person
import dev.kstep.generated.ap242v1.PersonAndOrganization
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductContext
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionContext
import dev.kstep.generated.ap242v1.ProductDefinitionFormation
import java.util.IdentityHashMap

/**
 * Serializes a graph of already-validated `kstep-core` AP242 V1 instances (plus, as of kSTEP M2
 * Welle 10, the six support entity types the codegen-generated shapes now require —
 * `application_context`/`product_context`/`product_definition_context`/`approval_status`/
 * `person`/`organization`), reachable from one or more `roots`, into a complete, syntactically
 * valid ISO 10303-21 physical exchange file.
 */
object Part21Writer {
    private const val MAX_WRITE_GRAPH_DEPTH = 64

    fun write(
        header: Part21Header,
        roots: List<Any>,
    ): String {
        val identityMap = IdentityHashMap<Any, Int>()
        val order = mutableListOf<Any>()
        for (root in roots) {
            visit(root, 0, identityMap, order)
        }

        return buildString {
            append("ISO-10303-21;\n")
            append("HEADER;\n")
            appendHeaderStatements(this, header)
            append("ENDSEC;\n")
            append("DATA;\n")
            for ((index, instance) in order.withIndex()) {
                appendInstanceStatement(this, index + 1, instance, identityMap)
            }
            append("ENDSEC;\n")
            append("END-ISO-10303-21;\n")
        }
    }

    fun write(
        header: Part21Header,
        vararg roots: Any,
    ): String = write(header, roots.toList())

    // Post-order DFS reachability walk: every referenced instance is fully discovered (and
    // numbered, in the caller-facing DATA section pass below) before the instance referencing
    // it, matching the human-readable convention illustrative Part-21 files typically follow
    // (not spec-mandated, but deterministic and reproducible across repeated calls on the same
    // object graph and root order). Dedup key is object identity (IdentityHashMap), never
    // equals()/hashCode() — two structurally-equal-but-distinct instances must get two
    // distinct #N, only the literal same object reused across multiple referencing sites
    // collapses to one. Depth-capped (not converted to an explicit work-stack) because the
    // twelve V1+support types have a true max reference-chain depth of 6 (application_context
    // -> product_context -> product -> product_definition_formation -> product_definition ->
    // next_assembly_usage_occurrence) — MAX_WRITE_GRAPH_DEPTH=64 is defense-in-depth headroom,
    // not a realistic limit, so native recursion cannot practically overflow the JVM stack here.
    private fun visit(
        instance: Any,
        depth: Int,
        identityMap: IdentityHashMap<Any, Int>,
        order: MutableList<Any>,
    ) {
        if (identityMap.containsKey(instance)) return
        if (depth >= MAX_WRITE_GRAPH_DEPTH) {
            throw Part21LimitExceededException(
                "write graph depth exceeds the maximum supported depth of $MAX_WRITE_GRAPH_DEPTH",
            )
        }
        for (ref in referencesOf(instance)) {
            visit(ref, depth + 1, identityMap, order)
        }
        if (!identityMap.containsKey(instance)) {
            identityMap[instance] = order.size + 1
            order += instance
        }
    }

    private fun referencesOf(instance: Any): List<Any> =
        when (instance) {
            is ApplicationContext -> emptyList()
            is ApprovalStatus -> emptyList()
            is Person -> emptyList()
            is Organization -> emptyList()
            is ProductContext -> listOf(instance.frameOfReference)
            is ProductDefinitionContext -> listOf(instance.frameOfReference)
            is Product -> instance.frameOfReference.toList()
            is ProductDefinitionFormation -> listOf(instance.ofProduct)
            is ProductDefinition -> listOf(instance.formation, instance.frameOfReference)
            is NextAssemblyUsageOccurrence ->
                listOf(instance.relatingProductDefinition, instance.relatedProductDefinition)
            is Approval -> listOf(instance.status)
            is PersonAndOrganization -> listOf(instance.thePerson, instance.theOrganization)
            else -> unsupportedInstanceType(instance)
        }

    private fun entityKindOf(instance: Any): Part21EntityKind =
        when (instance) {
            is ApplicationContext -> Part21EntityKind.APPLICATION_CONTEXT
            is ProductContext -> Part21EntityKind.PRODUCT_CONTEXT
            is ProductDefinitionContext -> Part21EntityKind.PRODUCT_DEFINITION_CONTEXT
            is ApprovalStatus -> Part21EntityKind.APPROVAL_STATUS
            is Person -> Part21EntityKind.PERSON
            is Organization -> Part21EntityKind.ORGANIZATION
            is Product -> Part21EntityKind.PRODUCT
            is ProductDefinitionFormation -> Part21EntityKind.PRODUCT_DEFINITION_FORMATION
            is ProductDefinition -> Part21EntityKind.PRODUCT_DEFINITION
            is NextAssemblyUsageOccurrence -> Part21EntityKind.NEXT_ASSEMBLY_USAGE_OCCURRENCE
            is Approval -> Part21EntityKind.APPROVAL
            is PersonAndOrganization -> Part21EntityKind.PERSON_AND_ORGANIZATION
            else -> unsupportedInstanceType(instance)
        }

    private fun writeArgs(
        instance: Any,
        identityMap: IdentityHashMap<Any, Int>,
    ): List<String> {
        fun ref(target: Any) = "#${identityMap.getValue(target)}"

        fun refList(targets: Collection<Any>) = "(" + targets.joinToString(",") { ref(it) } + ")"
        return when (instance) {
            is ApplicationContext -> listOf(quoteString(instance.application))
            is ProductContext ->
                listOf(quoteString(instance.name), ref(instance.frameOfReference), quoteString(instance.disciplineType))
            is ProductDefinitionContext ->
                listOf(
                    quoteString(instance.name),
                    ref(instance.frameOfReference),
                    quoteString(instance.lifeCycleStage),
                )
            is ApprovalStatus -> listOf(quoteString(instance.name))
            is Person ->
                listOf(
                    quoteString(instance.id),
                    quoteStringOrUnset(instance.lastName),
                    quoteStringOrUnset(instance.firstName),
                    stringListOrUnset(instance.middleNames),
                    stringListOrUnset(instance.prefixTitles),
                    stringListOrUnset(instance.suffixTitles),
                )
            is Organization ->
                listOf(
                    quoteStringOrUnset(instance.id),
                    quoteString(instance.name),
                    quoteStringOrUnset(instance.description),
                )
            is Product ->
                listOf(
                    quoteString(instance.id),
                    quoteString(instance.name),
                    quoteStringOrUnset(instance.description),
                    refList(instance.frameOfReference),
                )
            is ProductDefinitionFormation ->
                listOf(quoteString(instance.id), quoteStringOrUnset(instance.description), ref(instance.ofProduct))
            is ProductDefinition ->
                listOf(
                    quoteString(instance.id),
                    quoteStringOrUnset(instance.description),
                    ref(instance.formation),
                    ref(instance.frameOfReference),
                )
            is NextAssemblyUsageOccurrence ->
                listOf(
                    quoteString(instance.id),
                    quoteString(instance.name),
                    quoteStringOrUnset(instance.description),
                    ref(instance.relatingProductDefinition),
                    ref(instance.relatedProductDefinition),
                    quoteStringOrUnset(instance.referenceDesignator),
                )
            is Approval -> listOf(ref(instance.status), quoteString(instance.level))
            is PersonAndOrganization -> listOf(ref(instance.thePerson), ref(instance.theOrganization))
            else -> unsupportedInstanceType(instance)
        }
    }

    private fun unsupportedInstanceType(instance: Any): Nothing =
        throw Part21WriteException(
            "object of type '${instance::class.qualifiedName}' reachable from the writer's roots is not " +
                "one of the twelve supported kstep-core AP242 V1/support entity types",
        )

    private fun appendInstanceStatement(
        sb: StringBuilder,
        id: Int,
        instance: Any,
        identityMap: IdentityHashMap<Any, Int>,
    ) {
        val kind = entityKindOf(instance)
        val args = writeArgs(instance, identityMap)
        sb.append("#$id=${kind.entityName}(${args.joinToString(",")});\n")
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

    // Renders a nullable OPTIONAL LIST OF label attribute (Person.middleNames and friends): the
    // Part-21 '$' token when unset, or a parenthesized list of quoted strings otherwise — never
    // an empty '()' standing in for "unset", which would be ambiguous with a genuinely empty
    // (but present) LIST.
    private fun stringListOrUnset(items: List<String>?): String =
        if (items == null) "$" else "(" + items.joinToString(",") { quoteString(it) } + ")"

    private fun quoteStringOrUnset(value: String?): String = if (value == null) "$" else quoteString(value)

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
                    "value '$value' contains a reverse solidus ('\\', code point 0x5c) — V1 does not " +
                        "implement the ISO 10303-21 \\X\\/\\X2\\/\\X4\\ escape mechanism, so an unescaped " +
                        "backslash cannot be written without risking misinterpretation by conformant " +
                        "external Part-21 readers, see README",
                )
            }
            if (c.code < 0x20 || c.code > 0x7E) {
                throw Part21EncodingException(
                    "value '$value' contains an unsupported character (code point 0x${
                        c.code.toString(16)
                    }) — only printable ASCII is supported, see README",
                )
            }
        }
    }
}
