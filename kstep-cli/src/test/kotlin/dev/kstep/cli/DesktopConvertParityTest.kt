package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** The desktop facade and `kstep convert --output json` must say the same (ADR-0077): both run `computeConvert`. */
class DesktopConvertParityTest :
    StringSpec({
        fun tree(): Path {
            val d = Files.createTempDirectory("kstep-parity").toRealPath()
            Files.writeString(d.resolve("a.md"), "```kstep\nx\n```\n\n```kstep\ny\n```\n")
            Files.writeString(d.resolve("b.md"), "```kstep\nz\n```\n")
            Files.writeString(d.resolve("c.md"), "no block\n")
            Files.writeString(d.resolve(".h.md"), "```kstep\nz\n```\n")
            Files.createDirectory(d.resolve("sub"))
            Files.writeString(d.resolve("sub").resolve("deep.md"), "```kstep\nq\n```\n")
            return d
        }

        fun put(
            root: Path,
            rel: String,
            text: String,
        ) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }

        /** One scenario: how the input and output are derived from a root, and what the command line looked like. */
        class Spec(
            val input: (Path) -> Path = { it },
            val outDir: (Path) -> Path? = { null },
            val target: ConvertTarget = ConvertTarget.KTS,
            val recursive: Boolean = false,
            val force: Boolean = false,
            val title: String? = null,
            val tags: List<String> = emptyList(),
            val limits: ConvertDirLimits = ConvertDirLimits(),
        )

        fun cliJson(
            root: Path,
            s: Spec,
        ): JsonObject {
            val o = ByteArrayOutputStream()
            runConvert(
                CliCommand.Convert(
                    s.input(root).toString(),
                    s.target,
                    s.outDir(root)?.toString(),
                    s.force,
                    true,
                    s.title,
                    s.tags,
                    s.recursive,
                ),
                PrintStream(o, true, "UTF-8"),
                PrintStream(ByteArrayOutputStream(), true, "UTF-8"),
                limits = s.limits,
            )
            return Json.parseToJsonElement(o.toString("UTF-8")).jsonObject
        }

        fun facade(
            root: Path,
            s: Spec,
        ): ConvertOutcome =
            DesktopConvert.run(
                DesktopConvertRequest(
                    s.input(root),
                    s.target,
                    s.outDir(root),
                    s.recursive,
                    s.force,
                    s.title,
                    s.tags,
                ),
                s.limits,
            )

        fun str(
            o: JsonObject,
            key: String,
        ): String? = o[key]?.jsonPrimitive?.content

        fun int(
            o: JsonObject,
            key: String,
        ): Int? = str(o, key)?.toInt()

        fun compareFiles(
            json: JsonArray?,
            files: List<ConvertedFile>,
        ) {
            val expected = json.orEmpty().map { it.jsonObject }
            files.map { listOf(it.name, it.source, it.block, it.target, it.blockName) } shouldBe
                expected.map {
                    listOf(
                        str(it, "name"),
                        str(it, "source"),
                        int(it, "block"),
                        str(it, "target"),
                        str(it, "blockName"),
                    )
                }
        }

        fun compareIssues(
            json: JsonArray?,
            issues: List<ConvertIssue>,
            kindKey: String,
        ) {
            val expected = json.orEmpty().map { it.jsonObject }
            issues.map { listOf(it.code, it.kind, it.message, it.source, it.line) } shouldBe
                expected.map {
                    listOf(str(it, "code"), str(it, kindKey), str(it, "message"), str(it, "source"), int(it, "line"))
                }
        }

        /** Runs the CLI on copy A and the facade on copy B (both built by [setup] and [pre]) and compares everything. */
        fun parity(
            spec: Spec,
            setup: (Path) -> Unit = {},
            pre: (Path) -> Unit = {},
        ): Pair<JsonObject, ConvertOutcome> {
            val a = tree().also(setup).also(pre)
            val b = tree().also(setup).also(pre)
            val cli = cliJson(a, spec)
            val out = facade(b, spec)
            str(cli, "status") shouldBe (if (out is ConvertOutcome.Success) "success" else "error")
            compareFiles(cli["written"] as? JsonArray, out.written)
            val skipped = cli["skipped"]?.jsonArray.orEmpty().map { it.jsonObject }
            out.skipped.map { listOf(it.source, it.reason.jsonKind, it.line) } shouldBe
                skipped.map { listOf(str(it, "source"), str(it, "reason"), int(it, "line")) }
            compareIssues(cli["warnings"] as? JsonArray, out.warnings.take(spec.limits.maxReportedErrors), "kind")
            if (out is ConvertOutcome.Failure) {
                val first = out.errors.first()
                listOf(first.code, first.kind, first.message, first.source) shouldBe
                    listOf(str(cli, "code"), str(cli, "errorKind"), str(cli, "message"), str(cli, "source"))
                val errs = cli["errors"] as? JsonArray
                if (errs != null) {
                    compareIssues(errs, out.errors.take(spec.limits.maxReportedErrors), "errorKind")
                    (out.errors.size - errs.size) shouldBe (int(cli, "moreErrors") ?: 0)
                }
            }
            return cli to out
        }

        fun convertOnce(
            root: Path,
            spec: Spec,
        ) {
            runConvert(
                CliCommand.Convert(
                    spec.input(root).toString(),
                    spec.target,
                    spec.outDir(root)?.toString(),
                    false,
                    true,
                    spec.title,
                    spec.tags,
                    spec.recursive,
                ),
                PrintStream(ByteArrayOutputStream(), true, "UTF-8"),
                PrintStream(ByteArrayOutputStream(), true, "UTF-8"),
            )
        }

        fun outDir(root: Path): Path = Files.createDirectories(root.resolve("out"))

        "folder to kts with the golden tree" {
            val (_, out) = parity(Spec())
            out.shouldBeInstanceOf<ConvertOutcome.Success>().written.map { it.name } shouldContainExactly
                listOf("a-1.kstep.kts", "a-2.kstep.kts", "b.kstep.kts")
        }

        "single file to kts" {
            parity(Spec(input = { it.resolve("a.md") }))
        }

        "recursive with an output folder inside the source skips it" {
            val (_, out) =
                parity(
                    Spec(outDir = { it.resolve("out") }, recursive = true),
                    setup = { Files.createDirectories(it.resolve("out")) },
                )
            out.skipped.any { it.reason == ConvertSkipReason.OUTPUT_DIR } shouldBe true
        }

        "single script to okf with title and tag" {
            parity(
                Spec(
                    input = { it.resolve("s.kstep.kts") },
                    target = ConvertTarget.OKF,
                    title = "T",
                    tags = listOf("one"),
                ),
                setup = { put(it, "s.kstep.kts", "val a = 1\n") },
            )
        }

        "a conflict lists every existing target, and force replaces them" {
            val spec = Spec()
            val (_, out) = parity(spec, pre = { convertOnce(it, spec) })
            val failure = out.shouldBeInstanceOf<ConvertOutcome.Failure>()
            failure.isTargetConflict shouldBe true
            failure.conflicts shouldContainExactly listOf("a-1.kstep.kts", "a-2.kstep.kts", "b.kstep.kts")
            parity(Spec(force = true), pre = { convertOnce(it, spec) })
                .second
                .shouldBeInstanceOf<ConvertOutcome.Success>()
        }

        "wrong input type" {
            parity(
                Spec(input = { it.resolve("s.kstep.kts") }, target = ConvertTarget.KTS),
                setup = { put(it, "s.kstep.kts", "val a = 1\n") },
            ).second.shouldBeInstanceOf<ConvertOutcome.Failure>().errors.first().code shouldBe "KSTEP-C-002"
        }

        "recursive on a file" {
            parity(Spec(input = { it.resolve("a.md") }, recursive = true))
                .second
                .shouldBeInstanceOf<ConvertOutcome.Failure>()
        }

        "invalid and duplicate block names" {
            val (_, out) =
                parity(
                    Spec(recursive = true, outDir = { outDir(it) }),
                    setup = {
                        put(it, "s/bad.md", "```kstep {name=../x}\na\n```\n")
                        put(it, "s/dup.md", "```kstep {name=a}\na\n```\n```kstep {name=A}\nb\n```\n")
                    },
                )
            out
                .shouldBeInstanceOf<ConvertOutcome.Failure>()
                .errors
                .map { it.code }
                .toSet() shouldBe
                setOf("KSTEP-C-014", "KSTEP-C-015")
        }

        "an unknown block attribute is a warning" {
            val (_, out) =
                parity(
                    Spec(input = { it.resolve("n.md") }),
                    setup = { put(it, "n.md", "```kstep {name=x, type=y}\na\n```\n") },
                )
            out.warnings.single().code shouldBe "KSTEP-C-016"
        }

        "title with a folder" {
            parity(Spec(target = ConvertTarget.OKF, title = "T"))
                .second
                .shouldBeInstanceOf<ConvertOutcome.Failure>()
                .errors
                .first()
                .code shouldBe "KSTEP-C-006"
        }

        "a missing output folder" {
            parity(Spec(outDir = { it.resolve("nope") }))
                .second
                .shouldBeInstanceOf<ConvertOutcome.Failure>()
                .errors
                .first()
                .code shouldBe "KSTEP-C-007"
        }

        "a file name with a bidi control character is escaped the same way" {
            val (_, out) =
                parity(
                    Spec(input = { it.resolve("x‮y.md") }),
                    setup = { put(it, "x‮y.md", "```kstep\nq\n```\n") },
                )
            val name =
                out
                    .shouldBeInstanceOf<ConvertOutcome.Success>()
                    .written
                    .single()
                    .name
            name.contains('‮') shouldBe false
        }

        "the facade returns every error while the JSON is capped" {
            val limits = ConvertDirLimits(maxReportedErrors = 2)
            val root = tree()
            for (i in 1..5) put(root, "e$i.md", "```kstep {name=../x}\na\n```\n")
            val cli = cliJson(root, Spec(limits = limits))
            val out =
                facade(
                    tree().also { r ->
                        for (i in 1..5) put(r, "e$i.md", "```kstep {name=../x}\na\n```\n")
                    },
                    Spec(limits = limits),
                )
            val failure = out.shouldBeInstanceOf<ConvertOutcome.Failure>()
            cli["errors"]!!.jsonArray.size shouldBe 2
            failure.errors.size shouldBe 2 + (int(cli, "moreErrors") ?: 0)
        }

        "without an output folder the files land next to the source" {
            val single = tree()
            DesktopConvert
                .run(
                    DesktopConvertRequest(single.resolve("a.md"), ConvertTarget.KTS, null, false, false),
                ).shouldBeInstanceOf<ConvertOutcome.Success>()
            Files.exists(single.resolve("a-1.kstep.kts")) shouldBe true

            val folder = tree()
            DesktopConvert
                .run(
                    DesktopConvertRequest(folder, ConvertTarget.KTS, null, false, false),
                ).shouldBeInstanceOf<ConvertOutcome.Success>()
            Files.exists(folder.resolve("b.kstep.kts")) shouldBe true
            Files.readString(folder.resolve("b.md")) shouldBe "```kstep\nz\n```\n"
        }

        "an output folder equal to the source keeps the sources intact" {
            val d = tree()
            DesktopConvert
                .run(
                    DesktopConvertRequest(d, ConvertTarget.KTS, d, true, false),
                ).shouldBeInstanceOf<ConvertOutcome.Success>()
            Files.readString(d.resolve("a.md")) shouldBe "```kstep\nx\n```\n\n```kstep\ny\n```\n"
            Files.exists(d.resolve("sub/deep.kstep.kts")) shouldBe true
        }
    })
