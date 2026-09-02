package dev.kstep.geometry.occt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Targeted tests for [OcctNativeLibrary.resolveLibraryPath]'s override-path validation -- the
 * only guard on the only path by which a library other than the bundled classpath resource is
 * ever loaded into this process via [System.load] (see that function's KDoc and the class KDoc
 * on [OcctNativeLibrary]).
 *
 * Deliberately never touches [OcctNativeLibrary.availability]: that is a `by lazy` on a Kotlin
 * `object`, resolved (and cached) exactly once per JVM process, so accessing it here would wedge
 * its outcome for the rest of this module's test run and make these tests order-dependent on
 * whatever other test happens to touch it first. `resolveLibraryPath()` itself never calls
 * [System.load] and has no such caching, so it can be called repeatedly and independently of
 * `availability` -- which is exactly why it is `internal` rather than folded into `load()`.
 *
 * No OCCT installation is required for any of these three cases.
 */
class OcctNativeLibraryResolveLibraryPathTest :
    StringSpec({
        "a relative override path is rejected" {
            withOverrideProperty("relative/path/to/libkstep_occt_bridge.so") {
                shouldThrow<IllegalArgumentException> { OcctNativeLibrary.resolveLibraryPath() }
            }
        }

        "an override path that names a directory, not a regular file, is rejected" {
            val dir = Files.createTempDirectory("occt-native-library-test-")
            try {
                withOverrideProperty(dir.toAbsolutePath().toString()) {
                    shouldThrow<IllegalArgumentException> { OcctNativeLibrary.resolveLibraryPath() }
                }
            } finally {
                Files.delete(dir)
            }
        }

        "a nonexistent override path is rejected" {
            val parent = Files.createTempDirectory("occt-native-library-test-")
            try {
                val missing = parent.resolve("does-not-exist.so")
                withOverrideProperty(missing.toAbsolutePath().toString()) {
                    // Path.toRealPath() is what's expected to fail here -- it both canonicalizes
                    // symlinks and requires the target to actually exist (see resolveLibraryPath's
                    // doc comment on OcctNativeLibrary), so a path that was never created throws
                    // before the isRegularFile check is even reached.
                    shouldThrow<NoSuchFileException> { OcctNativeLibrary.resolveLibraryPath() }
                }
            } finally {
                Files.delete(parent)
            }
        }
    })

/**
 * Sets [OcctNativeLibrary.OVERRIDE_PROPERTY] to [value] for the duration of [block], then
 * restores whatever the property held before (clearing it if it was unset) -- so a failing
 * assertion inside [block] can never leak an override into a later, unrelated test.
 */
private inline fun withOverrideProperty(
    value: String,
    block: () -> Unit,
) {
    val previous = System.getProperty(OcctNativeLibrary.OVERRIDE_PROPERTY)
    System.setProperty(OcctNativeLibrary.OVERRIDE_PROPERTY, value)
    try {
        block()
    } finally {
        if (previous == null) {
            System.clearProperty(OcctNativeLibrary.OVERRIDE_PROPERTY)
        } else {
            System.setProperty(OcctNativeLibrary.OVERRIDE_PROPERTY, previous)
        }
    }
}
