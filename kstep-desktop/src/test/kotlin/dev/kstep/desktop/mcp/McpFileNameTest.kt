package dev.kstep.desktop.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class McpFileNameTest :
    StringSpec({
        fun ok(s: String) = McpSafeFileName.parse(s)?.value

        "plain names pass, a known export extension is removed" {
            ok("part") shouldBe "part"
            ok("My Part-2_v1.5") shouldBe "My Part-2_v1.5"
            ok("part.STEP") shouldBe "part"
            ok("part.glb") shouldBe "part"
            ok("a.3mf") shouldBe "a"
        }

        "path parts and traversal are refused" {
            for (bad in listOf(
                "../x",
                "a/b",
                "a\\b",
                "..",
                ".",
                "/etc/x",
                "C:\\x",
                "a:b",
                ".hidden",
                "trail.",
                " lead",
                "trail ",
            )) {
                ok(bad) shouldBe null
            }
        }

        "control, format and bidi characters are refused" {
            for (bad in listOf("a\u0000b", "a\u202Eb", "a\u200Bb", "a\u0007b", "a\nb", "a\tb")) ok(bad) shouldBe null
        }

        "the length is bounded at 100 code points" {
            ok("a".repeat(100)) shouldBe "a".repeat(100)
            ok("a".repeat(101)) shouldBe null
            ok("") shouldBe null
        }

        "Windows device names are refused in any case and with an extension" {
            for (bad in listOf("CON", "con", "con.step", "Nul", "PRN", "AUX", "COM1", "lpt9", "LPT9.stl", "com5.x")) {
                ok(bad) shouldBe null
            }
            ok("CONSOLE") shouldBe "CONSOLE"
            ok("COM10") shouldBe "COM10"
        }

        "a decomposed letter is normalised to the same name" {
            ok("caf\u0065\u0301") shouldBe ok("caf\u00E9")
            ok("caf\u00E9") shouldBe "caf\u00E9"
        }
    })
