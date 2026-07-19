package dev.kstep.step21

/**
 * The three fixed `HEADER;` statements of an ISO 10303-21 physical file
 * (`FILE_DESCRIPTION`, `FILE_NAME`, `FILE_SCHEMA`), flattened into one data class.
 *
 * [fileName], [timestamp] and [schemaIdentifiers] have no defaults: the writer must be told
 * these explicitly rather than silently emitting a placeholder-looking value. [timestamp] is
 * opaque to this module — no ISO-8601 parsing or validation is performed, it is written
 * verbatim. The remaining fields default to legitimately-blank values (empty list/string),
 * which the Part-21 spec itself treats as valid, not a fake stand-in.
 */
data class Part21Header(
    val fileName: String,
    val timestamp: String,
    val schemaIdentifiers: List<String>,
    val description: List<String> = emptyList(),
    val implementationLevel: String = "2;1",
    val author: List<String> = emptyList(),
    val organization: List<String> = emptyList(),
    val preprocessorVersion: String = "",
    val originatingSystem: String = "kSTEP",
    val authorization: String = "",
)
