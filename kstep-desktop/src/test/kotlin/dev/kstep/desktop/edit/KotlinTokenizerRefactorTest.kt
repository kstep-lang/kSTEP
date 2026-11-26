package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** The state-machine tokenizer of ADR-0088 against the frozen pre-refactor copy. */
class KotlinTokenizerRefactorTest :
    StringSpec({
        fun same(text: String) {
            val now = KotlinTokenizer.tokenize(text)
            val legacy = LegacyKotlinTokenizer.tokenize(text)
            if (now != legacy) throw AssertionError("tokenizer differs from the oracle for ${text.take(200)}")
        }

        "degenerate and comment edge cases equal the oracle" {
            val cases =
                listOf(
                    "",
                    "\"",
                    "\\",
                    "'",
                    "/",
                    "/*",
                    "\${",
                    "\$",
                    "}",
                    "@",
                    "`",
                    "\"\"\"",
                    "0x",
                    "1e",
                    "/*/",
                    "*/*",
                    "/*/ x */",
                    "/* /*/ */ */",
                    "/**/",
                    "/*/*/",
                    "*/",
                    "/* a\nb /* c\n*/ d\n*/ val",
                    "\"\"\"a\${x}\nb\"\"\" val",
                    "\"a \${ /* \n */ 1 } b\"",
                    "\"\"\"\"\"\"\"",
                    "\"\"\"\n\"\"\"\"",
                    "val s = \"😀\"\r\nval t = 1\r\n// c\r\n",
                    "\"abc\\\n",
                    "'a\n'b'",
                )
            for (c in cases) same(c)
        }

        "random texts equal the oracle" {
            val random = Random(20261126)
            val fragments =
                listOf(
                    "\"",
                    "\"\"\"",
                    "\\",
                    "\\\"",
                    "'",
                    "\${",
                    "\$x",
                    "{",
                    "}",
                    "/*",
                    "*/",
                    "/",
                    "*",
                    "//",
                    "\n",
                    "\r\n",
                    " ",
                    "val",
                    "fun",
                    "shape",
                    "x",
                    "1",
                    "0x1F",
                    "1.5e3",
                    "@Ann",
                    "`",
                    "😀",
                    "\uD83D",
                    "\uDE00",
                )
            repeat(5_000) {
                val length = random.nextInt(0, 60)
                val text = (0 until length).joinToString("") { fragments[random.nextInt(fragments.size)] }
                same(text)
            }
            val alphabet = "ab1 \n\"'\\\$/{}*@`x_.e-+(#"
            repeat(2_000) {
                same(String(CharArray(random.nextInt(0, 120)) { alphabet[random.nextInt(alphabet.length)] }))
            }
            true shouldBe true
        }
    })
