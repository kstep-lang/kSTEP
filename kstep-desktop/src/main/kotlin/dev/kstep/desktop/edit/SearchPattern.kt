package dev.kstep.desktop.edit

/** What the find bar searches for (ADR-0090): a literal text, or a pattern of the [SafeRegex] subset. */
sealed interface SearchPattern {
    val matchCase: Boolean
    val wholeWord: Boolean

    data class Literal(
        val query: String,
        override val matchCase: Boolean,
        override val wholeWord: Boolean,
    ) : SearchPattern

    class Regex internal constructor(
        val source: String,
        internal val compiled: SafeRegex.Compiled.Ok,
        override val matchCase: Boolean,
        override val wholeWord: Boolean,
    ) : SearchPattern {
        val groupCount: Int get() = compiled.groupCount
    }

    sealed interface Compile {
        data class Ok(
            val pattern: SearchPattern,
        ) : Compile

        data class Rejected(
            val reason: SafeRegex.RejectReason,
            val escapeChar: Int?,
        ) : Compile
    }

    companion object {
        /** Never throws. An empty literal query is valid (it just finds nothing); an empty regex is rejected as EMPTY. */
        fun compile(
            query: String,
            regex: Boolean,
            matchCase: Boolean,
            wholeWord: Boolean,
        ): Compile {
            if (!regex) return Compile.Ok(Literal(query, matchCase, wholeWord))
            return when (val c = SafeRegex.compile(query, matchCase, wholeWord)) {
                is SafeRegex.Compiled.Ok -> Compile.Ok(Regex(query, c, matchCase, wholeWord))
                is SafeRegex.Compiled.Rejected -> Compile.Rejected(c.reason, c.escapeChar)
            }
        }
    }
}
