package dev.kstep.constraints.planegcs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Targeted tests for [PlaneGcsNativeLibrary.resolveLibraryPath]'s override-path validation -- the
 * only guard on the only path by which a library other than the bundled classpath resource is ever
 * loaded into this process via [System.load] (see that function's KDoc and the class KDoc on
 * [PlaneGcsNativeLibrary]). Mirrors kstep-geometry's `OcctNativeLibraryResolveLibraryPathTest`
 * exactly, one-for-one, plus a fourth case for the accepted (valid regular file) path.
 *
 * Deliberately never touches [PlaneGcsNativeLibrary.availability]: that is a `by lazy` on a Kotlin
 * `object`, resolved (and cached) exactly once per JVM process, so accessing it here would wedge
 * its outcome for the rest of this module's test run and make these tests order-dependent on
 * whatever other test happens to touch it first. `resolveLibraryPath()` itself never calls
 * [System.load] and has no such caching, so it can be called repeatedly and independently of
 * `availability` -- which is exactly why it is `internal` rather than folded into `load()`.
 *
 * No PlaneGCS native build is required for any of these four cases.
 */
class PlaneGcsNativeLibraryResolveLibraryPathTest :
    StringSpec({
        "a relative override path is rejected" {
            withOverrideProperty("relative/path/to/libkstep_planegcs_bridge.so") {
                shouldThrow<IllegalArgumentException> { PlaneGcsNativeLibrary.resolveLibraryPath() }
            }
        }

        "an override path that names a directory, not a regular file, is rejected" {
            val dir = Files.createTempDirectory("planegcs-native-library-test-")
            try {
                withOverrideProperty(dir.toAbsolutePath().toString()) {
                    shouldThrow<IllegalArgumentException> { PlaneGcsNativeLibrary.resolveLibraryPath() }
                }
            } finally {
                Files.delete(dir)
            }
        }

        "a nonexistent override path is rejected" {
            val parent = Files.createTempDirectory("planegcs-native-library-test-")
            try {
                val missing = parent.resolve("does-not-exist.so")
                withOverrideProperty(missing.toAbsolutePath().toString()) {
                    // Path.toRealPath() is what's expected to fail here -- it both canonicalizes
                    // symlinks and requires the target to actually exist (see resolveLibraryPath's
                    // doc comment on PlaneGcsNativeLibrary), so a path that was never created throws
                    // before the isRegularFile check is even reached.
                    shouldThrow<NoSuchFileException> { PlaneGcsNativeLibrary.resolveLibraryPath() }
                }
            } finally {
                Files.delete(parent)
            }
        }

        "an override path naming a real regular file resolves to its canonical absolute path" {
            val dir = Files.createTempDirectory("planegcs-native-library-test-")
            val file = Files.createFile(dir.resolve("not-really-a-library.so"))
            try {
                withOverrideProperty(file.toAbsolutePath().toString()) {
                    PlaneGcsNativeLibrary.resolveLibraryPath() shouldBe file.toRealPath()
                }
            } finally {
                Files.delete(file)
                Files.delete(dir)
            }
        }
    })

/**
 * Sets [PlaneGcsNativeLibrary.OVERRIDE_PROPERTY] to [value] for the duration of [block], then
 * restores whatever the property held before (clearing it if it was unset) -- so a failing
 * assertion inside [block] can never leak an override into a later, unrelated test.
 */
private inline fun withOverrideProperty(
    value: String,
    block: () -> Unit,
) {
    val previous = System.getProperty(PlaneGcsNativeLibrary.OVERRIDE_PROPERTY)
    System.setProperty(PlaneGcsNativeLibrary.OVERRIDE_PROPERTY, value)
    try {
        block()
    } finally {
        if (previous == null) {
            System.clearProperty(PlaneGcsNativeLibrary.OVERRIDE_PROPERTY)
        } else {
            System.setProperty(PlaneGcsNativeLibrary.OVERRIDE_PROPERTY, previous)
        }
    }
}
