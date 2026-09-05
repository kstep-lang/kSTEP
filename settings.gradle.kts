pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kSTEP"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform 1.11.1 (kstep-viewer, Viewer-Welle 1) pulls real androidx.*
        // artifacts (androidx.compose.runtime:runtime-saveable, androidx.lifecycle:
        // lifecycle-common/-runtime, androidx.savedstate:savedstate-compose) that do NOT exist
        // on Maven Central -- verified 2026-09-02: with only mavenCentral() above,
        // kstep-viewer's runtimeClasspath resolution fails hard on those coordinates. Scoped to
        // androidx.* only, so this repository does not become a second, unscoped artifact
        // source for every module in this build -- same content{} pattern kUML uses for its own
        // third-party repositories. See docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc.
        google {
            content {
                includeGroupByRegex("androidx\\..*")
            }
        }
    }
}

include(
    "kstep-core",
    "kstep-express",
    "kstep-step21",
    "kstep-script",
    "kstep-cli",
    "kstep-mcp",
    "kstep-geometry",
    "kstep-constraints",
    "kstep-shape",
    "kstep-render",
    // Shared preview pipeline extracted from kstep-cli's RenderCommand.kt so `kstep render`
    // and `kstep asciidoc` share ONE implementation of ADR-0011's Container-Regel/
    // Pflicht-Fallback instead of two copies drifting apart -- see ADR-0019.
    "kstep-preview",
    "kstep-viewer",
    // Grouping directory mirroring kuml-dev/kUML's own kuml-docs/ layout. `:kstep-docs`
    // itself has no build file -- it is a pure namespace project, not a Kotlin module.
    "kstep-docs:kstep-asciidoc",
    "kstep-tests",
)
