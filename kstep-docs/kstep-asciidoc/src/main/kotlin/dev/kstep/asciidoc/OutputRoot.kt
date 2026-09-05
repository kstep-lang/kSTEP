package dev.kstep.asciidoc

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * One validated write target inside an [OutputRoot]. [path] is the (lexical) path actually
 * written through -- it may traverse a symlinked ancestor directory. [identity] is the REAL-path
 * key used for whole-run collision tracking: two [WriteTarget]s with equal [identity] refer to
 * the SAME physical file even when their [path]s are lexically different, e.g. one reached
 * directly and one through a symlinked directory placed inside the output tree. See
 * docs/adr/ADR-0019-kstep-asciidoc.adoc's Security section, Round 5's H1 finding.
 */
internal data class WriteTarget(
    val path: Path,
    val identity: Path,
)

/**
 * The single place `kstep asciidoc`'s write side resolves an output path AND checks it for
 * containment/collisions. Introduced after a Round 5 review found the previous pattern --
 * lexical containment checked in one place ([AsciidocProcessor]'s `reservedOutputPaths`,
 * `target.toAbsolutePath().normalize()`), physical real-path containment checked in another,
 * independently computed way -- kept drifting apart across five review rounds (R2 a
 * dangling leaf symlink, R3 a pre-existing output-dir-internal directory symlink, R4 a
 * raw-vs-normalized single-file `..`-through-symlink mismatch, R5 an output-dir-INTERNAL symlink
 * escaping the whole-tree collision guard because that guard tracked lexical, not real, paths).
 * Every output-path resolution in [AsciidocProcessor] now goes through [resolveTarget]; nothing
 * else calls [AsciidocPaths.realPathOfNearestExistingAncestor] directly for a WRITE path.
 */
internal class OutputRoot private constructor(
    val lexical: Path,
    val real: Path,
) {
    companion object {
        /**
         * Does NOT create [dir] -- callers create it only AFTER every guard has passed, exactly
         * like [AsciidocProcessor]'s existing "validate everything before any write" discipline.
         * [dir] need not exist yet: [AsciidocPaths.realPathOfNearestExistingAncestor] walks up to
         * whichever ancestor does. Returns `null` only if not even the shallowest ancestor can be
         * real-pathed (e.g. every ancestor is unreadable) -- never throws.
         */
        fun resolve(dir: Path): OutputRoot? {
            val lexical = dir.toAbsolutePath().normalize()
            val real = AsciidocPaths.realPathOfNearestExistingAncestor(lexical) ?: return null
            return OutputRoot(lexical, real)
        }
    }

    /**
     * Physical overlap check against another already-real-pathed root -- the guard that closes a
     * symlinked `--output-dir`/`--input-dir` aliasing each other. Callers MUST run this (via
     * [AsciidocPaths.realPathOfNearestExistingAncestor] on both roots) BEFORE
     * `Files.createDirectories` ever touches either tree -- Round 5's H2 finding was exactly this
     * check running AFTER the output root had already been created, which could plant a directory
     * inside `--input-dir` even on a run that was ultimately rejected.
     */
    fun overlapsWith(otherReal: Path): Boolean = real.startsWith(otherReal) || otherReal.startsWith(real)

    /**
     * Resolves [raw] (already known to be lexically safe -- a walk-relativized path, a bare file
     * name, or an already-validated `:imagesdir:`-derived relative path) against this root:
     * lexical containment first, then the same physical real-path check
     * [AsciidocPaths.realPathOfNearestExistingAncestor] performs -- but this ALSO returns that
     * real path as [WriteTarget.identity], so a caller tracking collisions across many
     * resolutions (a whole tree walk, or one document's `.adoc` output plus every one of its
     * images) compares REAL identities, never lexical ones. Returns `null` on any rejection --
     * never throws.
     */
    fun resolveTarget(raw: String): WriteTarget? {
        val candidate =
            try {
                lexical.resolve(raw).normalize()
            } catch (_: InvalidPathException) {
                return null
            }
        if (!candidate.startsWith(lexical)) return null
        val candidateReal = AsciidocPaths.realPathOfNearestExistingAncestor(candidate) ?: return null
        if (!candidateReal.startsWith(real)) return null
        return WriteTarget(path = candidate, identity = candidateReal)
    }
}
