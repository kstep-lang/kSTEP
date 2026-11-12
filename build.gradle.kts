import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import java.util.zip.ZipFile

plugins {
    // base: root `clean` (removes build/maven-staging) and a root `check` for verifyPublicationCoverage.
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.vanniktech.publish) apply false
}

allprojects {
    group = "dev.kstep"
    version = "0.4.0"
}

// ---- Maven Central publishing (ADR-0043) ----------------------------------------------------
// Allowlist by Project.path: a new module is never uploaded by accident. verifyPublicationCoverage
// fails the build until every subproject is either published or explicitly excluded.
val publishedModules: Map<String, String> =
    mapOf(
        ":kstep-core" to "kSTEP core DSL types, validation results and the generated AP242 entity classes.",
        ":kstep-express" to "ANTLR4 EXPRESS (ISO 10303-11) parser, semantic model and Kotlin code generator.",
        ":kstep-step21" to "STEP Part 21 (ISO 10303-21) reader and writer for the kSTEP AP242 entities.",
        ":kstep-script" to "Kotlin scripting host for *.kstep.kts scripts.",
        ":kstep-shape" to "AP242 shape assignment: joins product structure with B-Rep solids.",
        ":kstep-geometry" to "OpenCascade (OCCT) JNI bridge API for B-Rep geometry; native library not included.",
        ":kstep-constraints" to "PlaneGCS bridge API for 2D geometric constraints; native library not included.",
        ":kstep-sketch" to "Constraint-sketch DSL on top of kstep-geometry and kstep-constraints.",
        ":kstep-render" to "Headless mesh projection and SVG/PNG/text/glTF rendering.",
        ":kstep-preview" to "Shared headless preview pipeline behind kstep render and kstep asciidoc.",
        ":kstep-mcp" to "MCP server exposing the kSTEP entity builders and Part 21 export as LLM tools.",
        ":kstep-docs:kstep-asciidoc" to "Asciidoctor pre-processing integration for kSTEP diagrams and previews.",
    )

// Application, Compose Desktop app, integration tests, namespace parent.
val nonPublishedModules: Set<String> = setOf(":kstep-cli", ":kstep-viewer", ":kstep-tests", ":kstep-docs")

val kstepPublishingConfiguredMarker = "kstep.publishingConfigured"
val mavenStagingDir = layout.buildDirectory.dir("maven-staging")
val legalFiles = files("LICENSE", "NOTICE")
val publishedArtifactsFile = file("scripts/release/published-artifacts.txt")
val nativeExclude = providers.gradleProperty("kstep.native.exclude").map { it == "true" }.orElse(false)

// Fail-closed guards for a real upload (configuration time; messages name properties, never values).
val uploadTasks =
    setOf(
        "publishToMavenCentral",
        "publishAndReleaseToMavenCentral",
        "publishAllPublicationsToMavenCentralRepository",
    )
val uploadRequested = gradle.startParameter.taskNames.any { it.substringAfterLast(':') in uploadTasks }
if (uploadRequested) {
    if (!providers.gradleProperty("signingInMemoryKey").isPresent) {
        throw GradleException("Refusing a Maven Central upload without signingInMemoryKey (unsigned upload).")
    }
    if (!nativeExclude.get()) {
        throw GradleException("Maven Central upload requires -Pkstep.native.exclude=true.")
    }
}

