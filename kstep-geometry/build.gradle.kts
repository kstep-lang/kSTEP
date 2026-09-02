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
    // kstep-geometry gets its own (small) test sourceset rather than living entirely in
    // kstep-tests, because the one thing it tests -- OcctNativeLibrary.resolveLibraryPath's
    // override-path validation -- is `internal`, not public. kstep-tests only ever depends on
    // this module's public API (implementation(project(":kstep-geometry"))), so it cannot see
    // that function; this module's own `test` sourceset can, via the Kotlin Gradle plugin's
    // default main/test associated compilation. See OcctNativeLibraryResolveLibraryPathTest.
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------
// OCCT JNI bridge (Linux x86-64 only in this wave -- see docs/adr/ADR-0005-occt-jni-bridge.adoc
// for the full rationale, license findings, and the folge-Wellen that extend platform coverage).
// Compiles src/main/cpp/kstep_occt_bridge.cpp against a *system-installed* OCCT (Ubuntu
// libocct-*-dev packages) with a single, direct g++ invocation -- no cmake, no pkg-config
// (neither ships an OCCT integration on this distro, and a single translation unit doesn't need a
// build-system-within-a-build-system). When the OCCT dev headers are absent (e.g. on a machine
// that hasn't run the apt-get install documented in README's "Building" section),
// compileOcctBridge is SKIPPED, not failed -- the rest of the module (and the whole repo build)
// stays green, and dev.kstep.geometry.OcctKernel reports OcctAvailability.Unavailable at runtime
// instead. See kstep-tests/build.gradle.kts's `kstep.occt.require` property for turning that into
// a hard build failure in environments that must guarantee OCCT is actually present.
// ---------------------------------------------------------------------------------------------

val occtIncludeDir = providers.gradleProperty("kstep.occt.includeDir").orElse("/usr/include/opencascade")
val occtLibDir = providers.gradleProperty("kstep.occt.libDir").orElse("/usr/lib/x86_64-linux-gnu")
val nativeOutputDir = layout.buildDirectory.dir("native/linux-x86-64")
val bridgeSource = layout.projectDirectory.file("src/main/cpp/kstep_occt_bridge.cpp")
val nativeLibraryFileName = "libkstep_occt_bridge.so"

// Plain, eagerly-evaluated configuration-time facts (Strings/Booleans only).
//
// NOTE: despite only capturing plain values, `compileOcctBridge` below is NOT actually
// Configuration-Cache-safe -- verified 2026-09-02 with
// `./gradlew :kstep-geometry:jar --configuration-cache --no-build-cache --rerun-tasks`, which
// fails with "cannot serialize Gradle script object references" for this task's `onlyIf`/
// `doFirst`/`CommandLineArgumentProvider` lambdas. The `onlyIf`/`doFirst`/argument-provider
// lambdas each still close over the enclosing build script *instance* (to reach the vals below),
// not just their values, which is exactly what the CC serializer rejects -- capturing only
// plain-typed values does not by itself avoid that. This was previously (incorrectly) documented
// here as safe, citing kstep-express/build.gradle.kts as a working precedent; that module has the
// identical problem (`generateExpressKotlin`), so it is not actually a counter-example. Neither
// task has been converted to the `object : Action<Task>` pattern from CLAUDE.md's
// "Multi-OS-Pipeline-Patterns" table (or moved out of a custom task entirely, per that table's
// last row) because Configuration Cache is not enabled in gradle.properties -- so this is a
// latent, pre-existing issue affecting two modules, not a regression, and not yet blocking.
val isLinuxX8664 =
    System.getProperty("os.name")?.contains("Linux", ignoreCase = true) == true &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64")
val occtHeadersPresent = File(occtIncludeDir.get(), "Standard_Version.hxx").isFile
val compilerAvailable =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { dir ->
        File(dir, "g++").let { it.isFile && it.canExecute() }
    }
val nativeBuildable = isLinuxX8664 && occtHeadersPresent && compilerAvailable

if (!nativeBuildable) {
    logger.warn(
        "kstep-geometry: the OCCT native bridge will NOT be compiled this run " +
            "(isLinuxX8664=$isLinuxX8664, occtHeadersPresent=$occtHeadersPresent " +
            "[looked in ${occtIncludeDir.get()}], compilerAvailable=$compilerAvailable). " +
            "dev.kstep.geometry.OcctKernel will report OcctAvailability.Unavailable at runtime. " +
            "See README 'Building' for the apt-get command that installs the required OCCT dev packages.",
    )
}

private val javaToolchainService = project.extensions.getByType(JavaToolchainService::class.java)
private val jdkHomeProvider =
    javaToolchainService
        .compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.asFile.absolutePath }

