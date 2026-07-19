package dev.kstep.step21

/** A single parsed Part-21 argument value — a quoted string, a `#N` reference, or a parenthesized list of values. */
internal sealed interface Part21Value {
    data class Str(
        val text: String,
    ) : Part21Value

    data class Ref(
        val id: Int,
    ) : Part21Value

    data class ListValue(
        val items: List<Part21Value>,
    ) : Part21Value
}
