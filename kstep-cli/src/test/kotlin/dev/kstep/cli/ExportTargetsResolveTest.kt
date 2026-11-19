package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** `resolveCommand` for `kstep export --target/--all-targets` (ADR-0065): pure, no process. */
class ExportTargetsResolveTest :
    StringSpec({
        fun resolve(vararg args: String): CliCommand = resolveCommand(arrayOf("export", *args))

        val usage = CliCommand.ShowUsage(1)

        "--target step parses to a named selection" {
            resolve("a.kstep.kts", "--target", "step") shouldBe
                CliCommand.ExportTargets(
                    scriptPath = "a.kstep.kts",
                    selection = ExportTargetSelection.Named(listOf("step")),
                    outDir = null,
                    force = false,
                    jsonOutput = false,
                )
        }

        "--target is repeatable, keeps its order and takes names verbatim, also with a leading --" {
            resolve("a.kstep.kts", "--target", "stl", "--target", "--odd.stl", "--target", "b.step") shouldBe
                CliCommand.ExportTargets(
                    scriptPath = "a.kstep.kts",
                    selection = ExportTargetSelection.Named(listOf("stl", "--odd.stl", "b.step")),
                    outDir = null,
                    force = false,
                    jsonOutput = false,
                )
        }

        "--all-targets with every option parses, in any order" {
            resolve(
                "--output",
                "json",
                "--force",
                "--tolerance",
                "0.25",
                "a.kstep.kts",
                "--all-targets",
                "--out-dir",
                "dir",
                "--validate",
                "--require-geometry",
            ) shouldBe
                CliCommand.ExportTargets(
                    scriptPath = "a.kstep.kts",
                    selection = ExportTargetSelection.All,
                    outDir = "dir",
                    force = true,
                    jsonOutput = true,
                    validate = true,
                    requireGeometry = true,
                    toleranceMm = 0.25,
                )
        }

        "four targets are accepted, five are not" {
            val four = arrayOf("--target", "step", "--target", "glb", "--target", "stl", "--target", "3mf")
            (resolve("a.kstep.kts", *four) is CliCommand.ExportTargets) shouldBe true
            resolve("a.kstep.kts", *four, "--target", "x.step") shouldBe usage
        }

        "forbidden combinations resolve to ShowUsage(1)" {
            resolve("a.kstep.kts", "--out", "x.step", "--target", "step") shouldBe usage
            resolve("a.kstep.kts", "--out", "x.step", "--all-targets") shouldBe usage
            resolve("a.kstep.kts", "--target", "step", "--all-targets") shouldBe usage
            resolve("a.kstep.kts", "--all-targets", "--out-dir", "a", "--out-dir", "b") shouldBe usage
            resolve("a.kstep.kts", "--target", "step", "--target", "step") shouldBe usage
            resolve("a.kstep.kts", "--all-targets", "--tolerance", "abc") shouldBe usage
            resolve("a.kstep.kts", "--target") shouldBe usage
            resolve("a.kstep.kts", "--all-targets", "--out-dir") shouldBe usage
            resolve("a.kstep.kts", "--all-targets", "--tolerance") shouldBe usage
            resolve("--all-targets") shouldBe usage
            resolve("a.kstep.kts", "b.kstep.kts", "--all-targets") shouldBe usage
        }

        "--out-dir, --force and --tolerance without a target choice stay unknown flags" {
            resolve("a.kstep.kts", "--out-dir", "d") shouldBe usage
            resolve("a.kstep.kts", "--force") shouldBe usage
            resolve("a.kstep.kts", "--tolerance", "0.1") shouldBe usage
        }

        "every old form resolves to the very same Export as before" {
            val old =
                mapOf(
                    listOf("a.kstep.kts") to CliCommand.Export("a.kstep.kts", null, false),
                    listOf("a.kstep.kts", "--out", "b.step") to CliCommand.Export("a.kstep.kts", "b.step", false),
                    listOf("a.kstep.kts", "--output", "json") to CliCommand.Export("a.kstep.kts", null, true),
                    listOf("a.kstep.kts", "--validate") to
                        CliCommand.Export("a.kstep.kts", null, false, validate = true),
                    listOf("a.kstep.kts", "--require-geometry") to
                        CliCommand.Export("a.kstep.kts", null, false, requireGeometry = true),
                    listOf("--output", "json", "--out", "b.step", "a.kstep.kts", "--validate", "--require-geometry") to
                        CliCommand.Export("a.kstep.kts", "b.step", true, validate = true, requireGeometry = true),
                )
            for ((args, expected) in old) resolve(*args.toTypedArray()) shouldBe expected
        }

        "the usage text documents the new flags" {
            for (flag in listOf("--target", "--all-targets", "--out-dir", "--force")) {
                (USAGE_TEXT.contains(flag)) shouldBe true
            }
        }
    })
