package dev.kstep.desktop.run

import dev.kstep.cli.ImportWorkerLaunch
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/** Where the worker JVM comes from (ADR-0082): the java launcher and the class path it runs with. */
data class WorkerLocation(
    val java: String,
    val classpath: String,
    val source: Source,
) {
    enum class Source {
        /** The packaged macOS app: the bundled runtime and the jars of its own worker folder. */
        PACKAGED,

        /** `gradlew run` / tests: the class path the build hands over. */
        GRADLE,

        /** Neither of the above: the desktop's own class path (with a warning). */
        FALLBACK,
    }
}

sealed interface WorkerLocationResult {
    data class Found(
        val location: WorkerLocation,
    ) : WorkerLocationResult

    /** The worker cannot be located; [code] is [DesktopErrorCodes.WORKER_MISSING]. [reason] names files, never paths. */
    data class Broken(
        val code: String,
        val reason: String,
    ) : WorkerLocationResult
}

/**
 * Finds the worker of this process (ADR-0082). In the packaged app (the launcher sets [LIB_DIR_PROPERTY]) the worker is
 * strict and fails closed: a falling back to the desktop's own class path would put Compose and Skiko into the script
 * compiler.
 */
object WorkerLocator {
    const val LIB_DIR_PROPERTY: String = "kstep.desktop.workerLibDir"
    const val CLASSPATH_PROPERTY: String = WORKER_CLASSPATH_PROPERTY
    const val MANIFEST_FILE: String = "worker-classpath.txt"
    const val MAX_ENTRIES: Int = 1024
    const val MAX_MANIFEST_BYTES: Long = 256L * 1024

    /** Pure; the arguments are the seams of the tests. */
    fun resolve(
        property: (String) -> String? = System::getProperty,
        javaHome: Path = Path.of(System.getProperty("java.home")),
        isExecutable: (Path) -> Boolean = Files::isExecutable,
    ): WorkerLocationResult {
        val libDirText = property(LIB_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        if (libDirText != null) return packaged(libDirText, javaHome, isExecutable)
        val java = devJava(javaHome)
        val gradleClasspath = property(CLASSPATH_PROPERTY)?.takeIf { it.isNotBlank() }
        if (gradleClasspath != null) {
            return WorkerLocationResult.Found(WorkerLocation(java, gradleClasspath, WorkerLocation.Source.GRADLE))
        }
        logger.warn { "worker classpath property not set; using the desktop classpath" }
        return WorkerLocationResult.Found(
            WorkerLocation(java, property("java.class.path").orEmpty(), WorkerLocation.Source.FALLBACK),
        )
    }

    /** Resolved once per desktop process. */
    val current: WorkerLocationResult by lazy { resolve() }

    // Dev runs use the launcher of the running JVM exactly as the CLI does; a different java home (tests) is used as given.
    private fun devJava(javaHome: Path): String =
        if (javaHome == Path.of(System.getProperty("java.home"))) {
            ImportWorkerLaunch.javaExecutable()
        } else {
            javaExecutable(javaHome).toString()
        }

    private fun javaExecutable(javaHome: Path): Path = javaHome.resolve("bin").resolve("java")

    private fun broken(reason: String): WorkerLocationResult =
        WorkerLocationResult.Broken(DesktopErrorCodes.WORKER_MISSING, reason)

    private fun packaged(
        libDirText: String,
        javaHome: Path,
        isExecutable: (Path) -> Boolean,
    ): WorkerLocationResult {
        val libDir =
            try {
                Path.of(libDirText).toAbsolutePath().normalize()
            } catch (_: Exception) {
                return broken("the packaged worker folder is not usable; reinstall kSTEP")
            }
        if (!Files.isDirectory(libDir)) return broken("the packaged worker folder is missing; reinstall kSTEP")
        val manifest = libDir.resolve(MANIFEST_FILE)
        val names =
            readManifest(manifest) ?: return broken("the packaged worker is incomplete (no usable $MANIFEST_FILE)")
        if (names.isEmpty() || names.size > MAX_ENTRIES) {
            return broken("the packaged worker is incomplete ($MANIFEST_FILE has an unexpected number of entries)")
        }
        val paths = ArrayList<String>(names.size)
        for (name in names) {
            if (!validName(
                    name,
                )
            ) {
                return broken("the packaged worker is incomplete ($MANIFEST_FILE has an invalid entry)")
            }
            val jar = libDir.resolve(name)
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                return broken("the packaged worker is incomplete (missing $name); reinstall kSTEP")
            }
            paths += jar.toString()
        }
        val java = javaExecutable(javaHome)
        if (!isExecutable(java)) return broken("the bundled runtime has no java launcher; reinstall kSTEP")
        return WorkerLocationResult.Found(
            WorkerLocation(java.toString(), paths.joinToString(File.pathSeparator), WorkerLocation.Source.PACKAGED),
        )
    }

    /** The non-blank lines of the manifest, or `null` if it is not a regular file or too large. */
    private fun readManifest(manifest: Path): List<String>? {
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return null
        val size = Files.size(manifest)
        if (size > MAX_MANIFEST_BYTES) return null
        val text =
            Files
                .newInputStream(
                    manifest,
                ).use { String(it.readNBytes(MAX_MANIFEST_BYTES.toInt() + 1), Charsets.UTF_8) }
        if (text.length > MAX_MANIFEST_BYTES) return null
        return text
            .lineSequence()
            .map { it.trim('\r', ' ') }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun validName(name: String): Boolean =
        name.endsWith(".jar") &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains("..") &&
            name.none { it.isISOControl() }
}

/** The note shown instead of the OCCT install hint when the desktop runs as the packaged JVM-only app (ADR-0082). */
object PackagedHint {
    fun lines(): List<String> =
        listOf(
            "This packaged app is the JVM-only build and contains no native geometry bridge (ADR-0082).",
            "Scripts are compiled and checked; geometry preview, STEP/glTF export and STEP import need a build from source:",
            "./gradlew clean :kstep-desktop:run -Pkstep.occt.require=true (see docs/building.adoc)",
        )

    /** `true` if this process runs from the packaged app. */
    fun active(result: WorkerLocationResult = WorkerLocator.current): Boolean =
        result is WorkerLocationResult.Found && result.location.source == WorkerLocation.Source.PACKAGED
}
