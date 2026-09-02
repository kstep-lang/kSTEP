package dev.kstep.constraints

/**
 * Whether the native PlaneGCS JNI bridge could be loaded on this JVM, and -- when it could -- which
 * native library was actually loaded and from which vendored PlaneGCS source commit it was built.
 *
 * [PlaneGcsSolver.availability] computes this exactly once per JVM process and caches the outcome:
 * loading a native library twice from two different paths is undefined behavior, so this whole
 * module is built around "resolve once, cache the outcome, degrade gracefully afterward" rather
 * than retrying the native load on every call -- the same pattern as
 * `dev.kstep.geometry.OcctAvailability` in kstep-geometry.
 */
sealed interface PlaneGcsAvailability {
    /**
     * The native bridge loaded successfully.
     *
     * @property librarySource human-readable diagnostic describing where the loaded `.so` came from
     *   -- either the override system property or the bundled classpath resource. Not a filesystem
     *   path secret; safe to log or surface to a caller.
     * @property planeGcsCommit the vendored PlaneGCS source commit the loaded library was compiled
     *   from (see `kstep-constraints/src/main/cpp/third_party/planegcs/PROVENANCE.adoc`), as
     *   reported by [dev.kstep.constraints.planegcs.PlaneGcsBridge.nativePlaneGcsSourceCommit].
     */
    data class Available(
        val librarySource: String,
        val planeGcsCommit: String,
    ) : PlaneGcsAvailability

    /**
     * The native bridge could not be loaded -- missing Eigen/Boost headers or a C++ compiler at
     * build time (so no `.so` classpath resource exists in this module's jar at all), a platform
     * other than linux-x86-64 (the only one built in this wave), or a runtime link failure.
     * [reason] is meant to be read by a human or an LLM agent, not machine-parsed; it is also what
     * [PlaneGcsSolver]'s callers see inside [PlaneGcsUnavailableException]'s message.
     */
    data class Unavailable(
        val reason: String,
        val cause: Throwable? = null,
    ) : PlaneGcsAvailability
}
