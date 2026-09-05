package dev.kstep.asciidoc

/**
 * Shared size/DoS guards for the `kstep asciidoc` pre-processing pipeline -- see
 * docs/adr/ADR-0019-kstep-asciidoc.adoc's Security section for the reasoning behind each bound.
 * Centralized here so [AsciidocScanner]/[AsciidocProcessor] validate against the exact same
 * numbers rather than hand-copied constants drifting apart.
 */
object AsciidocLimits {
    /** Every block of one document is rendered into memory before anything is written (the
     *  partial-output guarantee) -- this caps that buffer. 200 diagrams in one `.adoc` is far
     *  beyond any real document. */
    const val MAX_BLOCKS_PER_DOCUMENT: Int = 200

    /** Refuse to even scan a larger `.adoc`. */
    const val MAX_ADOC_BYTES: Long = 8L * 1024 * 1024

    /** Refuse a tree with more entries than this (walk-bomb guard). */
    const val MAX_FILES_IN_TREE: Int = 10_000

    /** Longest accepted `target`/attribute-name-like identifier. */
    const val MAX_TARGET_NAME_CHARS: Int = 100

    /** Longest accepted `alt=`/`title=` attribute text. */
    const val MAX_ATTRIBUTE_TEXT_CHARS: Int = 200
}
