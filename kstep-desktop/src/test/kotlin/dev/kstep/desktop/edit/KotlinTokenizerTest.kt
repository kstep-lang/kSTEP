package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class KotlinTokenizerTest :
    StringSpec({
        fun tokens(text: String) = KotlinTokenizer.tokenize(text).map { text.substring(it.start, it.end) to it.kind }

        fun kindsOf(text: String) = KotlinTokenizer.tokenize(text).map { it.kind }

        fun checkInvariants(text: String) {
            var last = 0
            for (t in KotlinTokenizer.tokenize(text)) {
                (t.start >= last) shouldBe true
                (t.end > t.start) shouldBe true
                (t.end <= text.length) shouldBe true
                last = t.end
            }
        }

        "keywords match whole identifiers only" {
            tokens("val x = if (a) 1 else valid") shouldBe
                listOf(
                    "val" to TokenKind.KEYWORD,
                    "if" to TokenKind.KEYWORD,
                    "1" to TokenKind.NUMBER,
                    "else" to TokenKind.KEYWORD,
                )
        }

        "DSL names are highlighted as DSL" {
            tokens("stepFile { shape(x) }") shouldBe listOf("stepFile" to TokenKind.DSL, "shape" to TokenKind.DSL)
        }

        "strings, escapes and char literals" {
            tokens("""val a = "x\"y" + 'c' + '\''""").map { it.second } shouldBe
                listOf(TokenKind.KEYWORD, TokenKind.STRING, TokenKind.STRING, TokenKind.STRING)
        }

        "an unterminated string ends at the line end" {
            val text = "val a = \"abc\nval b"
            tokens(text) shouldBe
                listOf(
                    "val" to TokenKind.KEYWORD,
                    "\"abc" to TokenKind.STRING,
                    "val" to TokenKind.KEYWORD,
                )
        }

        "an unterminated raw string and block comment run to the end" {
            tokens("\"\"\"a\nb") shouldBe listOf("\"\"\"a\nb" to TokenKind.STRING)
            tokens("/* a\nb") shouldBe listOf("/* a\nb" to TokenKind.COMMENT)
        }

        "block comments nest" {
            tokens("/* a /* b */ c */ val") shouldBe
                listOf("/* a /* b */ c */" to TokenKind.COMMENT, "val" to TokenKind.KEYWORD)
        }

        "line comment stops before the line break" {
            tokens("// hi\nval") shouldBe listOf("// hi" to TokenKind.COMMENT, "val" to TokenKind.KEYWORD)
        }

        "string templates" {
            tokens("\"a \$x b \${y + 1} c\"") shouldBe
                listOf(
                    "\"a " to TokenKind.STRING,
                    "\$x" to TokenKind.TEMPLATE,
                    " b " to TokenKind.STRING,
                    "\${" to TokenKind.TEMPLATE,
                    "1" to TokenKind.NUMBER,
                    "}" to TokenKind.TEMPLATE,
                    " c\"" to TokenKind.STRING,
                )
        }

        "nested strings inside a template" {
            val text = "\"a \${ \"b \${ 1 } c\" } d\""
            kindsOf(text).count { it == TokenKind.TEMPLATE } shouldBe 4
            checkInvariants(text)
        }

        "template nesting beyond the limit does not recurse" {
            val deep = (1..60).joinToString("") { "\"\${" } + "x" + (1..60).joinToString("") { "}\"" }
            checkInvariants(deep)
            val depth = KotlinTokenizer.tokenize(deep).count { it.kind == TokenKind.TEMPLATE && deep[it.start] == '$' }
            (depth <= KotlinTokenizer.MAX_TEMPLATE_DEPTH) shouldBe true
        }

        "numbers" {
            tokens("0xFF 0b1_0 1_000 1.5e-3 2L 3.0f 4u").map { it.second }.distinct() shouldBe listOf(TokenKind.NUMBER)
            tokens("0xFF 0b1_0 1_000 1.5e-3 2L 3.0f 4u").size shouldBe 7
            tokens("a1 1abc").shouldBeEmpty()
        }

        "annotations including use-site targets" {
            tokens("@Suppress(\"x\") @file:JvmName(\"y\")").map { it.first }.filter { it.startsWith("@") } shouldBe
                listOf("@Suppress", "@file:JvmName")
        }

        "backtick identifiers are not highlighted" {
            tokens("`val`") shouldBe emptyList()
        }

        "degenerate input never throws" {
            for (text in listOf("", "\"", "\\", "'", "/", "/*", "\${", "\$", "}", "@", "`", "\"\"\"", "0x", "1e")) {
                checkInvariants(text)
            }
        }

        "surrogate pairs and CRLF text keep the invariants" {
            checkInvariants("val s = \"😀\"\r\nval t = 1\r\n// c\r\n")
        }

        "random input keeps the invariants" {
            val random = Random(42)
            val alphabet = "ab1 \n\"'\\\$/{}*@`x_.e-+(#"
            repeat(300) {
                val text = String(CharArray(random.nextInt(0, 200)) { alphabet[random.nextInt(alphabet.length)] })
                checkInvariants(text)
            }
        }

        "a 100000 character line is linear" {
            val text = "val x = 1; ".repeat(10_000)
            val started = System.nanoTime()
            checkInvariants(text)
            ((System.nanoTime() - started) / 1_000_000).toInt() shouldBeLessThan 30_000
        }

        "a 1 MiB script tokenizes within the bound" {
            val line = "val a = shape(\"x \$y\") + 12 // comment\n"
            val text = line.repeat(1_048_576 / line.length)
            val started = System.nanoTime()
            val count = KotlinTokenizer.tokenize(text).size
            val ms = (System.nanoTime() - started) / 1_000_000
            println("tokenizer: 1 MiB, $count tokens, $ms ms")
            ms.toInt() shouldBeLessThan 30_000
        }
    })
