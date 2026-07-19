package dev.kstep.step21

import dev.kstep.core.ap242.Approval
import dev.kstep.core.ap242.NextAssemblyUsageOccurrence
import dev.kstep.core.ap242.PersonAndOrganization
import dev.kstep.core.ap242.Product
import dev.kstep.core.ap242.ProductDefinition
import dev.kstep.core.ap242.ProductDefinitionFormation
import java.util.IdentityHashMap

/**
 * Serializes a graph of already-validated `kstep-core` AP242 V1 instances, reachable from one
 * or more `roots`, into a complete, syntactically valid ISO 10303-21 physical exchange file.
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
    // collapses to one. Depth-capped (not converted to an explicit work-stack) because the six
    // V1 types have a true max chain depth of 4 — MAX_WRITE_GRAPH_DEPTH=64 is defense-in-depth
    // headroom, not a realistic limit, so native recursion cannot practically overflow the JVM
    // stack here.
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
            is Product -> emptyList()
            is PersonAndOrganization -> emptyList()
            is ProductDefinitionFormation -> listOf(instance.ofProduct)
            is ProductDefinition -> listOf(instance.formation)
            is NextAssemblyUsageOccurrence ->
                listOf(instance.relatingProductDefinition, instance.relatedProductDefinition)
            is Approval -> listOf(instance.authorizedBy)
            else ->
                throw Part21WriteException(
                    "object of type '${instance::class.qualifiedName}' reachable from the writer's roots is not " +
                        "one of the six supported kstep-core AP242 V1 entity types",
                )
        }

    private fun entityKindOf(instance: Any): Part21EntityKind =
        when (instance) {
            is Product -> Part21EntityKind.PRODUCT
            is PersonAndOrganization -> Part21EntityKind.PERSON_AND_ORGANIZATION
            is ProductDefinitionFormation -> Part21EntityKind.PRODUCT_DEFINITION_FORMATION
            is ProductDefinition -> Part21EntityKind.PRODUCT_DEFINITION
            is NextAssemblyUsageOccurrence -> Part21EntityKind.NEXT_ASSEMBLY_USAGE_OCCURRENCE
            is Approval -> Part21EntityKind.APPROVAL
            else ->
                throw Part21WriteException(
                    "object of type '${instance::class.qualifiedName}' reachable from the writer's roots is not " +
                        "one of the six supported kstep-core AP242 V1 entity types",
                )
        }

    private fun writeArgs(
        instance: Any,
        identityMap: IdentityHashMap<Any, Int>,
    ): List<String> {
        fun ref(target: Any) = "#${identityMap.getValue(target)}"
        return when (instance) {
            is Product ->
                listOf(quoteString(instance.id), quoteString(instance.name), quoteString(instance.description))
            is PersonAndOrganization -> listOf(quoteString(instance.thePerson), quoteString(instance.theOrganization))
            is ProductDefinitionFormation ->
                listOf(quoteString(instance.id), quoteString(instance.description), ref(instance.ofProduct))
            is ProductDefinition ->
                listOf(quoteString(instance.id), quoteString(instance.description), ref(instance.formation))
            is NextAssemblyUsageOccurrence ->
                listOf(
                    quoteString(instance.id),
                    quoteString(instance.name),
                    ref(instance.relatingProductDefinition),
                    ref(instance.relatedProductDefinition),
                    quoteString(instance.referenceDesignator),
                )
            is Approval ->
                listOf(quoteString(instance.status), quoteString(instance.level), ref(instance.authorizedBy))
            else ->
                throw Part21WriteException(
                    "object of type '${instance::class.qualifiedName}' reachable from the writer's roots is not " +
                        "one of the six supported kstep-core AP242 V1 entity types",
                )
        }
    }

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

    // Single-quote doubling ('O''Brien' round-trips O'Brien), matching the reader's manual
    // scan (see Part21Tokenizer's KDoc for why this is hand-written instead of reusing the
    // ANTLR EXPRESS lexer's documented-buggy SimpleStringLiteral rule).
    private fun quoteString(value: String): String {
        assertEncodable(value)
        return "'" + value.replace("'", "''") + "'"
    }

    private fun assertEncodable(value: String) {
        for (c in value) {
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
