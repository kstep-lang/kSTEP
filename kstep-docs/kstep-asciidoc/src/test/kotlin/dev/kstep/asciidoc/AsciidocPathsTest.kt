package dev.kstep.asciidoc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class AsciidocPathsTest :
    StringSpec({
        // C1
        "validateTargetName rejects every unsafe value and accepts safe ones" {
            val unsafe =
                listOf(
                    "../x",
                    "/abs",
                    "a/b",
                    "a\\b",
                    ".",
                    "..",
                    "",
                    "a b",
                    "x".repeat(101),
                )
            unsafe.forEach { AsciidocPaths.validateTargetName(it) shouldBe null }

            AsciidocPaths.validateTargetName("bracket-1") shouldBe "bracket-1"
            AsciidocPaths.validateTargetName("a_b.c") shouldBe "a_b.c"
        }

        // C2
        "resolveScriptPath rejects traversal outside the root and a symlink that escapes it" {
            val root = Files.createTempDirectory("kstep-paths-test-root")
            val adocDir = Files.createDirectories(root.resolve("docs"))
            val outsideDir = Files.createTempDirectory("kstep-paths-test-outside")
            val outsideFile = Files.writeString(outsideDir.resolve("secret.kstep.kts"), "secret")

            // Lexical traversal
            AsciidocPaths.resolveScriptPath(adocDir, root, "../../../etc/passwd") shouldBe null

            // A symlink INSIDE the tree pointing OUTSIDE it
            val linkPath = adocDir.resolve("escape-link.kstep.kts")
            try {
                Files.createSymbolicLink(linkPath, outsideFile)
                AsciidocPaths.resolveScriptPath(adocDir, root, "escape-link.kstep.kts") shouldBe null
            } catch (_: UnsupportedOperationException) {
                // Symlinks unsupported on this filesystem -- nothing to assert.
            }

            // A legitimate sibling path resolves fine
            Files.writeString(adocDir.resolve("sibling.kstep.kts"), "val x = 1")
            val resolved = AsciidocPaths.resolveScriptPath(adocDir, root, "sibling.kstep.kts")
            resolved shouldBe adocDir.resolve("sibling.kstep.kts").normalize()
        }

        // C3/C4/C5 used to exercise `AsciidocPaths.resolveImagesDir`/`relativeForwardSlash`,
        // which were removed as dead code (no production caller -- the real write-time
        // containment check is `OutputRoot.resolveTarget`, already covered by
        // `AsciidocProcessorTest` D10/D12/D15/D16). See docs/adr/ADR-0019-kstep-asciidoc.adoc's
        // Security table.

        // C6
        "realPathOfNearestExistingAncestor never throws when it climbs past an unreadable directory" {
            val root = Files.createTempDirectory("kstep-paths-test-unreadable")
            val locked = root.resolve("locked").also { Files.createDirectories(it) }
            val candidate = locked.resolve("nested").resolve("leaf.svg")
            val permissions = Files.getPosixFilePermissions(locked)
            try {
                Files.setPosixFilePermissions(locked, emptySet())
                // `candidate` and its parent ("locked/nested") do not exist -- and, with `locked`
                // stripped of read/execute, `Files.exists` cannot even confirm they don't exist by
                // entering `locked` to check; it correctly reports `false` regardless (never
                // throws). The walk climbs to `locked` itself, which DOES exist and -- resolving
                // ITS OWN name only requires search permission on `locked`'s PARENT, not on
                // `locked`'s own (removed) permissions -- resolves to a real path with no
                // exception either. The missing "nested/leaf.svg" suffix is reattached lexically
                // on top of it. This pins the actual contract (never throws, regardless of WHERE
                // the unreadable directory sits relative to the nearest existing ancestor) rather
                // than assuming naive POSIX permission semantics -- see AsciidocProcessorTest D22
                // for the sibling case where an unreadable directory genuinely must be entered
                // (a tree walk that LISTS it) and does throw, which `AsciidocProcessor` catches at
                // that different call site instead.
                AsciidocPaths.realPathOfNearestExistingAncestor(candidate) shouldBe
                    locked.toRealPath().resolve("nested").resolve("leaf.svg")
            } finally {
                Files.setPosixFilePermissions(locked, permissions)
            }
        }
    })
