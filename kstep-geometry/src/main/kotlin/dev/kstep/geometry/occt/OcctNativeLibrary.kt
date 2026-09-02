package dev.kstep.geometry.occt

import dev.kstep.geometry.OcctAvailability
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private val logger = KotlinLogging.logger {}

/**
 * Resolves and loads the native `kstep_occt_bridge` library exactly once per JVM process, then
 * caches the outcome as an [OcctAvailability].
 *
 * Deliberately does NOT use [System.loadLibrary] -- that consults `java.library.path`, which is
 * partially environment-controlled (e.g. a `-Djava.library.path=...` value the JVM launcher sets,
 * or a same-named library earlier on that path shadowing the intended one). Only [System.load]
 * with a fully resolved, canonicalized, absolute path is used, via exactly one of two resolution
 * strategies below -- no further fallback chain beyond these two:
 *
 * 1. [OVERRIDE_PROPERTY] system property, if set: must already be an absolute path; resolved with
 *    [Path.toRealPath] (which both canonicalizes symlinks and throws if the target does not
 *    exist) and must name a regular file. Logged at INFO so any override is visible in build/CI
 *    logs -- an unexpected override should never silently take effect unnoticed.
 * 2. Otherwise: the bundled classpath resource at [RESOURCE_PATH] (a compile-time constant, never
 *    influenced by any runtime input) is extracted into a **freshly created**, owner-only-
 *    permission temp directory (never a predictable, shared path like a fixed name under `/tmp`),
 *    under a constant file name ([EXTRACTED_FILE_NAME] -- again never derived from any input),
 *    and both the file and its containing directory are marked [java.io.File.deleteOnExit].
 *
 * `internal`: nothing outside this module needs to resolve or load the library directly --
 * [dev.kstep.geometry.OcctKernel] is the only intended caller (indirectly, via [OcctBridge]'s
 * native methods only working once this object's [availability] has been accessed at least once).
 */
internal object OcctNativeLibrary {
    /**
     * System property that, when set, names an absolute path to a `kstep_occt_bridge` shared
     * library to load instead of the one bundled as a classpath resource. Intended for local
     * development against a hand-built `.so` and for tests exercising the override path itself.
     * Only settable by whoever starts the JVM (`-D...` flag or trusted startup code calling
     * [System.setProperty] before this object is first touched) -- never derived from network
     * input, a config file parsed from untrusted data, or any per-request value.
     */
    const val OVERRIDE_PROPERTY: String = "kstep.occt.bridge.library"

    private const val RESOURCE_PATH: String = "/dev/kstep/geometry/native/linux-x86-64/libkstep_occt_bridge.so"
    private const val EXTRACTED_FILE_NAME: String = "libkstep_occt_bridge.so"
    private const val TEMP_DIR_PREFIX: String = "kstep-occt-"

    /** Resolved and cached on first access; every later access returns the same instance. */
    val availability: OcctAvailability by lazy { load() }

    private fun load(): OcctAvailability =
        try {
            val path = resolveLibraryPath()
            System.load(path.toString())
            val version = OcctBridge.nativeOcctVersion()
            val source =
                if (System.getProperty(OVERRIDE_PROPERTY) != null) {
                    "override property ($OVERRIDE_PROPERTY=$path)"
                } else {
                    "bundled classpath resource ($path)"
                }
            logger.info { "OCCT JNI bridge loaded from $source, OCCT version $version" }
            OcctAvailability.Available(librarySource = source, occtVersion = version)
        } catch (e: Throwable) {
            // Deliberately catches Throwable, not just Exception: UnsatisfiedLinkError and
            // LinkageError are Errors, not Exceptions, and are exactly the failure mode this
            // resolution is meant to degrade gracefully from (missing/incompatible native
            // library). Letting e.g. an OutOfMemoryError propagate uncaught out of this lazy
            // property initializer instead would not be handled any better by narrowing the catch.
            val reason = "OCCT native bridge unavailable: ${e::class.qualifiedName}: ${e.message}"
            logger.info { reason }
            OcctAvailability.Unavailable(reason = reason, cause = e)
        }

    // internal (not private): the only way this module's own test sourceset (which shares
    // internal visibility with `main` via the Kotlin Gradle plugin's default main/test
    // associated compilation) can exercise this validation directly, without ever touching
    // [availability] -- see OcctNativeLibraryResolveLibraryPathTest.
    internal fun resolveLibraryPath(): Path {
        val override = System.getProperty(OVERRIDE_PROPERTY)
        if (override != null) {
            val overridePath = Path.of(override)
            require(overridePath.isAbsolute) {
                "$OVERRIDE_PROPERTY must be an absolute path, got: $override"
            }
            val real = overridePath.toRealPath()
            require(Files.isRegularFile(real)) {
                "$OVERRIDE_PROPERTY does not point at a regular file: $real"
            }
            return real
        }
        return extractBundledLibrary()
    }

    private fun extractBundledLibrary(): Path {
        val resource =
            OcctNativeLibrary::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: throw IOException(
                    "Classpath resource not found: $RESOURCE_PATH (OCCT dev headers were absent " +
                        "at build time, or this platform is not linux-x86-64 -- see README " +
                        "'Building' for the required apt-get install)",
                )
        resource.use { input ->
            val supportsPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
            val tempDir =
                if (supportsPosix) {
                    Files.createTempDirectory(
                        TEMP_DIR_PREFIX,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                    )
                } else {
                    // Files.createTempDirectory's POSIX-permissions overload throws
                    // UnsupportedOperationException on a non-POSIX filesystem (e.g. Windows) --
                    // not applicable to this wave (linux-x86-64 only) but kept defensive rather
                    // than assuming the host filesystem.
                    Files.createTempDirectory(TEMP_DIR_PREFIX)
                }
            tempDir.toFile().deleteOnExit()
            val target = tempDir.resolve(EXTRACTED_FILE_NAME)
            Files.copy(input, target)
            target.toFile().deleteOnExit()
            return target.toRealPath()
        }
    }
}
