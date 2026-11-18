package dev.kstep.desktop.ai.keys

/**
 * An API key held in memory (ADR-0056). Deliberately not a data class and not serializable: `toString` never shows the
 * value, equality is identity, and [wipe] zeroes the characters. The value can only be read through the internal
 * [reveal] (used by `provider/` for the request header, enforced by the architecture test), or checked through
 * [occursIn]/[redact], which never return the key itself.
 */
class Secret private constructor(
    private var chars: CharArray?,
) {
    @Synchronized
    internal fun reveal(): String = String(chars ?: error("secret was wiped"))

    @Synchronized
    fun length(): Int = chars?.size ?: 0

    @Synchronized
    fun isWiped(): Boolean = chars == null

    /** True if the key occurs literally in [text] (defence in depth for answers, log lines and summaries). */
    @Synchronized
    fun occursIn(text: String): Boolean {
        val c = chars ?: return false
        return c.isNotEmpty() && text.contains(String(c))
    }

    /** [text] with every literal occurrence of the key replaced by `<redacted>`. */
    @Synchronized
    fun redact(text: String): String {
        val c = chars ?: return text
        return if (c.isEmpty()) text else text.replace(String(c), "<redacted>")
    }

    @Synchronized
    fun wipe() {
        chars?.fill('\u0000')
        chars = null
    }

    override fun toString(): String = "Secret(redacted)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** Takes ownership of [value]: the array is copied and then zeroed. */
        fun of(value: CharArray): Secret {
            val copy = value.copyOf()
            value.fill('\u0000')
            return Secret(copy)
        }

        fun fromEnv(value: String): Secret = Secret(value.trim().toCharArray())
    }
}
