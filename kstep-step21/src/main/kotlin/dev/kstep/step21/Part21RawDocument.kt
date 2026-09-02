package dev.kstep.step21

/**
 * The full result of [Part21Tokenizer.parseDocument]'s pass-1 scan: a parsed [header] plus the
 * raw `DATA` statements, in source order, each either a [Part21SimpleInstance] or (under
 * [Part21ReadMode.TOLERANT]) a [Part21ComplexInstance].
 *
 * Public (was `internal` before kSTEP Geometrie Welle 4) — this is the syntactic-only view of a
 * Part-21 document `kstep-shape`'s merge path ([Part21Reader.readRawDocument]) operates on,
 * with no `kstep-core` construction involved.
 */
data class Part21RawDocument(
    val header: Part21Header,
    val instances: List<Part21EntityInstance>,
)
