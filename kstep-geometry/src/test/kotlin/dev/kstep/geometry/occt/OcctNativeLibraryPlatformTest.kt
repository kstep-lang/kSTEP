package dev.kstep.geometry.occt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pure, OCCT-free tests for the platform whitelist in [OcctNativeLibrary]: which `os.name` /
 * `os.arch` pairs are mapped to a bundled-library platform id, and what resource path / file name
 * each id yields. Never touches [OcctNativeLibrary.availability].
 */
class OcctNativeLibraryPlatformTest :
    StringSpec({
        "Linux amd64 and x86_64 map to linux-x86-64" {
            OcctNativeLibrary.detectPlatform("Linux", "amd64") shouldBe "linux-x86-64"
            OcctNativeLibrary.detectPlatform("Linux", "x86_64") shouldBe "linux-x86-64"
        }

        "macOS aarch64 and arm64 map to macos-aarch64" {
            OcctNativeLibrary.detectPlatform("Mac OS X", "aarch64") shouldBe "macos-aarch64"
            OcctNativeLibrary.detectPlatform("Mac OS X", "arm64") shouldBe "macos-aarch64"
        }

        "unsupported combinations map to null" {
            OcctNativeLibrary.detectPlatform("Mac OS X", "x86_64") shouldBe null
            OcctNativeLibrary.detectPlatform("Windows 11", "amd64") shouldBe null
            OcctNativeLibrary.detectPlatform("Linux", "aarch64") shouldBe null
            OcctNativeLibrary.detectPlatform(null, null) shouldBe null
        }

        "the Linux resource path is byte-identical to the historic constant" {
            OcctNativeLibrary.resourcePath("linux-x86-64") shouldBe
                "/dev/kstep/geometry/native/linux-x86-64/libkstep_occt_bridge.so"
        }

        "the macOS resource path names a dylib" {
            OcctNativeLibrary.resourcePath("macos-aarch64") shouldBe
                "/dev/kstep/geometry/native/macos-aarch64/libkstep_occt_bridge.dylib"
            OcctNativeLibrary.libraryFileName("macos-aarch64") shouldBe "libkstep_occt_bridge.dylib"
        }

        "a non-whitelisted platform id is rejected, never turned into a path" {
            shouldThrow<IllegalArgumentException> { OcctNativeLibrary.resourcePath("../../etc") }
        }
    })
