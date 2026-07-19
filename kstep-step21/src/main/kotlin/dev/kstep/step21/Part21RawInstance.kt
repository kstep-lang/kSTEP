package dev.kstep.step21

/** One `#id=entityName(args);` statement from a Part-21 `DATA` section, before type-aware resolution. */
internal data class Part21RawInstance(
    val id: Int,
    val entityName: String,
    val args: List<Part21Value>,
    val sourceLine: Int,
)

/** The full result of [Part21Tokenizer.parseDocument]'s pass-1 scan: a parsed [header] plus the raw `DATA` statements. */
internal data class Part21RawDocument(
    val header: Part21Header,
    val instances: List<Part21RawInstance>,
)
