package dev.kstep.cli.okf

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class OkfMetadataTest :
    StringSpec({
        fun ok(
            title: String?,
            tags: List<String> = emptyList(),
        ): OkfConverter.MetadataCheck.Ok =
            OkfConverter.checkMetadata(title, tags).shouldBeInstanceOf<OkfConverter.MetadataCheck.Ok>()

        fun rejected(
            title: String?,
            tags: List<String> = emptyList(),
        ): String =
            OkfConverter.checkMetadata(title, tags).shouldBeInstanceOf<OkfConverter.MetadataCheck.Rejected>().reason

        fun roundtrip(
            title: String?,
            tags: List<String>,
        ) {
            val m = ok(title, tags)
            val effective = m.title ?: "Default"
            val w =
                OkfConverter
                    .wrapAsOkf(
                        "val x = 1\n",
                        effective,
                        m.tags,
                    ).shouldBeInstanceOf<OkfConverter.WrapResult.Ok>()
            val fm = FrontmatterParser.parse(w.note)
            fm.ignored shouldBe false
            fm.title shouldBe effective
            fm.tags shouldBe m.tags
        }

        "tricky titles and tags survive the round trip through the parser" {
            val titles =
                listOf(
                    "Grüße ☃ 😀",
                    "say \"hi\" \\ back",
                    "a: b # c",
                    "'quoted'",
                    "\"dq",
                    "- dash",
                    "[list]",
                    "&anchor",
                    "*alias",
                    "!tag",
                    "|pipe",
                    ">fold",
                    "{map}",
                )
            for (t in titles) roundtrip(t, listOf("a,b", "x]", "q\"r", "back\\slash", "äö", "k:v", "a#b"))
        }

        "limits: title 100 code points, tag 64, 32 tags including kstep" {
            ok("a".repeat(100))
            rejected("a".repeat(101))
            ok("😀".repeat(100))
            rejected("😀".repeat(101))
            ok(null, listOf("t".repeat(64)))
            rejected(null, listOf("t".repeat(65)))
            ok(null, (1..31).map { "t$it" }).tags.size shouldBe 32
            rejected(null, (1..32).map { "t$it" }) shouldContain "more than 32"
        }

        "tags that are too long together are rejected clearly, not ignored silently" {
            val tags = (1..31).map { String(Character.toChars(0x1F600 + it)) + "\\".repeat(63) }
            rejected(null, tags) shouldContain "too long together"
        }

        "hostile characters are rejected" {
            val bad =
                listOf(0x0A, 0x0D, 0x09, 0x85, 0x2028, 0x2029, 0x202E, 0x2066, 0x061C, 0x00, 0xFEFF, 0xD800).map {
                    it.toChar().toString()
                }
            for (c in bad) {
                withClue("title U+" + c[0].code.toString(16)) { rejected("a${c}b") }
                withClue("tag U+" + c[0].code.toString(16)) { rejected(null, listOf("a${c}b")) }
            }
            rejected("   ")
            rejected(" ")
            rejected("")
            rejected(null, listOf("has space"))
            rejected(null, listOf("a b"))
            rejected(null, listOf("#tag")) shouldContain "without '#'"
            rejected(null, listOf(""))
        }

        "the title is trimmed of spaces only and duplicates are removed" {
            ok("  hi  ").title shouldBe "hi"
            ok(null, listOf("kstep", "a", "a")).tags shouldBe listOf("kstep", "a")
            ok(null).tags shouldBe listOf("kstep")
            ok(null).title shouldBe null
        }

        "golden: the default note is byte-identical to the previous release" {
            val note =
                (
                    OkfConverter
                        .wrapAsOkf(
                            "val x = 1\n",
                            OkfConverter.titleFromStem("demo"),
                        ).shouldBeInstanceOf<OkfConverter.WrapResult.Ok>()
                ).note
            note shouldBe "---\ntitle: \"demo\"\ntags: [kstep]\n---\n\n# demo\n\n```kstep\nval x = 1\n```\n"
        }
    })
