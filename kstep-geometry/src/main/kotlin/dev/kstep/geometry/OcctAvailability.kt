package dev.kstep.geometry

/**
 * Whether the native OCCT (Open CASCADE Technology) JNI bridge could be loaded on this JVM, and
 * -- when it could -- which native library was actually loaded and what OCCT version it reports.
 *
 * [OcctKernel.availability] computes this exactly once per JVM process and caches the outcome:
 * loading a native library twice from two different paths is undefined behavior, so this whole
 * module is built around "resolve once, cache the outcome, degrade gracefully afterward" rather
 * than retrying the native load on every call.
 */
sealed interface OcctAvailability {
    /**
     * The native bridge loaded successfully.
     *
     * @property librarySource human-readable diagnostic describing where the loaded `.so` came
     *   from -- either the override system property or the bundled classpath resource. Not a
     *   filesystem path secret; safe to log or surface to a caller.
     * @property occtVersion the OCCT version string reported by the loaded library (e.g.
     *   `"7.9.2"`).
     */
    data class Available(
        val librarySource: String,
        val occtVersion: String,
    ) : OcctAvailability

    /**
     * The native bridge could not be loaded -- missing OCCT dev headers at build time (so no
     * `.so` classpath resource exists in this module's jar at all), a platform other than
     * linux-x86-64 (the only one built in this wave), or a runtime link failure. [reason] is
     * meant to be read by a human or an LLM agent, not machine-parsed; it is also what
     * [OcctKernel]'s callers see inside [OcctUnavailableException]'s message.
     */
    data class Unavailable(
        val reason: String,
        val cause: Throwable? = null,
    ) : OcctAvailability
}
