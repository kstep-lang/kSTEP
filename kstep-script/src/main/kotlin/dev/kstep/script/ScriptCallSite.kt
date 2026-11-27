package dev.kstep.script

/**
 * Finds the script line of the call that is running right now (ADR-0091).
 *
 * The line comes from the JVM stack: the innermost frame whose file name equals the name of the source the host is
 * evaluating. Only the line is known, not the column, and it is the line of the call, not of the place where a part's
 * name is defined. The walk is bounded and never throws.
 */
internal object ScriptCallSite {
    /** Frames inspected at most; a deeper script call chain simply yields no line. */
    const val MAX_FRAMES = 256L

    const val MAX_SOURCE_LINE = ScriptSourceLimits.MAX_SOURCE_LINE

    private val walker = StackWalker.getInstance()

    /** 1-based line of the innermost frame of the running script, or `null`. */
    fun line(scriptSourceName: String?): Int? {
        if (scriptSourceName.isNullOrEmpty()) return null
        return try {
            walker
                .walk { frames ->
                    frames
                        .limit(MAX_FRAMES)
                        .filter { it.fileName == scriptSourceName && it.lineNumber > 0 }
                        .findFirst()
                        .map { it.lineNumber }
                        .orElse(null)
                }?.takeIf { it in 1..MAX_SOURCE_LINE }
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** Limit shared by the script host (reports) and the desktop part-list validator (ADR-0091). */
object ScriptSourceLimits {
    /** Source lines above this are neither reported nor accepted. */
    const val MAX_SOURCE_LINE: Int = 10_000_000
}