fun Project.configureKstepPublishing(moduleDescription: String) {
    check(!extra.has(kstepPublishingConfiguredMarker)) {
        "kSTEP publishing is already configured for $path; configureKstepPublishing must run once per module."
    }
    extra[kstepPublishingConfiguredMarker] = true

    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        // Only sign when a key is supplied; otherwise the sign tasks would exist without a signer and
        // break the credential-free local dry run (same lesson as kUML).
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }
        coordinates("dev.kstep", project.name, project.version.toString())
        configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
        pom {
            name.set(project.name)
            description.set(moduleDescription)
            url.set("https://kstep.dev")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("ibetchvaia")
                    name.set("Irakli Betchvaia")
                    email.set("irakli@betchvaia.de")
                }
            }
            scm {
                url.set("https://github.com/kstep-lang/kSTEP")
                connection.set("scm:git:https://github.com/kstep-lang/kSTEP.git")
                developerConnection.set("scm:git:ssh://git@github.com/kstep-lang/kSTEP.git")
            }
        }
    }

    // Local verification repository: writes checksums (and .asc when signing) and leaves ~/.m2 alone.
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "buildStaging"
                url = uri(mavenStagingDir)
            }
        }
    }

    // Apache-2.0 section 4(d): LICENSE and NOTICE travel with every jar (main, sources, javadoc).
    // vanniktech's empty javadoc jar is a plain Zip subclass (no metaInf {} spec), hence into("META-INF").
    tasks.withType<Jar>().configureEach {
        metaInf { from(legalFiles) }
    }
    tasks.withType<Zip>().matching { it !is Jar && it.name.endsWith("Jar", ignoreCase = true) }.configureEach {
        into("META-INF") { from(legalFiles) }
    }

    // Fail closed: no publish task may run while any jar of this module carries a native library.
    val jarFiles =
        provider {
            tasks.withType<Zip>().filter { it.name in setOf("jar", "sourcesJar", "javadocJar", "emptyJavadocJar") }
        }
    val verifyNoNativeLibraries =
        tasks.register("verifyNoNativeLibraries") {
            group = "verification"
            description = "Fails if a jar of this module contains a native library (see -Pkstep.native.exclude)."
            dependsOn(jarFiles)
            val jarPaths = jarFiles.map { jars -> jars.map { it.archiveFile.get().asFile } }
            val moduleName = project.name
            doLast(
                object : Action<Task> {
                    override fun execute(t: Task) {
                        val nativeFile = Regex("(?i).*\\.(so|dylib|dll|jnilib)$")
                        val nativeDir = Regex("^dev/kstep/[^/]+/native/.+")
                        jarPaths.get().filter { it.isFile }.forEach { jar ->
                            ZipFile(jar).use { zip ->
                                zip.entries().asSequence().forEach { e ->
                                    val bad =
                                        !e.isDirectory &&
                                            (nativeFile.matches(e.name) || nativeDir.matches(e.name))
                                    if (bad) {
                                        throw GradleException(
                                            "$moduleName: ${jar.name} contains native entry '${e.name}'; " +
                                                "publish with -Pkstep.native.exclude=true.",
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    tasks.withType<AbstractPublishToMaven>().configureEach {
        dependsOn(verifyNoNativeLibraries)
    }
}

subprojects {
    val moduleDescription = publishedModules[path] ?: return@subprojects
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { configureKstepPublishing(moduleDescription) }
}

// Every subproject is published or explicitly excluded; the shell scripts' artifact list matches.
val verifyPublicationCoverage =
    tasks.register("verifyPublicationCoverage") {
        group = "verification"
        description = "Fails when a subproject is neither in publishedModules nor in nonPublishedModules."
        val actualPaths = provider { subprojects.map { it.path }.toSet() }
        val published = publishedModules.keys.toSet()
        val excluded = nonPublishedModules
        val listFile = publishedArtifactsFile
        doLast(
            object : Action<Task> {
                override fun execute(t: Task) {
                    val overlap = published intersect excluded
                    check(overlap.isEmpty()) { "Modules both published and excluded: $overlap" }
                    val expected = published + excluded
                    val actual = actualPaths.get()
                    val unknown = actual - expected
                    check(unknown.isEmpty()) {
                        "New module(s) $unknown: add to publishedModules or nonPublishedModules in build.gradle.kts."
                    }
                    val missing = expected - actual
                    check(missing.isEmpty()) { "Listed module(s) no longer exist: $missing" }
                    val leaves = published.map { it.substringAfterLast(':') }
                    check(leaves.toSet().size == leaves.size) { "Duplicate leaf names: $leaves" }
                    val listed =
                        listFile
                            .readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                    check(listed.toSet() == leaves.toSet() && listed.size == leaves.size) {
                        "scripts/release/published-artifacts.txt $listed does not match publishedModules $leaves"
                    }
                }
            },
        )
    }
tasks.named("check") { dependsOn(verifyPublicationCoverage) }
