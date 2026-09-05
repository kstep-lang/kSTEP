package dev.kstep.asciidoc

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val TARGET_NAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,${AsciidocLimits.MAX_TARGET_NAME_CHARS}}\$")

/**
 * Security-critical path helpers for `kstep asciidoc` -- kept `internal` and pure/side-effect-
 * minimal so [AsciidocScannerTest]/`AsciidocPathsTest` can exercise every rejection case directly,
 * without spinning up [AsciidocProcessor]'s full file-tree machinery. See
 * docs/adr/ADR-0019-kstep-asciidoc.adoc's Security section for the two-layer (lexical + physical)
 * reasoning behind [OutputRoot.resolveTarget], the actual write-path containment check every
 * output resolution in [AsciidocProcessor] goes through.
 */
internal object AsciidocPaths {
    /**
     * Accepts only a single, harmless path SEGMENT: `^[A-Za-z0-9._-]{1,100}$`, additionally
     * rejecting exactly "." and ".." (both would otherwise match the character class). Rejects
     * `/`, `\`, NUL, every ISO control character, and anything else outright. Returns `null` on
     * rejection -- never throws, never sanitizes silently.
     */
    fun validateTargetName(raw: String): String? {
        if (raw == "." || raw == "..") return null
        return if (TARGET_NAME_PATTERN.matches(raw)) raw else null
    }

    /**
     * Pure, filesystem-free syntax check used by [AsciidocProcessor]'s write-time re-check of a
     * resolved image path and by [AsciidocScanner]'s cheap early rejection of a syntactically
     * unsafe `:imagesdir:` value -- no leading `/` or `\`, no NUL, no `..` path segment. Does NOT
     * by itself guarantee containment (a relative path can still escape via a symlink) --
     * [OutputRoot.resolveTarget] is what actually enforces that, once a real root [Path] is
     * available.
     */
    fun isSyntacticallySafeRelativePath(raw: String): Boolean {
        if (raw.isBlank()) return false
        if (raw.startsWith("/") || raw.startsWith("\\")) return false
        val segments = raw.split('/', '\\')
        return segments.none { it == ".." || it.isEmpty() }
    }

    /**
     * Real-path approximation of [path] itself, tolerant of [path] (or any prefix of it) not yet
     * existing on disk: walks up to the nearest EXISTING ancestor, real-paths THAT ancestor, then
     * reattaches the still-missing lexical suffix on top of it. Never throws -- an `IOException`
     * from `toRealPath()` (an unreadable ancestor, a concurrent deletion) is treated as
     * "unresolvable" and returns `null`, rather than escaping as a raw stack trace. Used
     * exclusively by [OutputRoot] (`resolve`/`resolveTarget`) so every write-path resolution in
     * this module keeps using the exact same real-path notion of "this path" -- Round 5 found two
     * call sites (a lexical `reservedOutputPaths` set and this function's own physical check)
     * using two DIFFERENT notions of it, which is how an output-dir-internal symlink escaped the
     * whole-tree collision guard (see docs/adr/ADR-0019-kstep-asciidoc.adoc's H1 finding).
     */
    fun realPathOfNearestExistingAncestor(path: Path): Path? {
        val missing = mutableListOf<String>()
        var current: Path? = path
        while (current != null && !Files.exists(current)) {
            missing.add(0, current.fileName?.toString() ?: return null)
            current = current.parent
        }
        val ancestor = current ?: return null
        val ancestorReal =
            try {
                ancestor.toRealPath()
            } catch (_: IOException) {
                return null
            }
        return missing.fold(ancestorReal) { acc, name -> acc.resolve(name) }
    }

    /** Resolves a `kstep::<rawPath>[]` macro's script reference: relative to the SOURCE `.adoc`'s
     *  own directory ([adocDir]), but never allowed to escape [inputRoot] (the containment root a
     *  macro path must not cross -- `--input-dir` in tree mode, [adocDir] itself in single-file
     *  mode). */
    fun resolveScriptPath(
        adocDir: Path,
        inputRoot: Path,
        raw: String,
    ): Path? {
        val normalizedInputRoot = inputRoot.normalize()
        val candidate =
            try {
                adocDir.resolve(raw).normalize()
            } catch (_: java.nio.file.InvalidPathException) {
                return null
            }
        if (!candidate.startsWith(normalizedInputRoot)) return null
        if (Files.exists(candidate)) {
            val realCandidate =
                try {
                    candidate.toRealPath()
                } catch (_: IOException) {
                    return null
                }
            val realRoot =
                try {
                    normalizedInputRoot.toRealPath()
                } catch (_: IOException) {
                    return null
                }
            if (!realCandidate.startsWith(realRoot)) return null
        }
        return candidate
    }
}
