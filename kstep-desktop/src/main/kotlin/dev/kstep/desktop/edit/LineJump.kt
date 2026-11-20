package dev.kstep.desktop.edit

/** Parses the input of the "go to line" field (ADR-0067). */
object LineJump {
    const val MAX_DIGITS = 9

    sealed interface Result {
        data class Valid(
            val line: Int,
        ) : Result

        data object Empty : Result

        data object NotANumber : Result

        data class OutOfRange(
            val min: Int,
            val max: Int,
        ) : Result
    }

    /** Only ASCII spaces and tabs are trimmed, only ASCII digits count (no signs, no Unicode digits). */
    fun parse(
        input: String,
        lineCount: Int,
    ): Result {
        val s = input.trim { it == ' ' || it == '\t' }
        if (s.isEmpty()) return Result.Empty
        if (s.length > MAX_DIGITS || s.any { it !in '0'..'9' }) return Result.NotANumber
        val max = lineCount.coerceAtLeast(1)
        val value = s.toInt()
        return if (value < 1 || value > max) Result.OutOfRange(1, max) else Result.Valid(value)
    }
}
