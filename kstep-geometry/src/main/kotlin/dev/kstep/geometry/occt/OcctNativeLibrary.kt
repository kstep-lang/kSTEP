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
 * 2. Otherwise: the bundled classpath resource at [resourcePath] (derived from a whitelisted platform id, never
 *    influenced by any runtime input) is extracted into a **freshly created**, owner-only-
 *    permission temp directory (never a predictable, shared path like a fixed name under `/tmp`),
 *    under a constant file name ([libraryFileName] -- again never derived from any input),
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

    /** Platform ids this bridge can be built for; the ONLY values [detectPlatform] ever returns. */
    internal const val PLATFORM_LINUX_X86_64: String = "linux-x86-64"
    internal const val PLATFORM_MACOS_AARCH64: String = "macos-aarch64"

    private const val BRIDGE_BASE_NAME: String = "libkstep_occt_bridge"
    private const val RESOURCE_DIR: String = "/dev/kstep/geometry/native"

    /**
     * Maps `os.name` / `os.arch` onto one of the whitelisted platform ids, or `null` for any
     * unsupported combination (Intel Mac, Windows, Linux aarch64, ...). Pure function so the
     * mapping is unit-testable on every host. Never echoes its inputs into a resource path:
     * only the constants above can flow into [resourcePath].
     */
    internal fun detectPlatform(
        osName: String?,
        osArch: String?,
    ): String? {
        val name = osName.orEmpty()
        val arch = osArch.orEmpty()
        return when {
            name.contains("Linux", ignoreCase = true) && arch in setOf("amd64", "x86_64") -> PLATFORM_LINUX_X86_64
            name.contains("Mac", ignoreCase = true) && arch in setOf("aarch64", "arm64") -> PLATFORM_MACOS_AARCH64
            else -> null
        }
    }

    /** File name of the bridge library on [platform] (`.so` on Linux, `.dylib` on macOS). */
    internal fun libraryFileName(platform: String): String =
        when (platform) {
            PLATFORM_LINUX_X86_64 -> "$BRIDGE_BASE_NAME.so"
            PLATFORM_MACOS_AARCH64 -> "$BRIDGE_BASE_NAME.dylib"
            else -> throw IllegalArgumentException("Unsupported platform id: $platform")
        }

    /** Classpath resource path of the bundled bridge for [platform]; a whitelist-derived constant. */
    internal fun resourcePath(platform: String): String = "$RESOURCE_DIR/$platform/${libraryFileName(platform)}"

    private const val SUPPORTED_PLATFORMS: String = "linux-x86-64, macos-aarch64"
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
        val osName = System.getProperty("os.name")
        val osArch = System.getProperty("os.arch")
        val platform =
            detectPlatform(osName, osArch)
                ?: throw IOException(
                    "unsupported platform os=$osName arch=$osArch (supported: $SUPPORTED_PLATFORMS)",
                )
        val resourcePath = resourcePath(platform)
        val resource =
            OcctNativeLibrary::class.java.getResourceAsStream(resourcePath)
                ?: throw IOException(
                    "Classpath resource not found: $resourcePath (this platform is not one of " +
                        "$SUPPORTED_PLATFORMS, or the bridge was not built -- see README 'Building')",
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
                    // not expected on the supported platforms (linux-x86-64, macos-aarch64) but kept
                    // defensive rather than assuming the host filesystem.
                    Files.createTempDirectory(TEMP_DIR_PREFIX)
                }
            tempDir.toFile().deleteOnExit()
            val target = tempDir.resolve(libraryFileName(platform))
            Files.copy(input, target)
            target.toFile().deleteOnExit()
            return target.toRealPath()
        }
    }
}
