package dev.kstep.constraints.planegcs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pure, bridge-free tests for the platform whitelist in [PlaneGcsNativeLibrary]: which `os.name` /
 * `os.arch` pairs are mapped to a bundled-library platform id, and what resource path / file name
 * each id yields. Never touches [PlaneGcsNativeLibrary.availability].
 */
class PlaneGcsNativeLibraryPlatformTest :
    StringSpec({
        "Linux amd64 and x86_64 map to linux-x86-64" {
            PlaneGcsNativeLibrary.detectPlatform("Linux", "amd64") shouldBe "linux-x86-64"
            PlaneGcsNativeLibrary.detectPlatform("Linux", "x86_64") shouldBe "linux-x86-64"
        }

        "macOS aarch64 and arm64 map to macos-aarch64" {
            PlaneGcsNativeLibrary.detectPlatform("Mac OS X", "aarch64") shouldBe "macos-aarch64"
            PlaneGcsNativeLibrary.detectPlatform("Mac OS X", "arm64") shouldBe "macos-aarch64"
        }

        "unsupported combinations map to null" {
            PlaneGcsNativeLibrary.detectPlatform("Mac OS X", "x86_64") shouldBe null
            PlaneGcsNativeLibrary.detectPlatform("Windows 11", "amd64") shouldBe null
            PlaneGcsNativeLibrary.detectPlatform("Linux", "aarch64") shouldBe null
            PlaneGcsNativeLibrary.detectPlatform(null, null) shouldBe null
        }

        "the Linux resource path is byte-identical to the historic constant" {
            PlaneGcsNativeLibrary.resourcePath("linux-x86-64") shouldBe
                "/dev/kstep/constraints/native/linux-x86-64/libkstep_planegcs_bridge.so"
        }

        "the macOS resource path names a dylib" {
            PlaneGcsNativeLibrary.resourcePath("macos-aarch64") shouldBe
                "/dev/kstep/constraints/native/macos-aarch64/libkstep_planegcs_bridge.dylib"
            PlaneGcsNativeLibrary.libraryFileName("macos-aarch64") shouldBe "libkstep_planegcs_bridge.dylib"
        }

        "a non-whitelisted platform id is rejected, never turned into a path" {
            shouldThrow<IllegalArgumentException> { PlaneGcsNativeLibrary.resourcePath("../../etc") }
        }
    })
