package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Where the worker comes from in the packaged app and in dev runs (ADR-0082). Temp folders, no real jars. */
class WorkerLocatorTest :
    StringSpec({
        val javaHome = Path.of("/bundle/runtime/Contents/Home")
        val executable: (Path) -> Boolean = { true }

        fun props(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

        /** A worker folder with the given jars and manifest lines. */
        fun workerDir(
            base: Path,
            jars: List<String>,
            manifest: List<String>? = jars,
        ): Path {
            val dir = Files.createDirectories(base.resolve("kstep-worker"))
            jars.forEach { Files.writeString(dir.resolve(it), "jar") }
            if (manifest !=
                null
            ) {
                Files.writeString(dir.resolve(WorkerLocator.MANIFEST_FILE), manifest.joinToString("\n"))
            }
            return dir
        }

        fun packaged(
            dir: Path,
            home: Path = javaHome,
            ok: (Path) -> Boolean = executable,
        ): WorkerLocationResult =
            WorkerLocator.resolve(props(WorkerLocator.LIB_DIR_PROPERTY to dir.toString()), home, ok)

        fun broken(r: WorkerLocationResult): WorkerLocationResult.Broken {
            val b = r.shouldBeInstanceOf<WorkerLocationResult.Broken>()
            b.code shouldBe "KSTEP-D-038"
            return b
        }

        fun withDir(body: (Path) -> Unit) {
            val base = TestSupport.newDir()
            try {
                body(base)
            } finally {
                base.toFile().deleteRecursively()
            }
        }

        "a complete worker folder is found in manifest order with the bundled java".config(timeout = 60.seconds) {
            withDir { base ->
                val dir = workerDir(base, listOf("b.jar", "a.jar", "c.jar"))
                val found = packaged(dir).shouldBeInstanceOf<WorkerLocationResult.Found>().location
                found.source shouldBe WorkerLocation.Source.PACKAGED
                found.java shouldBe javaHome.resolve("bin").resolve("java").toString()
                found.classpath shouldBe
                    listOf("b.jar", "a.jar", "c.jar").joinToString(File.pathSeparator) { dir.resolve(it).toString() }
            }
        }

        "the APPDIR/../kstep-worker form is normalised".config(timeout = 60.seconds) {
            withDir { base ->
                workerDir(base, listOf("a.jar"))
                val viaDots = base.resolve("app").resolve("..").resolve("kstep-worker")
                Files.createDirectories(base.resolve("app"))
                val found = packaged(viaDots).shouldBeInstanceOf<WorkerLocationResult.Found>().location
                found.classpath shouldBe base.resolve("kstep-worker").resolve("a.jar").toString()
            }
        }

        "a missing manifest is D-038 and names the manifest".config(timeout = 60.seconds) {
            withDir { base ->
                val dir = workerDir(base, listOf("a.jar"), manifest = null)
                broken(packaged(dir)).reason shouldContain WorkerLocator.MANIFEST_FILE
            }
        }

        "a missing jar is D-038 and the text names only the file".config(timeout = 60.seconds) {
            withDir { base ->
                val dir = workerDir(base, listOf("a.jar"), manifest = listOf("a.jar", "gone.jar"))
                val reason = broken(packaged(dir)).reason
                reason shouldContain "gone.jar"
                reason shouldNotContain base.toString()
            }
        }

        "invalid manifest entries are D-038".config(timeout = 60.seconds) {
            listOf("a/b.jar", "../x.jar", "x.txt", "a\\b.jar", "a\u0001.jar", "..jar.jar").forEach { bad ->
                withDir { base ->
                    val dir = workerDir(base, emptyList(), manifest = listOf(bad))
                    broken(packaged(dir))
                }
            }
        }

        "an empty manifest, too many entries and an oversized manifest are D-038".config(timeout = 60.seconds) {
            withDir { base ->
                broken(packaged(workerDir(base, emptyList(), manifest = emptyList())))
            }
            withDir { base ->
                val many = List(WorkerLocator.MAX_ENTRIES + 1) { "j$it.jar" }
                broken(packaged(workerDir(base, emptyList(), manifest = many)))
            }
            withDir { base ->
                val dir = workerDir(base, listOf("a.jar"))
                Files.writeString(
                    dir.resolve(WorkerLocator.MANIFEST_FILE),
                    "a.jar\n" + "x".repeat((WorkerLocator.MAX_MANIFEST_BYTES + 1).toInt()),
                )
                broken(packaged(dir))
            }
        }

        "a symbolic link or a directory as an entry is D-038".config(timeout = 60.seconds) {
            withDir { base ->
                val dir = workerDir(base, listOf("real.jar"), manifest = listOf("dir.jar"))
                Files.createDirectory(dir.resolve("dir.jar"))
                broken(packaged(dir))
            }
            withDir { base ->
                val dir = workerDir(base, listOf("real.jar"), manifest = listOf("link.jar"))
                val linked =
                    try {
                        Files.createSymbolicLink(dir.resolve("link.jar"), dir.resolve("real.jar"))
                        true
                    } catch (_: Exception) {
                        false // no symlink right (Windows without developer mode): the case is not testable there
                    }
                if (linked) broken(packaged(dir))
            }
        }

        "a bundled runtime without a java launcher is D-038".config(timeout = 60.seconds) {
            withDir { base ->
                val dir = workerDir(base, listOf("a.jar"))
                broken(packaged(dir, ok = { false })).reason shouldContain "no java launcher"
            }
        }

        "the packaged folder wins over the Gradle class path".config(timeout = 60.seconds) {
            withDir { base ->
                val dir = workerDir(base, listOf("a.jar"))
                val result =
                    WorkerLocator.resolve(
                        props(
                            WorkerLocator.LIB_DIR_PROPERTY to dir.toString(),
                            WorkerLocator.CLASSPATH_PROPERTY to "/dev/x.jar",
                        ),
                        javaHome,
                        executable,
                    )
                result.shouldBeInstanceOf<WorkerLocationResult.Found>().location.source shouldBe
                    WorkerLocation.Source.PACKAGED
            }
        }

        "only the Gradle class path gives GRADLE, nothing gives FALLBACK, blank counts as unset".config(
            timeout = 60.seconds,
        ) {
            val gradle = WorkerLocator.resolve(props(WorkerLocator.CLASSPATH_PROPERTY to "/dev/x.jar"))
            gradle.shouldBeInstanceOf<WorkerLocationResult.Found>().location.let {
                it.source shouldBe WorkerLocation.Source.GRADLE
                it.classpath shouldBe "/dev/x.jar"
            }
            val fallback = WorkerLocator.resolve(props("java.class.path" to "/own.jar"))
            fallback.shouldBeInstanceOf<WorkerLocationResult.Found>().location.let {
                it.source shouldBe WorkerLocation.Source.FALLBACK
                it.classpath shouldBe "/own.jar"
            }
            val blank =
                WorkerLocator.resolve(
                    props(
                        WorkerLocator.LIB_DIR_PROPERTY to " ",
                        WorkerLocator.CLASSPATH_PROPERTY to "",
                        "java.class.path" to "/own.jar",
                    ),
                )
            blank.shouldBeInstanceOf<WorkerLocationResult.Found>().location.source shouldBe
                WorkerLocation.Source.FALLBACK
        }

        "an unusable packaged folder never falls back to another class path".config(timeout = 60.seconds) {
            withDir { base ->
                val result =
                    WorkerLocator.resolve(
                        props(
                            WorkerLocator.LIB_DIR_PROPERTY to base.resolve("nothing").toString(),
                            WorkerLocator.CLASSPATH_PROPERTY to "/dev/x.jar",
                            "java.class.path" to "/own.jar",
                        ),
                        javaHome,
                        executable,
                    )
                broken(result)
            }
        }
    })
