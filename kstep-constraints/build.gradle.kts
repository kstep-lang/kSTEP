import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.logging.jvm)
    // kstep-constraints gets its own (small) test sourceset rather than living entirely in
    // kstep-tests, because the one thing it tests -- PlaneGcsNativeLibrary.resolveLibraryPath's
    // override-path validation -- is `internal`, not public. kstep-tests only ever depends on this
    // module's public API (implementation(project(":kstep-constraints"))), so it cannot see that
    // function; this module's own `test` sourceset can, via the Kotlin Gradle plugin's default
    // main/test associated compilation. See PlaneGcsNativeLibraryResolveLibraryPathTest -- exactly
    // the same reasoning as kstep-geometry/build.gradle.kts's identical comment for OcctNativeLibrary.
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------
// PlaneGCS JNI bridge (Linux x86-64 only in this wave -- see
// docs/adr/ADR-0006-planegcs-constraint-bridge.adoc for the full rationale, license findings, and
// the folge-Wellen that extend platform coverage). Unlike kstep-geometry's OCCT bridge, this does
// NOT link against a system-installed library: the PlaneGCS solver sources are vendored directly
// under src/main/cpp/third_party/planegcs/ (see that directory's PROVENANCE.adoc) and compiled
// straight into the shared object below, together with this module's own JNI shim
// (src/main/cpp/kstep_planegcs_bridge.cpp) -- a single, direct g++ invocation, no cmake, no
// pkg-config, exactly like kstep-geometry's compileOcctBridge. When the Eigen/Boost dev headers or
// g++ are absent (e.g. on a machine that hasn't run the apt-get install documented in README's
// "Building" section), compilePlaneGcsBridge is SKIPPED, not failed -- the rest of the module (and
// the whole repo build) stays green, and dev.kstep.constraints.PlaneGcsSolver reports
// PlaneGcsAvailability.Unavailable at runtime instead. See kstep-tests/build.gradle.kts's
// `kstep.planegcs.require` property for turning that into a hard build failure in environments that
// must guarantee the bridge is actually present.
// ---------------------------------------------------------------------------------------------

// The exact upstream PlaneGCS commit vendored under src/main/cpp/third_party/planegcs/ -- see that
// directory's PROVENANCE.adoc. Baked into the built library as KSTEP_PLANEGCS_COMMIT (see
// kstep_planegcs_bridge.cpp's nativePlaneGcsSourceCommit()) so a loaded .so can be checked against
// this documented provenance at runtime -- dev.kstep.tests.PlaneGcsBridgeSmokeTest does exactly
// that.
val planeGcsCommit = "ee9b156da9827a91a56a888a53520f63d5cffaa6"

val eigenIncludeDir = providers.gradleProperty("kstep.eigen.includeDir").orElse("/usr/include/eigen3")
val boostIncludeDir = providers.gradleProperty("kstep.boost.includeDir").orElse("/usr/include")
val nativeOutputDir = layout.buildDirectory.dir("native/linux-x86-64")
val cppSourceDir = layout.projectDirectory.dir("src/main/cpp")
val thirdPartyDir = cppSourceDir.dir("third_party/planegcs")
val bridgeSource = cppSourceDir.file("kstep_planegcs_bridge.cpp")
val nativeLibraryFileName = "libkstep_planegcs_bridge.so"

// Plain, eagerly-evaluated configuration-time facts (Strings/Booleans only) -- same latent
// Configuration-Cache incompatibility as kstep-geometry's compileOcctBridge (see that build file's
// long comment on this point, added 2026-09-02): capturing only plain-typed values here does NOT by
// itself make the onlyIf/doFirst/CommandLineArgumentProvider lambdas below CC-safe, because they
// still close over this build script *instance* to reach these vals. Configuration Cache is not
// enabled in gradle.properties, so this is a known, pre-existing, non-blocking issue shared with
// kstep-geometry and kstep-express -- not fixed in this wave (see CLAUDE.md's "Multi-OS-Pipeline-
// Patterns" table for the eventual fix pattern, `object : Action<Task>`).
val isLinuxX8664 =
    System.getProperty("os.name")?.contains("Linux", ignoreCase = true) == true &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64")
val eigenPresent = File(eigenIncludeDir.get(), "Eigen/QR").isFile
val boostPresent = File(boostIncludeDir.get(), "boost/graph/adjacency_list.hpp").isFile
val compilerAvailable =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { dir ->
        File(dir, "g++").let { it.isFile && it.canExecute() }
    }
val nativeBuildable = isLinuxX8664 && eigenPresent && boostPresent && compilerAvailable

if (!nativeBuildable) {
    logger.warn(
        "kstep-constraints: the PlaneGCS native bridge will NOT be compiled this run " +
            "(isLinuxX8664=$isLinuxX8664, eigenPresent=$eigenPresent [looked in ${eigenIncludeDir.get()}], " +
            "boostPresent=$boostPresent [looked in ${boostIncludeDir.get()}], compilerAvailable=$compilerAvailable). " +
            "dev.kstep.constraints.PlaneGcsSolver will report PlaneGcsAvailability.Unavailable at runtime. " +
            "See README 'Building' for the apt-get command that installs the required Eigen/Boost dev packages.",
    )
}

private val javaToolchainService = project.extensions.getByType(JavaToolchainService::class.java)
private val jdkHomeProvider =
    javaToolchainService
        .compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.asFile.absolutePath }

