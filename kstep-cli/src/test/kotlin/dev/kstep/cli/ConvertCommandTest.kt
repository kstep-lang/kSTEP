package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val RLO = 0x202E.toChar()
private val BEL = 0x07.toChar()

class ConvertCommandTest :
    StringSpec({
        class Run(
            val code: Int,
            val out: String,
            val err: String,
        )

        fun run(
            input: Path,
            target: ConvertTarget,
            outDir: Path? = null,
            force: Boolean = false,
        ): Run {
            val o = ByteArrayOutputStream()
            val e = ByteArrayOutputStream()
            val code =
                runConvert(
                    CliCommand.Convert(input.toString(), target, outDir?.toString(), force),
                    PrintStream(o, true, "UTF-8"),
                    PrintStream(e, true, "UTF-8"),
                )
            return Run(code, o.toString("UTF-8"), e.toString("UTF-8"))
        }

        fun tmp(): Path = Files.createTempDirectory("kstep-convert-cmd").toRealPath()

        "kts to okf to kts round trip end to end" {
            val d = tmp()
            val src = "val answer = 42\nprintln(answer)\n"
            val f = Files.writeString(d.resolve("demo.kstep.kts"), src)
            val a = run(f, ConvertTarget.OKF)
            a.code shouldBe 0
            a.out shouldContain "Wrote demo.md"
            val back = Files.createDirectory(d.resolve("back"))
            val b = run(d.resolve("demo.md"), ConvertTarget.KTS, back)
            b.code shouldBe 0
            Files.readString(back.resolve("demo.kstep.kts")) shouldBe src
        }

        "collision without force writes nothing, with force replaces" {
            val d = tmp()
            val note = "```kstep\na\n```\n\n```kstep\nb\n```\n"
            val f = Files.writeString(d.resolve("n.md"), note)
            Files.writeString(d.resolve("n-2.kstep.kts"), "old")
            val r = run(f, ConvertTarget.KTS)
            r.code shouldBe 1
            r.err shouldContain "--force"
            Files.exists(d.resolve("n-1.kstep.kts")) shouldBe false
            Files.readString(d.resolve("n-2.kstep.kts")) shouldBe "old"
            run(f, ConvertTarget.KTS, force = true).code shouldBe 0
            Files.readString(d.resolve("n-2.kstep.kts")) shouldBe "b\n"
        }

        "wrong extension and missing out dir fail" {
            val d = tmp()
            val f = Files.writeString(d.resolve("x.txt"), "a")
            run(f, ConvertTarget.OKF).code shouldBe 1
            val k = Files.writeString(d.resolve("x.kstep.kts"), "a\n")
            run(k, ConvertTarget.OKF, d.resolve("missing")).code shouldBe 1
            Files.exists(d.resolve("missing")) shouldBe false
        }

        "three blocks give three files and three lines" {
            val d = tmp()
            val f = Files.writeString(d.resolve("t.md"), "```kstep\na\n```\n```kstep\nb\n```\n```kstep\nc\n```\n")
            val r = run(f, ConvertTarget.KTS)
            r.code shouldBe 0
            r.out
                .lines()
                .filter { it.isNotEmpty() }
                .size shouldBe 3
        }

        "hostile note names never reach the output raw" {
            val d = tmp()
            val f = Files.writeString(d.resolve("a${RLO}b$BEL.md"), "no block here")
            val r = run(f, ConvertTarget.KTS)
            r.code shouldBe 1
            r.err shouldNotContain RLO.toString()
            r.err shouldNotContain "\u0007"
            r.err shouldContain "U+202E"
            val g = Files.writeString(d.resolve("c${RLO}d.md"), "```kstep\nx\n```\n")
            val ok = run(g, ConvertTarget.KTS)
            ok.code shouldBe 0
            ok.out shouldNotContain RLO.toString()
            Files.exists(d.resolve("c_d.kstep.kts")) shouldBe true
        }

        "empty script and hostile sizes fail cleanly".config(timeout = 30.seconds) {
            val d = tmp()
            run(Files.writeString(d.resolve("e.kstep.kts"), "\n"), ConvertTarget.OKF).code shouldBe 1
            val big = Files.writeString(d.resolve("big.md"), "x".repeat(1 shl 20))
            run(big, ConvertTarget.KTS).code shouldBe 1
            val over = Files.write(d.resolve("over.md"), ByteArray((1 shl 20) + 1) { 'x'.code.toByte() })
            run(over, ConvertTarget.KTS).code shouldBe 1
        }
    })
