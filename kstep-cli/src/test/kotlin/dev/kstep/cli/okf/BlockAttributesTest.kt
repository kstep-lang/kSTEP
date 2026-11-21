package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun blocks(vararg infos: String): List<FenceBlock> =
    MarkdownBlockExtractor.extract(infos.joinToString("") { "```kstep$it\nx\n```\n" }).blocks

private fun one(info: String): FenceBlock = blocks(info).single()

class BlockAttributesTest :
    StringSpec({
        "valid names in several spellings" {
            one(" {name=bracket}").name shouldBe "bracket"
            one(" {name=\"Bracket_v2\"}").name shouldBe "Bracket_v2"
            one(" { name = a.b-c }").name shouldBe "a.b-c"
            one("{name=x}").name shouldBe "x"
            one(" {name=" + "a".repeat(48) + "}").name shouldBe "a".repeat(48)
        }

        "unknown keys are collected next to the name" {
            val b = one(" {name=x, type=y z}")
            b.name shouldBe "x"
            b.nameIssue shouldBe null
            b.unknownKeys shouldBe listOf("type", "z")
        }

        "the old format is unchanged" {
            for (info in listOf("", " foo", " foo bar", "\t")) {
                val b = one(info)
                b.name shouldBe null
                b.nameIssue shouldBe null
                b.unknownKeys shouldBe emptyList()
                b.state shouldBe BlockState.OK
            }
        }

        "pandoc style tokens are unknown keys, not errors" {
            val b = one(" {.numberLines #id}")
            b.nameIssue shouldBe null
            b.unknownKeys shouldBe listOf(".numberLines", "#id")
        }

        "invalid names" {
            val bad =
                listOf(
                    "a".repeat(49),
                    "../x",
                    "a/b",
                    ".x",
                    "x.",
                    "a..b",
                    "ä",
                    "-x",
                    "a‮b",
                    "a\\b",
                )
            for (v in bad) {
                val b = one(" {name=\"$v\"}")
                b.name shouldBe null
                b.nameIssue?.kind shouldBe BlockNameIssueKind.INVALID
            }
            one(" {name=a b}").unknownKeys shouldBe listOf("b")
        }

        "malformed attribute lists" {
            for (info in listOf(" {name=", " {name=}", " {name=\"abc}", " {name=a name=b}", " {name}", " {name=a")) {
                val b = one(info)
                b.name shouldBe null
                b.nameIssue?.kind shouldBe BlockNameIssueKind.MALFORMED
            }
        }

        "an unterminated list without a name is only noted" {
            val b = one(" {type=x")
            b.nameIssue shouldBe null
            b.attrUnterminated shouldBe true
        }

        "duplicates are found case-insensitively, the first block keeps the name" {
            val bs = blocks(" {name=Bracket}", " {name=bracket}", " {name=other}")
            bs[0].name shouldBe "Bracket"
            bs[1].name shouldBe null
            bs[1].nameIssue?.kind shouldBe BlockNameIssueKind.DUPLICATE
            bs[1].nameIssue?.firstLine shouldBe 1
            bs[2].name shouldBe "other"
        }

        "hostile info lines are bounded" {
            val text = "```kstep {name=" + "a".repeat(64 * 1024) + "\nx\n```\n"
            val b = MarkdownBlockExtractor.extract(text).blocks.single()
            b.nameIssue?.kind shouldBe BlockNameIssueKind.MALFORMED
            val many = "```kstep {" + (1..10_000).joinToString(" ") { "k$it" } + "}\nx\n```\n"
            val m = MarkdownBlockExtractor.extract(many).blocks.single()
            (m.unknownKeys.size <= OkfLimits.MAX_UNKNOWN_KEYS) shouldBe true
            val keys = "```kstep {" + "k".repeat(100) + "}\nx\n```\n"
            MarkdownBlockExtractor
                .extract(keys)
                .blocks
                .single()
                .unknownKeys
                .single()
                .length shouldBe 32
            val ex =
                MarkdownBlockExtractor.extract(
                    (1..OkfLimits.MAX_BLOCKS).joinToString("") { "```kstep {name=n$it}\nx\n```\n" },
                )
            ex.blocks shouldHaveSize OkfLimits.MAX_BLOCKS
            ex.blocks.all { it.name != null } shouldBe true
        }

        "extraction of an old note is independent of the attribute parser" {
            val note = "---\ntitle: T\n---\n\n```kstep\na\n```\n\n```kstep foo\nb\n```\n"
            val ex = MarkdownBlockExtractor.extract(note)
            ex.blocks.map { it.openLine to it.state } shouldBe listOf(5 to BlockState.OK, 9 to BlockState.OK)
        }

        "extractScripts names files after the block names" {
            val note = "```kstep {name=bracket}\na\n```\n\n```kstep\nb\n```\n\n```kstep {name=\"Plate\"}\nc\n```\n"
            val r = OkfConverter.extractScripts(note, "note") as OkfConverter.ExtractResult.Ok
            r.scripts.map { it.fileName } shouldBe
                listOf("note-bracket.kstep.kts", "note-2.kstep.kts", "note-Plate.kstep.kts")
            r.scripts[0].blockName shouldBe "bracket"
        }

        "a single named block keeps the plain file name and its bytes" {
            val r = OkfConverter.extractScripts("```kstep {name=a}\nx\n```\n", "n") as OkfConverter.ExtractResult.Ok
            r.scripts.single().fileName shouldBe "n.kstep.kts"
            r.scripts.single().source shouldBe "x\n"
            r.scripts.single().blockName shouldBe "a"
        }

        "a name equal to a block number collides" {
            val note = "```kstep {name=2}\na\n```\n\n```kstep\nb\n```\n"
            val r = OkfConverter.extractScripts(note, "n") as OkfConverter.ExtractResult.Rejected
            r.kind shouldBe OkfConverter.RejectKind.DUPLICATE_NAME
        }

        "a name problem in an empty block still counts" {
            val r =
                OkfConverter.extractScripts("```kstep {name=../x}\n```\n\n```kstep\nb\n```\n", "n")
                    as OkfConverter.ExtractResult.Rejected
            r.kind shouldBe OkfConverter.RejectKind.INVALID_NAME
            r.reason shouldContain "invalid name"
            r.reason shouldNotContain "../x"
        }

        "windows device names are harmless because of the stem prefix" {
            val r =
                OkfConverter.extractScripts("```kstep {name=con}\na\n```\n```kstep\nb\n```\n", "note")
                    as OkfConverter.ExtractResult.Ok
            r.scripts[0].fileName shouldBe "note-con.kstep.kts"
        }
    })