val compileOcctBridge =
    tasks.register<Exec>("compileOcctBridge") {
        group = "native"
        description =
            "Compiles the OCCT JNI bridge (Linux x86-64 only; skipped when OCCT dev headers or g++ are absent)."
        onlyIf { nativeBuildable }
        inputs.file(bridgeSource)
        inputs.property("occtIncludeDir", occtIncludeDir)
        inputs.property("occtLibDir", occtLibDir)
        // Declared as a task input so a toolchain JDK change (a different `-I$jdkHome/include`)
        // correctly invalidates UP-TO-DATE-ness instead of leaving the existing .so compiled
        // against a stale jni.h -- read only inside the CommandLineArgumentProvider lambda below,
        // which Gradle does not otherwise treat as an input on its own.
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
                    "-std=c++17",
                    "-O2",
                    "-Wall",
                    "-Wextra",
                    // Fail the BUILD on an undefined symbol, instead of letting the .so link
                    // successfully and only surfacing UnsatisfiedLinkError much later, at first
                    // native call -- far harder to diagnose in a test run.
                    "-Wl,--no-undefined",
                    "-I$jdkHome/include",
                    "-I$jdkHome/include/linux",
                    "-I${occtIncludeDir.get()}",
                    bridgeSource.asFile.absolutePath,
                    "-L${occtLibDir.get()}",
                    // OCCT 7.8+ renamed the STEP data-exchange toolkits to TKDE*; the old
                    // TKSTEP/TKSTEPBase/TKSTEPAttr names no longer exist as of 7.9.2 (Ubuntu's
                    // packaged version) -- see docs/adr/ADR-0005-occt-jni-bridge.adoc.
                    "-lTKernel",
                    "-lTKMath",
                    "-lTKG2d",
                    "-lTKG3d",
                    "-lTKGeomBase",
                    "-lTKBRep",
                    "-lTKGeomAlgo",
                    "-lTKTopAlgo",
                    "-lTKPrim",
                    // BRepFilletAPI_MakeFillet lives in TKFillet -- verified with
                    // `nm -D --defined-only /usr/lib/x86_64-linux-gnu/libTKFillet.so | grep BRepFilletAPI_MakeFillet`
                    // (82 symbols; zero in TKPrim/TKTopAlgo/TKBRep/TKOffset). BRepPrimAPI_MakePrism is already
                    // covered by TKPrim, BRepBuilderAPI_MakePolygon/MakeFace and BRepCheck_Analyzer by TKTopAlgo --
                    // so TKFillet is the ONLY new toolkit Geometrie Welle 5a needs (see
                    // docs/adr/ADR-0008-occt-feature-operations.adoc).
                    "-lTKFillet",
                    "-lTKXSBase",
                    "-lTKDESTEP",
                    // Ubuntu's g++ defaults to --enable-new-dtags, which makes -Wl,-rpath below
                    // emit DT_RUNPATH instead of DT_RPATH. ld.so does NOT consult DT_RUNPATH when
                    // resolving a shared object's own *transitive* dependencies (only DT_NEEDED
                    // entries of the object that carries the tag) -- so with a non-default
                    // -Pkstep.occt.libDir, the resulting .so links fine (-L finds everything at
                    // link time) but fails to load at runtime with UnsatisfiedLinkError for
                    // transitively-needed OCCT toolkits (e.g. libTKG2d.so, libTKLCAF.so -- pulled
                    // in indirectly via TKGeomBase/TKDESTEP, never referenced by an -l flag here).
                    // --disable-new-dtags makes the linker emit DT_RPATH instead, which IS
                    // consulted for transitive resolution too -- based on documented ld.so
                    // DT_RPATH-vs-DT_RUNPATH semantics. Confirmed on 2026-09-02 (see
                    // docs/adr/ADR-0005-occt-jni-bridge.adoc's "Environment note") that this
                    // flag does make readelf report `(RPATH)` rather than `(RUNPATH)` on the
                    // built .so. What remains unverified is the actual *transitive-resolution
                    // advantage* claimed above for a non-default -Pkstep.occt.libDir: on every
                    // machine this has run on, OCCT's .so files live in the linker's own default
                    // search path (/usr/lib/x86_64-linux-gnu), so a build with DT_RUNPATH instead
                    // would have loaded transitive dependencies from there regardless -- the
                    // RPATH-vs-RUNPATH distinction this comment is actually about has not been
                    // exercised either way yet.
                    "-Wl,--disable-new-dtags",
                    "-Wl,-rpath,${occtLibDir.get()}",
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
    dependsOn(compileOcctBridge)
    from(nativeOutputDir) {
        into("dev/kstep/geometry/native/linux-x86-64")
    }
}