val compilePlaneGcsBridge =
    tasks.register<Exec>("compilePlaneGcsBridge") {
        group = "native"
        description =
            "Compiles the vendored PlaneGCS sources plus this module's JNI shim into one shared object " +
            "(Linux x86-64 only; skipped when Eigen/Boost dev headers or g++ are absent)."
        onlyIf { nativeBuildable }
        inputs.dir(cppSourceDir)
        inputs.property("eigenIncludeDir", eigenIncludeDir)
        inputs.property("boostIncludeDir", boostIncludeDir)
        inputs.property("planeGcsCommit", planeGcsCommit)
        // Declared as a task input so a toolchain JDK change (a different `-I$jdkHome/include`)
        // correctly invalidates UP-TO-DATE-ness instead of leaving the existing .so compiled
        // against a stale jni.h -- read only inside the CommandLineArgumentProvider lambda below,
        // which Gradle does not otherwise treat as an input on its own. Mirrors
        // kstep-geometry/build.gradle.kts's compileOcctBridge exactly.
        inputs.property("jdkHome", jdkHomeProvider)
        outputs.dir(nativeOutputDir)
        doFirst {
            nativeOutputDir.get().asFile.mkdirs()
        }
        executable = "g++"
        argumentProviders.add(
            CommandLineArgumentProvider {
                val jdkHome = jdkHomeProvider.get()
                listOf(
                    "-shared",
                    "-fPIC",
                    // PlaneGCS uses <numbers> (third_party/planegcs/GCS.cpp's own includes) --
                    // requires C++20, unlike kstep-geometry's OCCT bridge (-std=c++17). Upstream's
                    // own CMakeLists.txt sets CMAKE_CXX_STANDARD 20 for the same reason.
                    "-std=c++20",
                    "-O2",
                    // No -Wall/-Wextra over the vendored third-party sources: kSTEP does not want
                    // upstream PlaneGCS's own compiler warnings to become this module's signal. If
                    // warnings for kSTEP's OWN shim code (kstep_planegcs_bridge.cpp, Console.h) are
                    // ever wanted, that would need a separate compile step per translation unit
                    // instead of this single-invocation build -- not needed for this wave.
                    "-Wno-deprecated",
                    // Fail the BUILD on an undefined symbol, instead of letting the .so link
                    // successfully and only surfacing UnsatisfiedLinkError much later, at first
                    // native call -- far harder to diagnose in a test run. Mirrors
                    // kstep-geometry/build.gradle.kts.
                    "-Wl,--no-undefined",
                    // KSTEP_PLANEGCS_COMMIT: see nativePlaneGcsSourceCommit() in
                    // kstep_planegcs_bridge.cpp and this file's planeGcsCommit val above.
                    "-DKSTEP_PLANEGCS_COMMIT=\"$planeGcsCommit\"",
                    "-I$jdkHome/include",
                    "-I$jdkHome/include/linux",
                    // This module's own src/main/cpp MUST come before third_party/planegcs's
                    // include path below: third_party/planegcs/GCS.cpp:99 does
                    // `#include <Console.h>` (angle brackets), and this ordering is what makes that
                    // resolve to kSTEP's own native (non-Emscripten) Console.h shim instead of
                    // failing to find one at all (headers/Console.h was deliberately not vendored --
                    // see third_party/planegcs/PROVENANCE.adoc and Console.h's own header comment).
                    "-I${cppSourceDir.asFile.absolutePath}",
                    "-I${thirdPartyDir.asFile.absolutePath}",
                    "-I${thirdPartyDir.dir("headers").asFile.absolutePath}",
                    "-I${eigenIncludeDir.get()}",
                    "-I${boostIncludeDir.get()}",
                    thirdPartyDir.file("GCS.cpp").asFile.absolutePath,
                    thirdPartyDir.file("Geo.cpp").asFile.absolutePath,
                    thirdPartyDir.file("Constraints.cpp").asFile.absolutePath,
                    thirdPartyDir.file("SubSystem.cpp").asFile.absolutePath,
                    thirdPartyDir.file("qp_eq.cpp").asFile.absolutePath,
                    bridgeSource.asFile.absolutePath,
                    // No -L/-l flags and no -rpath at all: Eigen and Boost are both used here in a
                    // strictly header-only capacity (verified against every #include across the
                    // vendored sources -- Eigen/Core, Eigen/Dense, Eigen/QR, Eigen/Sparse,
                    // Eigen/OrderingMethods; boost/graph/adjacency_list.hpp (vendored, angle-bracket
                    // include resolves via the third_party/planegcs/headers -I above),
                    // boost/graph/connected_components.hpp, boost/graph/graph_concepts.hpp,
                    // boost/math/constants/constants.hpp), so nothing is linked against an external
                    // shared library at all -- a genuine advantage over kstep-geometry's OCCT bridge,
                    // which needs -L/-lTK*/-Wl,-rpath for the OCCT toolkit .so files it links
                    // against.
                    //
                    // NEVER add -D_DEBUG_TO_FILE or -D_GCS_EXTRACT_SOLVER_SUBSYSTEM_ here: both
                    // macros make the vendored PlaneGCS source write a file (GCS_debug.txt /
                    // subsystemfile.txt respectively) into the process's current working directory
                    // -- verified at third_party/planegcs/GCS.cpp:283 and :2577. See
                    // docs/adr/ADR-0006-planegcs-constraint-bridge.adoc's Security section.
                    "-o",
                    nativeOutputDir
                        .get()
                        .file(nativeLibraryFileName)
                        .asFile.absolutePath,
                )
            },
        )
    }

tasks.named<ProcessResources>("processResources") {
    dependsOn(compilePlaneGcsBridge)
    from(nativeOutputDir) {
        into("dev/kstep/constraints/native/linux-x86-64")
    }
}
